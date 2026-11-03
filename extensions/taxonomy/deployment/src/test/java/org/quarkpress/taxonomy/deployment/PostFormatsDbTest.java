package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.taxonomy.PostFormats;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

/** Post formats: terms of WordPress's built-in {@code post_format} taxonomy, registered as WordPress registers it. */
class PostFormatsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(PostFormatsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    Terms terms;

    @Inject
    PostFormats formats;

    @Inject
    Taxonomies taxonomies;

    private SiteId site;
    private UserId bob;
    private UserId dave;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        bob = TestData.profile(dataSource, "bob");
        TestData.member(dataSource, site, bob, "[\"editor\"]");
        dave = TestData.profile(dataSource, "dave");
        TestData.member(dataSource, site, dave, "[\"contributor\"]");
    }

    private <T> T as(UserId user, Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(user), work);
    }

    private PostId draft(UserId author, String title) throws Exception {
        return as(
                        author,
                        () -> posts.create(PostChanges.builder()
                                .title(title)
                                .status("draft")
                                .build()))
                .id();
    }

    private List<TermSnapshot> formatTerms(PostId post) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> terms.postTerms(post, "post_format"));
    }

    @Test
    void formatsAreTermsOfThePostFormatTaxonomy() throws Exception {
        PostId post = draft(bob, "Formatted");
        assertThat(as(bob, () -> formats.of(post))).isEqualTo("standard");
        assertThat(as(bob, () -> formats.exists("aside"))).isFalse();
        assertThat(as(bob, () -> formats.exists("standard"))).isFalse();

        as(bob, () -> {
            formats.set(post, "aside");
            return null;
        });
        assertThat(as(bob, () -> formats.of(post))).isEqualTo("aside");
        assertThat(formatTerms(post)).singleElement().satisfies(term -> {
            assertThat(term.slug()).isEqualTo("post-format-aside");
            assertThat(term.name()).isEqualTo("post-format-aside");
            assertThat(term.taxonomy()).isEqualTo("post_format");
        });
        assertThat(as(bob, () -> formats.exists("aside"))).isTrue();
        assertThat(as(bob, () -> formats.exists("standard"))).isFalse();
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and taxonomy = 'post_format'",
                        site.value()))
                .isEqualTo(1);

        // another post reuses the term; a second format replaces the first
        PostId second = draft(bob, "Second");
        as(bob, () -> {
            formats.set(second, "aside");
            formats.set(post, "quote");
            return null;
        });
        assertThat(as(bob, () -> formats.of(second))).isEqualTo("aside");
        assertThat(as(bob, () -> formats.of(post))).isEqualTo("quote");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where site_id = ? and taxonomy = 'post_format'",
                        site.value()))
                .isEqualTo(2);

        as(bob, () -> {
            formats.set(post, "standard");
            return null;
        });
        assertThat(as(bob, () -> formats.of(post))).isEqualTo("standard");
        assertThat(formatTerms(post)).isEmpty();
        // the term stays for the other post
        assertThat(as(bob, () -> formats.exists("aside"))).isTrue();
        assertThat(as(bob, () -> formats.exists("quote"))).isTrue();

        assertThatThrownBy(() -> as(bob, () -> {
                    formats.set(post, "bogus");
                    return null;
                }))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(as(bob, () -> formats.exists("bogus"))).isFalse();
        assertThat(as(bob, () -> formats.of(post))).isEqualTo("standard");
    }

    @Test
    void settingAFormatNeedsEditPostOnly() throws Exception {
        PostId own = draft(dave, "Dave's");
        PostId bobs = draft(bob, "Bob's");
        as(dave, () -> {
            formats.set(own, "video");
            return null;
        });
        assertThat(as(dave, () -> formats.of(own))).isEqualTo("video");
        assertThatThrownBy(() -> as(dave, () -> {
                    formats.set(bobs, "video");
                    return null;
                }))
                .isInstanceOf(ForbiddenException.class);
        assertThat(as(bob, () -> formats.of(bobs))).isEqualTo("standard");
        assertThatThrownBy(() -> Identities.onSite(site, Identities.anonymous(), () -> {
                    formats.set(bobs, "video");
                    return null;
                }))
                .isInstanceOf(UnauthorizedException.class);
    }

    @Test
    void theSlugsAndLabelsAreWordPresss() {
        assertThat(PostFormats.SLUGS)
                .containsExactly(
                        "standard", "aside", "chat", "gallery", "link", "image", "quote", "status", "video", "audio");
        assertThat(PostFormats.LABELS.keySet()).containsExactlyElementsOf(PostFormats.SLUGS);
        assertThat(PostFormats.LABELS.values())
                .containsExactly(
                        "Standard", "Aside", "Chat", "Gallery", "Link", "Image", "Quote", "Status", "Video", "Audio");
    }

    @Test
    void thePostFormatTaxonomyIsRegisteredAsWordPressRegistersIt() {
        TaxonomyDefinition format = taxonomies.find("post_format").orElseThrow();
        assertThat(format.showInRest()).isFalse();
        assertThat(format.restBase()).isEmpty();
        assertThat(format.publicly()).isTrue();
        assertThat(format.publiclyQueryable()).isTrue();
        assertThat(format.objectTypes()).containsExactly("post");
        assertThat(format.hierarchical()).isFalse();
        assertThat(format.labels())
                .containsEntry("name", "Formats")
                .containsEntry("singular_name", "Format")
                .containsEntry("all_items", "Formats")
                .containsEntry("menu_name", "Formats")
                .containsEntry("name_admin_bar", "Format")
                .containsEntry("archives", "Formats")
                .containsEntry("template_name", "Format Archives")
                .containsEntry("search_items", "Search Tags")
                .containsEntry("back_to_items", "&larr; Go to Tags")
                .doesNotContainKey("parent_item");
        assertThat(format.showUi()).isFalse();
        assertThat(format.showInMenu()).isFalse();
        assertThat(format.showInNavMenus()).isTrue();
        assertThat(format.showTagcloud()).isFalse();
        assertThat(format.showInQuickEdit()).isFalse();
        assertThat(format.showAdminColumn()).isFalse();
        assertThat(format.capabilities())
                .containsExactlyInAnyOrderEntriesOf(Map.of(
                        "manage_terms", "manage_categories",
                        "edit_terms", "manage_categories",
                        "delete_terms", "manage_categories",
                        "assign_terms", "edit_posts"));
        assertThat(format.defaultTerm()).isEmpty();
        assertThat(format.description()).isEmpty();
        assertThat(format.sort()).isFalse();
        assertThat(format.builtIn()).isTrue();
        assertThat(taxonomies.forType("post").stream().map(TaxonomyDefinition::key))
                .containsExactly("category", "post_tag", "post_format");
    }
}
