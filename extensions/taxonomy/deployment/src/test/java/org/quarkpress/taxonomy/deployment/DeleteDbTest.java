package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.runtime.PostLocks;
import org.quarkpress.taxonomy.Taxonomies;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermDeleted;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.TermUpdated;
import org.quarkpress.taxonomy.Terms;
import org.quarkpress.taxonomy.TermsAssigned;
import org.quarkpress.taxonomy.runtime.DefaultTerms;

/** Deleting terms (WordPress's {@code wp_delete_term}): posts and children handed on, counts exact, events in order. */
class DeleteDbTest {

    @Taxonomy(key = "genre", objectTypes = "post", hierarchical = true, defaultTerm = "General")
    static class Genre {}

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        List<String> seen() {
            return seen;
        }

        void assigned(@Observes TermsAssigned event) {
            seen.add("assigned " + event.post().value() + " " + event.taxonomy());
        }

        void updated(@Observes TermUpdated event) {
            seen.add("updated " + event.after().slug());
        }

        void deleted(@Observes TermDeleted event) {
            seen.add("deleted " + event.term().slug());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(DeleteDbTest.class, Genre.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    @Inject
    DefaultTerms defaults;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostLocks locks;

    @Inject
    Recorder recorder;

    private SiteId site;

    @BeforeEach
    void newSite() throws Exception {
        site = TestData.site(dataSource);
        Identities.onSite(site, Identities.system(), () -> {
            locks.lock(site);
            return defaults.ensure(site, taxonomies.require("genre"));
        });
        recorder.seen().clear();
    }

    private void assign(PostId post, String taxonomy, TermId... ids) throws Exception {
        List<TermRef> refs = java.util.Arrays.stream(ids).map(TermRef::id).toList();
        Identities.onSite(site, Identities.system(), () -> terms.setPostTerms(post, taxonomy, refs, false));
    }

    private Optional<TermSnapshot> delete(TermId term, String taxonomy) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> terms.delete(term, taxonomy));
    }

    private List<String> slugs(PostId post, String taxonomy) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> terms.postTerms(post, taxonomy)).stream()
                .map(TermSnapshot::slug)
                .toList();
    }

    private long count(TermId term) {
        return Jdbc.count(dataSource, "select count from qp_term where id = ?", term.value());
    }

    private long parent(TermId term) {
        return Jdbc.count(dataSource, "select coalesce(parent_id, 0) from qp_term where id = ?", term.value());
    }

    @Test
    void deletingACategoryReassignsEveryPostAndKeepsCountsExact() throws Exception {
        TermId world = TestData.term(dataSource, site, "category", "World", "world", null);
        TermId news = TestData.term(dataSource, site, "category", "News", "news", world);
        TermId local = TestData.term(dataSource, site, "category", "Local", "local", news);
        TermId sport = TestData.term(dataSource, site, "category", "Sport", "sport", null);
        PostId only = TestData.post(dataSource, site, "post", "publish", "only", "Only");
        PostId both = TestData.post(dataSource, site, "post", "publish", "both", "Both");
        PostId draft = TestData.post(dataSource, site, "post", "draft", "", "Draft");
        PostId trashed = TestData.post(dataSource, site, "post", "trash", "t__trashed", "Trashed");
        assign(only, "category", news);
        assign(both, "category", news, sport);
        assign(draft, "category", news);
        assign(trashed, "category", news);
        assertThat(count(news)).isEqualTo(2);
        recorder.seen().clear();
        Optional<TermSnapshot> deleted = delete(news, "category");
        assertThat(deleted.map(TermSnapshot::slug)).contains("news");
        assertThat(deleted.flatMap(TermSnapshot::parent)).contains(world);
        assertThat(slugs(only, "category")).containsExactly("uncategorized");
        assertThat(slugs(both, "category")).containsExactly("sport");
        assertThat(slugs(draft, "category")).containsExactly("uncategorized");
        assertThat(slugs(trashed, "category")).containsExactly("uncategorized");
        assertThat(count(TestData.defaultCategory(dataSource, site))).isEqualTo(1);
        assertThat(count(sport)).isEqualTo(1);
        assertThat(parent(local)).isEqualTo(world.value());
        assertThat(Jdbc.count(dataSource, "select version from qp_term where id = ?", local.value()))
                .isZero();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_term where id = ?", news.value()))
                .isZero();
        assertThat(recorder.seen())
                .containsExactly(
                        "assigned " + only.value() + " category",
                        "assigned " + both.value() + " category",
                        "assigned " + draft.value() + " category",
                        "assigned " + trashed.value() + " category",
                        "deleted news");
    }

    @Test
    void aTagDeleteJustRemovesIt() throws Exception {
        TermId x = TestData.term(dataSource, site, "post_tag", "x", "x", null);
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        assign(post, "post_tag", x);
        recorder.seen().clear();
        assertThat(delete(x, "post_tag")).isPresent();
        assertThat(slugs(post, "post_tag")).isEmpty();
        assertThat(recorder.seen()).containsExactly("assigned " + post.value() + " post_tag", "deleted x");
    }

    @Test
    void aCustomTaxonomysDefaultTermReplacesADeletedOnlyTerm() throws Exception {
        TermId fiction = TestData.term(dataSource, site, "genre", "Fiction", "fiction", null);
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        assign(post, "genre", fiction);
        delete(fiction, "genre");
        assertThat(slugs(post, "genre")).containsExactly("general");
    }

    @Test
    void theDefaultTermsCannotBeDeletedByAnyone() throws Exception {
        UserId admin = TestData.profile(dataSource, "administrator");
        TestData.member(dataSource, site, admin, "[\"administrator\"]");
        TermId uncategorized = TestData.defaultCategory(dataSource, site);
        assertThatThrownBy(() ->
                        Identities.onSite(site, Identities.user(admin), () -> terms.delete(uncategorized, "category")))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> delete(uncategorized, "category")).isInstanceOf(ForbiddenException.class);
        TermId general = TermId.of(Long.parseLong(
                TestData.option(dataSource, site, "default_term_genre").get(0)));
        assertThatThrownBy(() -> delete(general, "genre")).isInstanceOf(ForbiddenException.class);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where id in (?, ?)",
                        uncategorized.value(),
                        general.value()))
                .isEqualTo(2);
        assertThat(recorder.seen()).isEmpty();
    }

    @Test
    void missingTermsAndUnknownTaxonomiesGiveNothing() throws Exception {
        TermId category = TestData.term(dataSource, site, "category", "Cat", "cat", null);
        assertThat(delete(TermId.of(999_999_999L), "post_tag")).isEmpty();
        assertThat(delete(category, "bogus")).isEmpty();
        assertThat(delete(category, "post_tag")).isEmpty();
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]");
        assertThat(Identities.onSite(
                        site, Identities.user(author), () -> terms.delete(TermId.of(999_999_999L), "category")))
                .isEmpty();
        assertThat(Identities.onSite(site, Identities.user(author), () -> terms.delete(category, "bogus")))
                .isEmpty();
        SiteId other = TestData.site(dataSource);
        TermId elsewhere = TestData.term(dataSource, other, "category", "Away", "away", null);
        assertThat(Identities.onSite(site, Identities.user(author), () -> terms.delete(elsewhere, "category")))
                .isEmpty();
        assertThatThrownBy(() ->
                        Identities.onSite(site, Identities.user(author), () -> terms.delete(category, "category")))
                .isInstanceOf(ForbiddenException.class);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_term where id in (?, ?)",
                        category.value(),
                        elsewhere.value()))
                .isEqualTo(2);
    }

    @Test
    void aRefusedDeleteLeavesTheCallersTransactionUsable() throws Exception {
        UserId author = TestData.profile(dataSource, "author");
        TestData.member(dataSource, site, author, "[\"author\"]");
        UserId admin = TestData.profile(dataSource, "administrator");
        TestData.member(dataSource, site, admin, "[\"administrator\"]");
        TermId tag = TestData.term(dataSource, site, "post_tag", "Locked", "locked", null);
        TermId uncategorized = TestData.defaultCategory(dataSource, site);
        String byAuthor = Identities.onSite(site, Identities.user(author), () -> {
            assertThatThrownBy(() -> terms.delete(tag, "post_tag")).isInstanceOf(ForbiddenException.class);
            return terms.create(
                            "post_tag", TermChanges.builder().name("Allowed").build())
                    .slug();
        });
        String byAdmin = Identities.onSite(site, Identities.user(admin), () -> {
            assertThatThrownBy(() -> terms.delete(uncategorized, "category")).isInstanceOf(ForbiddenException.class);
            return terms.create("category", TermChanges.builder().name("Kept").build())
                    .slug();
        });
        assertThat(List.of(byAuthor, byAdmin)).containsExactly("allowed", "kept");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select slug from qp_term where site_id = ? and slug in ('locked', 'allowed', 'kept',"
                                + " 'uncategorized') order by slug",
                        site.value()))
                .containsExactly("allowed", "kept", "locked", "uncategorized");
    }

    @Test
    void childrenOfAFlatTaxonomyAreLeftWithoutParent() throws Exception {
        // parents in a flat taxonomy come only from data written past QuarkPress
        TermId grand = TestData.term(dataSource, site, "post_tag", "Grand", "grand", null);
        TermId middle = TestData.term(dataSource, site, "post_tag", "Middle", "middle", grand);
        TermId child = TestData.term(dataSource, site, "post_tag", "Child", "child", middle);
        TermId sibling = TestData.term(dataSource, site, "post_tag", "Sibling", "sibling", grand);
        assertThat(delete(middle, "post_tag")).isPresent();
        assertThat(parent(child)).isZero();
        assertThat(parent(sibling)).isEqualTo(grand.value());
        assertThat(Jdbc.count(dataSource, "select version from qp_term where id = ?", child.value()))
                .isZero();
    }

    @Test
    void childrenOfAnotherTaxonomyAreLeftWithoutParent() throws Exception {
        // a parent of another taxonomy comes only from data written past QuarkPress
        TermId top = TestData.term(dataSource, site, "category", "Top", "top", null);
        TermId middle = TestData.term(dataSource, site, "category", "Middle", "middle", top);
        TermId child = TestData.term(dataSource, site, "category", "Child", "child", middle);
        TermId genre = TestData.term(dataSource, site, "genre", "Stray", "stray", middle);
        TermId tag = TestData.term(dataSource, site, "post_tag", "Loose", "loose", middle);
        assertThat(delete(middle, "category")).isPresent();
        assertThat(parent(child)).isEqualTo(top.value());
        assertThat(parent(genre)).isZero();
        assertThat(parent(tag)).isZero();
    }

    @Test
    void reparentingTouchesOnlyTheDeletedTermsChildren() throws Exception {
        TermId top = TestData.term(dataSource, site, "category", "Top", "top", null);
        TermId middle = TestData.term(dataSource, site, "category", "Middle", "middle", top);
        TermId child = TestData.term(dataSource, site, "category", "Child", "child", middle);
        TermId unrelated = TestData.term(dataSource, site, "category", "Unrelated", "unrelated", null);
        TermId unrelatedChild =
                TestData.term(dataSource, site, "category", "Unrelated child", "unrelated-child", unrelated);
        SiteId other = TestData.site(dataSource);
        TermId otherParent = TestData.term(dataSource, other, "category", "Middle", "middle", null);
        TermId otherChild = TestData.term(dataSource, other, "category", "Child", "child", otherParent);

        // handed down: the deleted term's children get its parent
        assertThat(delete(middle, "category")).isPresent();
        assertThat(parent(child)).isEqualTo(top.value());
        assertThat(parent(top)).isZero();
        assertThat(parent(unrelated)).isZero();
        assertThat(parent(unrelatedChild)).isEqualTo(unrelated.value());
        assertThat(parent(otherChild)).isEqualTo(otherParent.value());

        // top level: the deleted term's children are left without parent
        assertThat(delete(top, "category")).isPresent();
        assertThat(parent(child)).isZero();
        assertThat(parent(unrelatedChild)).isEqualTo(unrelated.value());
        assertThat(parent(otherChild)).isEqualTo(otherParent.value());
        assertThat(Jdbc.count(dataSource, "select version from qp_term where id = ?", child.value()))
                .isZero();
        assertThat(recorder.seen()).containsExactly("deleted middle", "deleted top");
    }
}
