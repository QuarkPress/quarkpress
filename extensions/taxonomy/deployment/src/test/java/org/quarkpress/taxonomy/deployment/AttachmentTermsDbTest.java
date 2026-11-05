package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;
import org.quarkpress.taxonomy.runtime.DefaultTerms;

/**
 * Attachments are announced with events of their own, not as post saves, yet WordPress gives a new or updated
 * attachment the default term of every taxonomy registered for attachments, as for any type.
 */
class AttachmentTermsDbTest {

    @Taxonomy(key = "folder", objectTypes = "attachment", defaultTerm = "Unsorted")
    static class Folder {}

    @Taxonomy(key = "genre", objectTypes = "post", defaultTerm = "General")
    static class Genre {}

    @RegisterExtension
    static final QuarkusExtensionTest app =
            TaxonomyApp.multisite(AttachmentTermsDbTest.class, Folder.class, Genre.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    Terms terms;

    @Inject
    DefaultTerms defaults;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostLocks locks;

    private SiteId site;
    private UserId editor;

    @BeforeEach
    void newSite() throws Exception {
        site = TestData.site(dataSource);
        // a site created after startup gets its custom default terms when it is created; this one is written by JDBC
        Identities.onSite(site, Identities.system(), () -> {
            locks.lock(site);
            defaults.ensure(site, taxonomies.require("folder"));
            return defaults.ensure(site, taxonomies.require("genre"));
        });
        editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, site, editor, "[\"editor\"]");
    }

    private PostSnapshot create(PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> posts.create(changes.build()));
    }

    private PostSnapshot update(PostId post, PostChanges.Builder changes) throws Exception {
        return Identities.onSite(site, Identities.user(editor), () -> posts.update(post, changes.build()));
    }

    private List<String> slugs(PostId post, String taxonomy) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> terms.postTerms(post, taxonomy)).stream()
                .map(TermSnapshot::slug)
                .toList();
    }

    private TermId term(String taxonomy, String slug) {
        return TermId.of(Jdbc.count(
                dataSource,
                "select id from qp_term where site_id = ? and taxonomy = ? and slug = ?",
                site.value(),
                taxonomy,
                slug));
    }

    private long count(TermId term) {
        return Jdbc.count(dataSource, "select count from qp_term where id = ?", term.value());
    }

    private long assignments(PostId post) {
        return Jdbc.count(
                dataSource,
                "select count(*) from qp_term_assignment where site_id = ? and post_id = ?",
                site.value(),
                post.value());
    }

    @Test
    void creatingAnAttachmentAssignsTheDefaultTermAndRecounts() throws Exception {
        PostSnapshot parent = create(PostChanges.builder().title("Parent").status("publish"));
        PostSnapshot file =
                create(PostChanges.builder().type("attachment").title("File").parent(parent.id()));
        assertThat(slugs(file.id(), "folder")).containsExactly("unsorted");
        // an attachment that inherits from a published parent is counted
        assertThat(count(term("folder", "unsorted"))).isEqualTo(1);
    }

    @Test
    void anAttachmentWhoseTermWasRemovedGetsItAgainOnUpdate() throws Exception {
        PostSnapshot parent = create(PostChanges.builder().title("Parent").status("publish"));
        PostSnapshot file =
                create(PostChanges.builder().type("attachment").title("File").parent(parent.id()));
        TermId unsorted = term("folder", "unsorted");
        Jdbc.update(
                dataSource,
                "delete from qp_term_assignment where site_id = ? and post_id = ?",
                site.value(),
                file.id().value());
        Jdbc.update(dataSource, "update qp_term set count = 0 where id = ?", unsorted.value());
        assertThat(slugs(file.id(), "folder")).isEmpty();
        update(file.id(), PostChanges.builder().title("Renamed"));
        assertThat(slugs(file.id(), "folder")).containsExactly("unsorted");
        assertThat(count(unsorted)).isEqualTo(1);
    }

    @Test
    void anAttachmentWithAnotherTermOfTheTaxonomyKeepsIt() throws Exception {
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("File"));
        TermId photos = TestData.term(dataSource, site, "folder", "Photos", "photos", null);
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> terms.setPostTerms(file.id(), "folder", List.of(TermRef.id(photos)), false));
        update(file.id(), PostChanges.builder().title("Renamed"));
        assertThat(slugs(file.id(), "folder")).containsExactly("photos");
    }

    @Test
    void taxonomiesNotRegisteredForAttachmentsGiveThemNothing() throws Exception {
        PostSnapshot file = create(PostChanges.builder().type("attachment").title("File"));
        update(file.id(), PostChanges.builder().title("Renamed"));
        assertThat(slugs(file.id(), "genre")).isEmpty();
        assertThat(slugs(file.id(), "category")).isEmpty();
        assertThat(assignments(file.id())).isEqualTo(1);
    }

    @Test
    void anAutoDraftAttachmentGetsNone() throws Exception {
        PostSnapshot file =
                create(PostChanges.builder().type("attachment").title("File").status("auto-draft"));
        assertThat(slugs(file.id(), "folder")).isEmpty();
    }
}
