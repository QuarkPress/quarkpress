package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.Callable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermField;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

class ReadsDbTest {

    @Taxonomy(key = "secret", objectTypes = "post", publicly = false, hierarchical = true)
    static class Secret {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.multisite(ReadsDbTest.class, Secret.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Terms terms;

    private SiteId site;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
    }

    private <T> T anonymously(Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.anonymous(), work);
    }

    @Test
    void termsAreFoundByIdWithOrWithoutTaxonomy() throws Exception {
        TermId tag = TestData.term(dataSource, site, "post_tag", "News", "news", null);
        assertThat(anonymously(() -> terms.find(tag)).map(TermSnapshot::slug)).contains("news");
        assertThat(anonymously(() -> terms.find(tag, "post_tag"))).isPresent();
        assertThat(anonymously(() -> terms.find(tag, "category"))).isEmpty();
        assertThatThrownBy(() -> anonymously(() -> terms.find(tag, "bogus")))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_taxonomy"));
        TermId menu = TestData.term(dataSource, site, "nav_menu", "Main", "main", null);
        assertThat(anonymously(() -> terms.find(menu))).isEmpty();
    }

    @Test
    void anotherSitesTermIsNotFound() throws Exception {
        SiteId other = TestData.site(dataSource);
        TermId theirs = TestData.term(dataSource, other, "post_tag", "Theirs", "theirs", null);
        assertThat(anonymously(() -> terms.find(theirs))).isEmpty();
        assertThat(anonymously(() -> terms.findBy("post_tag", TermField.ID, Long.toString(theirs.value()))))
                .isEmpty();
        assertThat(anonymously(() -> terms.findBy("post_tag", TermField.SLUG, "theirs")))
                .isEmpty();
    }

    @Test
    void fieldsAreReadAsWordPressReadsThem() throws Exception {
        TermId rock = TestData.term(dataSource, site, "post_tag", "Rock &amp; Roll", "rock-roll", null);
        TermId news = TestData.term(dataSource, site, "post_tag", "Headlines", "news", null);
        assertThat(anonymously(() -> terms.findBy("post_tag", TermField.SLUG, "News"))
                        .map(TermSnapshot::id))
                .contains(news);
        assertThat(anonymously(() -> terms.findBy("post_tag", TermField.NAME, "rock & roll"))
                        .map(TermSnapshot::id))
                .contains(rock);
        assertThat(anonymously(() -> terms.findBy("post_tag", TermField.ID, Long.toString(rock.value())))
                        .map(TermSnapshot::id))
                .contains(rock);
        assertThat(anonymously(() -> terms.findBy("post_tag", TermField.ID, "x1")))
                .isEmpty();
        assertThat(anonymously(() -> terms.findBy("post_tag", TermField.NAME, "")))
                .isEmpty();
        assertThat(anonymously(() -> terms.findBy("bogus", TermField.SLUG, "news")))
                .isEmpty();
    }

    @Test
    void zeroIsANameOrSlugAndIdsAreReadAsPhpCastsThem() throws Exception {
        TermId zero = TestData.term(dataSource, site, "post_tag", "0", "0", null);
        assertThat(anonymously(() -> terms.findBy("post_tag", TermField.NAME, "0"))
                        .map(TermSnapshot::id))
                .contains(zero);
        assertThat(anonymously(() -> terms.findBy("post_tag", TermField.SLUG, "0"))
                        .map(TermSnapshot::id))
                .contains(zero);
        String id = Long.toString(zero.value());
        for (String text : new String[] {" " + id, id + "abc", "+" + id, id + ".9", "\n" + id + " "}) {
            assertThat(anonymously(() -> terms.findBy("post_tag", TermField.ID, text))
                            .map(TermSnapshot::id))
                    .as(text)
                    .contains(zero);
        }
        for (String text : new String[] {"0", "", "-" + id, "abc" + id, "0x" + id}) {
            assertThat(anonymously(() -> terms.findBy("post_tag", TermField.ID, text)))
                    .as(text)
                    .isEmpty();
        }
    }

    @Test
    void existsLooksForASlugThenANameUnderAnOptionalParent() throws Exception {
        TermId news = TestData.term(dataSource, site, "post_tag", "Headlines", "news", null);
        TermId five = TestData.term(dataSource, site, "category", "5", "five", null);
        assertThat(anonymously(() -> terms.exists("post_tag", "News!", OptionalLong.empty()))
                        .map(TermSnapshot::id))
                .contains(news);
        assertThat(anonymously(() -> terms.exists("category", "5", OptionalLong.empty()))
                        .map(TermSnapshot::id))
                .contains(five);
        assertThat(anonymously(() -> terms.exists("post_tag", Long.toString(news.value()), OptionalLong.empty())))
                .isEmpty();
        TermId world = TestData.term(dataSource, site, "category", "World", "world", null);
        TermId child = TestData.term(dataSource, site, "category", "Child", "child", world);
        assertThat(anonymously(() -> terms.exists("category", "Child", OptionalLong.of(0))))
                .isEmpty();
        assertThat(anonymously(() -> terms.exists("category", "Child", OptionalLong.of(world.value())))
                        .map(TermSnapshot::id))
                .contains(child);
        assertThat(anonymously(() -> terms.exists("bogus", "Child", OptionalLong.empty())))
                .isEmpty();
        // a slug is looked up before a name
        TermId byName = TestData.term(dataSource, site, "category", "Alpha", "name-holder", null);
        TermId bySlug = TestData.term(dataSource, site, "category", "Other", "alpha", null);
        assertThat(anonymously(() -> terms.exists("category", "alpha", OptionalLong.empty()))
                        .map(TermSnapshot::id))
                .contains(bySlug);
        assertThat(byName).isNotEqualTo(bySlug);
        // the lowest id wins among equal names under different parents
        TermId first = TestData.term(dataSource, site, "category", "Twin", "twin-a", world);
        TestData.term(dataSource, site, "category", "Twin", "twin-b", child);
        assertThat(anonymously(() -> terms.exists("category", "Twin", OptionalLong.empty()))
                        .map(TermSnapshot::id))
                .contains(first);
        assertThat(anonymously(() -> terms.exists("post_tag", "  ", OptionalLong.empty())))
                .isEmpty();
    }

    @Test
    void ancestorsAndChildrenWalkTheHierarchy() throws Exception {
        TermId a = TestData.term(dataSource, site, "category", "A", "a", null);
        TermId b = TestData.term(dataSource, site, "category", "B", "b", a);
        TermId c = TestData.term(dataSource, site, "category", "C", "c", a);
        TermId d = TestData.term(dataSource, site, "category", "D", "d", b);
        assertThat(anonymously(() -> terms.ancestors(d, "category"))).containsExactly(b, a);
        assertThat(anonymously(() -> terms.children(a, "category"))).containsExactly(b, c, d);
        assertThat(anonymously(() -> terms.children(d, "category"))).isEmpty();
        TermId tag = TestData.term(dataSource, site, "post_tag", "T", "t", null);
        TermId stray = TestData.term(dataSource, site, "post_tag", "Stray", "stray", tag);
        assertThat(anonymously(() -> terms.ancestors(tag, "post_tag"))).isEmpty();
        assertThat(anonymously(() -> terms.ancestors(stray, "post_tag"))).isEmpty();
        assertThat(anonymously(() -> terms.children(tag, "post_tag"))).isEmpty();
        assertThat(anonymously(() -> terms.children(stray, "post_tag"))).isEmpty();
        assertThatThrownBy(() -> anonymously(() -> terms.ancestors(a, "bogus")))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_taxonomy"));
        assertThatThrownBy(() -> anonymously(() -> terms.children(a, "bogus")))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_taxonomy"));
    }

    @Test
    void aTaxonomyThatIsNotPubliclyQueryableNeedsAssignTerms() throws Exception {
        TermId hidden = TestData.term(dataSource, site, "secret", "Hidden", "hidden", null);
        assertThatThrownBy(() -> anonymously(() -> terms.find(hidden, "secret")))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> anonymously(() -> terms.find(hidden))).isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> anonymously(() -> terms.findBy("secret", TermField.SLUG, "hidden")))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> anonymously(() -> terms.exists("secret", "Hidden", OptionalLong.empty())))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> anonymously(() -> terms.ancestors(hidden, "secret")))
                .isInstanceOf(UnauthorizedException.class);
        assertThatThrownBy(() -> anonymously(() -> terms.children(hidden, "secret")))
                .isInstanceOf(UnauthorizedException.class);
        UserId contributor = TestData.profile(dataSource, "contributor");
        TestData.member(dataSource, site, contributor, "[\"contributor\"]");
        Optional<TermSnapshot> found =
                Identities.onSite(site, Identities.user(contributor), () -> terms.find(hidden, "secret"));
        assertThat(found).isPresent();
    }
}
