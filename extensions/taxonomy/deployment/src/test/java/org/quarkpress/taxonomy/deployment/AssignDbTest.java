package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.posts.PostException;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;
import org.quarkpress.taxonomy.TermsAssigned;

class AssignDbTest {

    @Taxonomy(key = "series", objectTypes = "post", sort = true)
    static class Series {}

    @Taxonomy(key = "shelf", objectTypes = "link")
    static class Shelf {}

    @Taxonomy(
            key = "gallery",
            objectTypes = {"post", "attachment"})
    static class Gallery {}

    @Taxonomy(
            key = "photo",
            objectTypes = {"post", "attachment:image", "attachment:video"})
    static class Photo {}

    @ApplicationScoped
    static class Recorder {
        final List<String> seen = new CopyOnWriteArrayList<>();

        List<String> seen() {
            return seen;
        }

        void assigned(@Observes TermsAssigned event) {
            seen.add(event.taxonomy() + " " + ids(event.before()) + ">" + ids(event.after()));
        }

        private static String ids(List<TermId> ids) {
            return ids.stream().map(id -> Long.toString(id.value())).toList().toString();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app =
            TaxonomyApp.with(AssignDbTest.class, Series.class, Shelf.class, Gallery.class, Photo.class, Recorder.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    @Inject
    Recorder recorder;

    private SiteId site;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        recorder.seen().clear();
    }

    private List<TermId> set(PostId post, String taxonomy, boolean append, TermRef... refs) throws Exception {
        return Identities.onSite(
                site, Identities.system(), () -> terms.setPostTerms(post, taxonomy, List.of(refs), append));
    }

    private List<String> slugsOf(PostId post, String taxonomy) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> terms.postTerms(post, taxonomy)).stream()
                .map(TermSnapshot::slug)
                .toList();
    }

    private long count(TermId term) {
        return Jdbc.count(dataSource, "select count from qp_term where id = ?", term.value());
    }

    @Test
    void replaceAndAppendChangeThePostsTermsAndCounts() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        TermId a = TestData.term(dataSource, site, "post_tag", "a", "a", null);
        TermId b = TestData.term(dataSource, site, "post_tag", "b", "b", null);
        TermId c = TestData.term(dataSource, site, "post_tag", "c", "c", null);
        assertThat(set(post, "post_tag", false, TermRef.id(a), TermRef.id(b))).containsExactly(a, b);
        assertThat(slugsOf(post, "post_tag")).containsExactly("a", "b");
        assertThat(set(post, "post_tag", true, TermRef.id(c))).containsExactly(c);
        assertThat(slugsOf(post, "post_tag")).containsExactly("a", "b", "c");
        assertThat(set(post, "post_tag", false, TermRef.id(b), TermRef.id(b))).containsExactly(b, b);
        assertThat(slugsOf(post, "post_tag")).containsExactly("b");
        assertThat(count(a)).isZero();
        assertThat(count(b)).isEqualTo(1);
        assertThat(count(c)).isZero();
        assertThat(recorder.seen())
                .containsExactly(
                        "post_tag []>[" + a.value() + ", " + b.value() + "]",
                        "post_tag [" + a.value() + ", " + b.value() + "]>[" + a.value() + ", " + b.value() + ", "
                                + c.value() + "]",
                        "post_tag [" + a.value() + ", " + b.value() + ", " + c.value() + "]>[" + b.value() + "]");
    }

    @Test
    void namesFindOrCreateTermsAndIdsAreNeverNames() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "draft", "", "D");
        TermId news = TestData.term(dataSource, site, "post_tag", "Headlines", "news", null);
        List<TermId> result = set(
                post,
                "post_tag",
                false,
                TermRef.name("News!"),
                TermRef.name("Fresh"),
                TermRef.name("  "),
                TermRef.name("Fresh"),
                TermRef.name("5"),
                TermRef.id(999_999_999L));
        assertThat(result).hasSize(4).startsWith(news);
        assertThat(result.get(1)).isEqualTo(result.get(2));
        assertThat(slugsOf(post, "post_tag")).containsExactly("5", "fresh", "news");
        assertThat(Jdbc.strings(
                        dataSource,
                        "select name from qp_term where id = ?",
                        result.get(3).value()))
                .containsExactly("5");
    }

    @Test
    void categoriesAreCreatedByNameToo() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        List<TermId> travel = set(post, "category", false, TermRef.name("Travel"));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(slug, '|', coalesce(parent_id, 0)) from qp_term where id = ?",
                        travel.get(0).value()))
                .containsExactly("travel|0");
    }

    @Test
    void anEmptyReplaceClearsOnlyThatTaxonomy() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        TermId tag = TestData.term(dataSource, site, "post_tag", "t", "t", null);
        TermId category = TestData.term(dataSource, site, "category", "c", "c", null);
        set(post, "post_tag", false, TermRef.id(tag));
        set(post, "category", false, TermRef.id(category));
        assertThat(set(post, "post_tag", false)).isEmpty();
        assertThat(slugsOf(post, "post_tag")).isEmpty();
        assertThat(slugsOf(post, "category")).containsExactly("c");
        assertThat(count(tag)).isZero();
        assertThat(count(category)).isEqualTo(1);
    }

    @Test
    void aSortedTaxonomyKeepsTheOrderOfAReplace() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        TermId a = TestData.term(dataSource, site, "series", "a", "a", null);
        TermId b = TestData.term(dataSource, site, "series", "b", "b", null);
        TermId c = TestData.term(dataSource, site, "series", "c", "c", null);
        set(post, "series", false, TermRef.id(c), TermRef.id(a), TermRef.id(b));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(term_id, ':', term_order) from qp_term_assignment"
                                + " where post_id = ? order by term_order",
                        post.value()))
                .containsExactly(c.value() + ":1", a.value() + ":2", b.value() + ":3");
        TermId d = TestData.term(dataSource, site, "series", "d", "d", null);
        set(post, "series", true, TermRef.id(d));
        assertThat(Jdbc.count(
                        dataSource,
                        "select term_order from qp_term_assignment where post_id = ? and term_id = ?",
                        post.value(),
                        d.value()))
                .isZero();
        PostId tagged = TestData.post(dataSource, site, "post", "publish", "q", "Q");
        TermId tag = TestData.term(dataSource, site, "post_tag", "x", "x", null);
        set(tagged, "post_tag", false, TermRef.id(tag));
        assertThat(Jdbc.count(
                        dataSource, "select term_order from qp_term_assignment where post_id = ?", tagged.value()))
                .isZero();
    }

    @Test
    void countsFollowWordPresssPostCounter() throws Exception {
        TermId tag = TestData.term(dataSource, site, "post_tag", "t", "t", null);
        for (String status : List.of("publish", "draft", "private", "future", "trash", "pending")) {
            set(TestData.post(dataSource, site, "post", status, "", status), "post_tag", false, TermRef.id(tag));
        }
        set(TestData.post(dataSource, site, "page", "publish", "pg", "Page"), "post_tag", false, TermRef.id(tag));
        assertThat(count(tag)).isEqualTo(1);
        TermId shelf = TestData.term(dataSource, site, "shelf", "s", "s", null);
        set(TestData.post(dataSource, site, "post", "draft", "", "x"), "shelf", false, TermRef.id(shelf));
        assertThat(count(shelf)).isEqualTo(1);
    }

    @Test
    void attachmentsThatInheritCountThroughTheirParent() throws Exception {
        TermId term = TestData.term(dataSource, site, "gallery", "g", "g", null);
        PostId published = TestData.post(dataSource, site, "post", "publish", "pub", "Published");
        PostId draft = TestData.post(dataSource, site, "post", "draft", "", "Draft");
        List<PostId> objects = List.of(
                published,
                draft,
                attachment(published, "inherit"),
                attachment(draft, "inherit"),
                attachment(null, "inherit"),
                attachment(published, "private"),
                attachment(null, "publish"));
        for (PostId object : objects) {
            set(object, "gallery", false, TermRef.id(term));
        }
        assertThat(count(term)).isEqualTo(3);
    }

    @Test
    void anAttachmentMimeTypeCountsAsAttachment() throws Exception {
        TermId term = TestData.term(dataSource, site, "photo", "p", "p", null);
        PostId published = TestData.post(dataSource, site, "post", "publish", "pub", "Published");
        PostId draft = TestData.post(dataSource, site, "post", "draft", "", "Draft");
        List<PostId> objects = List.of(
                published,
                draft,
                attachment(published, "inherit"),
                attachment(draft, "inherit"),
                attachment(null, "inherit"),
                attachment(published, "private"),
                attachment(null, "publish"));
        for (PostId object : objects) {
            set(object, "photo", false, TermRef.id(term));
        }
        assertThat(count(term)).isEqualTo(3);
    }

    @Test
    void aTermGivenTwiceInASortedReplaceKeepsItsLastPosition() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        TermId a = TestData.term(dataSource, site, "series", "a", "a", null);
        TermId b = TestData.term(dataSource, site, "series", "b", "b", null);
        TermId c = TestData.term(dataSource, site, "series", "c", "c", null);
        set(post, "series", false, TermRef.id(a), TermRef.id(b), TermRef.id(c));
        set(post, "series", false, TermRef.id(c), TermRef.id(a), TermRef.id(c), TermRef.id(b));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(term_id, ':', term_order) from qp_term_assignment"
                                + " where post_id = ? order by term_order",
                        post.value()))
                .containsExactly(a.value() + ":2", c.value() + ":3", b.value() + ":4");
    }

    @Test
    void aPostsTermsOfOneNameIgnoringCaseComeInIdOrder() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        // the lower id holds the name that MySQL's binary collation puts second
        TermId first = TestData.term(dataSource, site, "post_tag", "qQ", "x1", null);
        TermId second = TestData.term(dataSource, site, "post_tag", "Qq", "x2", null);
        set(post, "post_tag", false, TermRef.id(second), TermRef.id(first));
        assertThat(Identities.onSite(site, Identities.system(), () -> terms.postTerms(post, "post_tag")).stream()
                        .map(TermSnapshot::id))
                .containsExactly(first, second);
    }

    private PostId attachment(PostId parent, String status) {
        PostId attachment = TestData.post(dataSource, site, "attachment", status, "", "File");
        if (parent != null) {
            Jdbc.update(
                    dataSource, "update qp_post set parent_id = ? where id = ?", parent.value(), attachment.value());
        }
        return attachment;
    }

    @Test
    void removingRemovesNamedTermsAndIgnoresUnknownNames() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        TermId a = TestData.term(dataSource, site, "post_tag", "a", "a", null);
        TermId b = TestData.term(dataSource, site, "post_tag", "b", "b", null);
        set(post, "post_tag", false, TermRef.id(a), TermRef.id(b));
        recorder.seen().clear();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> terms.removePostTerms(
                                post, "post_tag", List.of(TermRef.name("a"), TermRef.id(999_999_999L)))))
                .isTrue();
        assertThat(slugsOf(post, "post_tag")).containsExactly("b");
        assertThat(count(a)).isZero();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> terms.removePostTerms(post, "post_tag", List.of(TermRef.name("zzz")))))
                .isFalse();
        assertThat(recorder.seen())
                .containsExactly("post_tag [" + a.value() + ", " + b.value() + "]>[" + b.value() + "]");
        assertThat(Jdbc.count(
                        dataSource, "select count(*) from qp_term where site_id = ? and slug = 'zzz'", site.value()))
                .isZero();
    }

    @Test
    void anotherSitesTermIsSkipped() throws Exception {
        SiteId other = TestData.site(dataSource);
        TermId theirs = TestData.term(dataSource, other, "post_tag", "theirs", "theirs", null);
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        assertThat(set(post, "post_tag", false, TermRef.id(theirs))).isEmpty();
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_term_assignment where post_id = ?", post.value()))
                .isZero();
        assertThat(count(theirs)).isZero();
    }

    @Test
    void aMissingPostOrTaxonomyIsRefused() {
        assertThatThrownBy(() -> set(PostId.of(999_999_999L), "post_tag", false, TermRef.name("x")))
                .isInstanceOfSatisfying(
                        PostException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_post"));
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        assertThatThrownBy(() -> set(post, "bogus", false, TermRef.name("x")))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_taxonomy"));
    }

    @Test
    void aNameThatSanitizesAwayRefusesTheWholeCallBeforeItWrites() {
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        assertThatThrownBy(() -> set(post, "post_tag", false, TermRef.name("Kept"), TermRef.name("<b></b>")))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_term_name"));
        assertThat(Jdbc.count(
                        dataSource, "select count(*) from qp_term where site_id = ? and slug = 'kept'", site.value()))
                .isZero();
    }

    @Test
    void postTermsComeInNameOrder() throws Exception {
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        set(post, "post_tag", false, TermRef.name("zeta"), TermRef.name("Alpha"), TermRef.name("beta"));
        assertThat(slugsOf(post, "post_tag")).containsExactly("alpha", "beta", "zeta");
    }
}
