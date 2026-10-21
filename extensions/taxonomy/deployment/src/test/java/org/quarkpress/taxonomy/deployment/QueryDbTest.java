package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.taxonomy.Taxonomy;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermOrder;
import org.quarkpress.taxonomy.TermQuery;
import org.quarkpress.taxonomy.TermSnapshot;
import org.quarkpress.taxonomy.Terms;

class QueryDbTest {

    @Taxonomy(key = "secret", objectTypes = "post", publicly = false)
    static class Secret {}

    @Taxonomy(key = "region", objectTypes = "post", hierarchical = true)
    static class Region {}

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.with(QueryDbTest.class, Secret.class, Region.class);

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

    private TermId term(String taxonomy, String name, TermId parent, int count) {
        String slug = name.toLowerCase(java.util.Locale.ROOT).replace(' ', '-');
        TermId id = TestData.term(dataSource, site, taxonomy, name, slug, parent);
        Jdbc.update(dataSource, "update qp_term set count = ? where id = ?", count, id.value());
        return id;
    }

    private List<String> names(TermQuery.Builder query) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> terms.query(query.build())).stream()
                .map(TermSnapshot::name)
                .toList();
    }

    private long count(TermQuery.Builder query) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> terms.count(query.build()));
    }

    @Test
    void emptyTermsAreHiddenUnlessADescendantHasACount() throws Exception {
        TermId a = term("category", "A", null, 0);
        term("category", "B", a, 2);
        term("category", "C", null, 0);
        term("post_tag", "Empty", null, 0);
        assertThat(names(TermQuery.builder().taxonomies("category"))).containsExactly("A", "B");
        assertThat(names(TermQuery.builder().taxonomies("category").hierarchical(false)))
                .containsExactly("B");
        assertThat(names(TermQuery.builder().taxonomies("category").hideEmpty(false)))
                .containsExactly("A", "B", "C", "Uncategorized");
        assertThat(names(TermQuery.builder().taxonomies("post_tag"))).isEmpty();
        assertThat(names(TermQuery.builder().taxonomies("category").all(true)))
                .containsExactly("A", "B", "C", "Uncategorized");
    }

    @Test
    void ordersAndDirectionsFollowWordPress() throws Exception {
        TermId beta = term("post_tag", "beta", null, 3);
        TermId alpha = term("post_tag", "Alpha", null, 1);
        TermId zeta = term("post_tag", "Zeta", null, 2);
        TermQuery.Builder tags = TermQuery.builder().taxonomies("post_tag");
        assertThat(names(tags)).containsExactly("Alpha", "beta", "Zeta");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").order("")))
                .containsExactly("Zeta", "beta", "Alpha");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").order("up")))
                .containsExactly("Zeta", "beta", "Alpha");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").order("asc")))
                .containsExactly("Alpha", "beta", "Zeta");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").order("aSc")))
                .containsExactly("Alpha", "beta", "Zeta");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").orderBy(TermOrder.COUNT)))
                .containsExactly("Alpha", "Zeta", "beta");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").orderBy(TermOrder.ID)))
                .containsExactly("beta", "Alpha", "Zeta");
        assertThat(names(TermQuery.builder()
                        .taxonomies("post_tag")
                        .orderBy(TermOrder.INCLUDE)
                        .include(zeta, beta, alpha)))
                .containsExactly("Zeta", "beta", "Alpha");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").orderBy(TermOrder.INCLUDE)))
                .containsExactly("Alpha", "beta", "Zeta");
        assertThat(names(TermQuery.builder()
                        .taxonomies("post_tag")
                        .orderBy(TermOrder.SLUG_IN)
                        .slugs("zeta", "alpha")))
                .containsExactly("Zeta", "Alpha");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").orderBy(TermOrder.TERM_ORDER)))
                .containsExactly("beta", "Alpha", "Zeta");
        assertThat(names(TermQuery.builder()
                        .taxonomies("post_tag")
                        .orderBy(TermOrder.NONE)
                        .order("DESC")))
                .containsExactly("beta", "Alpha", "Zeta");
    }

    @Test
    void tiesAreBrokenByIdInTheQuerysDirection() throws Exception {
        TermId first = TestData.term(dataSource, site, "post_tag", "qQ", "qq-1", null);
        TermId second = TestData.term(dataSource, site, "post_tag", "Qq", "qq-2", null);
        Jdbc.update(dataSource, "update qp_term set count = 1 where id in (?, ?)", first.value(), second.value());
        assertThat(names(TermQuery.builder().taxonomies("post_tag").order("DESC")))
                .containsExactly("Qq", "qQ");
        assertThat(names(TermQuery.builder().taxonomies("post_tag"))).containsExactly("qQ", "Qq");
        assertThat(names(TermQuery.builder()
                        .taxonomies("post_tag")
                        .orderBy(TermOrder.COUNT)
                        .order("DESC")))
                .containsExactly("Qq", "qQ");
    }

    @Test
    void numberAndOffsetFollowTheHierarchyAndTheParentQuirk() throws Exception {
        for (int i = 10; i < 25; i++) {
            term("post_tag", "Tag " + i, null, 1);
        }
        assertThat(names(TermQuery.builder().taxonomies("post_tag").number(10))).hasSize(10);
        assertThat(names(TermQuery.builder().taxonomies("post_tag").number(10).parent(0)))
                .hasSize(15);
        assertThat(names(TermQuery.builder().taxonomies("post_tag").number(-3))).hasSize(3);
        term("category", "Cat A", null, 1);
        term("category", "Cat B", null, 0);
        term("category", "Cat C", null, 1);
        term("category", "Cat D", null, 0);
        term("category", "Cat E", null, 1);
        assertThat(names(TermQuery.builder().taxonomies("category").number(2).offset(1)))
                .containsExactly("Cat C", "Cat E");
        assertThat(names(TermQuery.builder().taxonomies("category").number(2).offset(9)))
                .isEmpty();
        assertThat(names(TermQuery.builder()
                        .taxonomies("category")
                        .number(Integer.MIN_VALUE)
                        .offset(Integer.MIN_VALUE)))
                .isEmpty();
        assertThat(names(TermQuery.builder()
                        .taxonomies("post_tag")
                        .number(Integer.MIN_VALUE)
                        .offset(Integer.MIN_VALUE)))
                .isEmpty();
    }

    @Test
    void negativeParentsAndChildOfMatchNothing() throws Exception {
        TermId world = term("category", "World", null, 1);
        term("category", "Europe", world, 1);
        TermQuery.Builder shown = TermQuery.builder().taxonomies("category").hideEmpty(false);
        assertThat(names(TermQuery.builder()
                        .taxonomies("category")
                        .hideEmpty(false)
                        .parent(-1)))
                .isEmpty();
        assertThat(names(TermQuery.builder()
                        .taxonomies("category")
                        .hideEmpty(false)
                        .childOf(-1)))
                .isEmpty();
        assertThat(count(TermQuery.builder().taxonomies("category").parent(-1))).isZero();
        assertThat(count(TermQuery.builder().taxonomies("category").childOf(-1)))
                .isZero();
        assertThat(names(shown.childOf(-1).all(true))).containsExactly("Europe", "Uncategorized", "World");
    }

    @Test
    void childOfOverSeveralHierarchiesFollowsEachTaxonomysHierarchy() throws Exception {
        TermId world = term("category", "World", null, 1);
        TermId europe = term("category", "Europe", world, 1);
        term("category", "Paris", europe, 1);
        TermId north = term("region", "North", null, 1);
        term("region", "South", north, 1);
        assertThat(names(TermQuery.builder().taxonomies("category").childOf(world.value())))
                .containsExactly("Europe", "Paris");
        assertThat(names(TermQuery.builder().taxonomies("category", "region").childOf(world.value())))
                .isEmpty();
        assertThat(names(TermQuery.builder().taxonomies("region", "category").childOf(north.value())))
                .isEmpty();
    }

    @Test
    void filtersSelectAsWordPressSelects() throws Exception {
        TermId world = term("category", "World", null, 1);
        TermId europe = term("category", "Europe", world, 1);
        TermId paris = term("category", "Paris", europe, 1);
        TermId asia = term("category", "Asia", world, 1);
        TermId sport = term("category", "Sport 100%", null, 1);
        TermQuery.Builder all = TermQuery.builder().taxonomies("category").hideEmpty(false);
        assertThat(names(TermQuery.builder()
                        .taxonomies("category")
                        .include(world, asia)
                        .exclude(world)))
                .containsExactly("Asia", "World");
        assertThat(names(TermQuery.builder().taxonomies("category").exclude(world, asia)))
                .containsExactly("Europe", "Paris", "Sport 100%");
        assertThat(names(TermQuery.builder().taxonomies("category").excludeTree(europe)))
                .containsExactly("Asia", "Sport 100%", "World");
        assertThat(names(TermQuery.builder().taxonomies("category").slugs("World!", "asia")))
                .containsExactly("Asia", "World");
        assertThat(names(TermQuery.builder().taxonomies("category").names("world", "PARIS")))
                .containsExactly("Paris", "World");
        assertThat(names(TermQuery.builder().taxonomies("category").search("%")))
                .containsExactly("Sport 100%");
        assertThat(names(TermQuery.builder().taxonomies("category").search("ROP")))
                .containsExactly("Europe");
        assertThat(names(TermQuery.builder().taxonomies("category").nameLike("si")))
                .containsExactly("Asia");
        assertThat(names(TermQuery.builder().taxonomies("category").parent(0))).containsExactly("Sport 100%", "World");
        assertThat(names(TermQuery.builder().taxonomies("category").parent(world.value())))
                .containsExactly("Asia", "Europe");
        assertThat(names(TermQuery.builder().taxonomies("category").parent(paris.value())))
                .isEmpty();
        assertThat(names(TermQuery.builder().taxonomies("category").childOf(world.value())))
                .containsExactly("Asia", "Europe", "Paris");
        assertThat(names(TermQuery.builder().taxonomies("category").childless(true)))
                .containsExactly("Asia", "Paris", "Sport 100%");
        assertThat(names(all.parent(sport.value()))).isEmpty();
        TermId tag = term("post_tag", "Tagged", null, 0);
        PostId post = TestData.post(dataSource, site, "post", "draft", "", "P");
        TestData.assign(dataSource, site, post, tag);
        assertThat(names(TermQuery.builder().posts(post))).containsExactly("Tagged");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").parent(tag.value())))
                .isEmpty();
        assertThat(names(TermQuery.builder()
                        .taxonomies("post_tag")
                        .hideEmpty(false)
                        .childOf(tag.value())))
                .isEmpty();
        assertThat(count(TermQuery.builder().taxonomies("post_tag").childOf(tag.value())))
                .isZero();
    }

    @Test
    void padCountsAddDescendantsPosts() throws Exception {
        TermId a = term("category", "A", null, 0);
        TermId b = term("category", "B", a, 1);
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        TestData.assign(dataSource, site, post, b);
        List<TermSnapshot> padded = Identities.onSite(
                site,
                Identities.system(),
                () -> terms.query(TermQuery.builder()
                        .taxonomies("category")
                        .padCounts(true)
                        .build()));
        assertThat(padded).extracting(TermSnapshot::name).containsExactly("A", "B");
        assertThat(padded).extracting(TermSnapshot::count).containsExactly(1, 1);
    }

    @Test
    void countsIgnoreTheLimitsAndShowEmptyTermsByDefault() throws Exception {
        term("post_tag", "One", null, 1);
        term("post_tag", "Two", null, 0);
        assertThat(count(TermQuery.builder().taxonomies("post_tag"))).isEqualTo(2);
        assertThat(count(TermQuery.builder().taxonomies("post_tag").hideEmpty(true)))
                .isEqualTo(1);
        assertThat(count(TermQuery.builder()
                        .taxonomies("post_tag")
                        .hideEmpty(false)
                        .number(1)
                        .offset(1)))
                .isEqualTo(2);
    }

    @Test
    void countsWithPostsCountAssignments() throws Exception {
        TermId shared = term("post_tag", "Shared", null, 2);
        TermId single = term("post_tag", "Single", null, 1);
        term("post_tag", "Unused", null, 1);
        PostId first = TestData.post(dataSource, site, "post", "publish", "first", "First");
        PostId second = TestData.post(dataSource, site, "post", "publish", "second", "Second");
        TestData.assign(dataSource, site, first, shared);
        TestData.assign(dataSource, site, second, shared);
        TestData.assign(dataSource, site, first, single);
        assertThat(count(TermQuery.builder().taxonomies("post_tag").posts(first, second)))
                .isEqualTo(3);
        assertThat(names(TermQuery.builder().taxonomies("post_tag").posts(first, second)))
                .containsExactly("Shared", "Single");
    }

    @Test
    void termOrderFollowsThePostsAssignments() throws Exception {
        TermId alpha = term("post_tag", "Alpha", null, 1);
        TermId zeta = term("post_tag", "Zeta", null, 1);
        term("post_tag", "Middle", null, 1);
        PostId post = TestData.post(dataSource, site, "post", "publish", "p", "P");
        Jdbc.update(
                dataSource,
                "insert into qp_term_assignment (site_id, post_id, term_id, term_order) values (?, ?, ?, ?)",
                site.value(),
                post.value(),
                alpha.value(),
                2);
        Jdbc.update(
                dataSource,
                "insert into qp_term_assignment (site_id, post_id, term_id, term_order) values (?, ?, ?, ?)",
                site.value(),
                post.value(),
                zeta.value(),
                1);
        assertThat(names(TermQuery.builder().taxonomies("post_tag").posts(post).orderBy(TermOrder.TERM_ORDER)))
                .containsExactly("Zeta", "Alpha");
        assertThat(names(TermQuery.builder()
                        .taxonomies("post_tag")
                        .posts(post)
                        .orderBy(TermOrder.TERM_ORDER)
                        .order("DESC")))
                .containsExactly("Alpha", "Zeta");
    }

    @Test
    void otherOrdersAndTheDescriptionFilterFollowWordPress() throws Exception {
        TermId bravo = TestData.term(dataSource, site, "post_tag", "Bravo", "c-slug", null);
        TermId alpha = TestData.term(dataSource, site, "post_tag", "Alpha", "b-slug", null);
        TermId charlie = TestData.term(dataSource, site, "post_tag", "Charlie", "a-slug", null);
        Jdbc.update(dataSource, "update qp_term set count = 1, description = 'y' where id = ?", bravo.value());
        Jdbc.update(dataSource, "update qp_term set count = 1, description = 'Z' where id = ?", alpha.value());
        Jdbc.update(dataSource, "update qp_term set count = 1, description = 'x' where id = ?", charlie.value());
        TermQuery.Builder tags = TermQuery.builder().taxonomies("post_tag");
        assertThat(names(tags.orderBy(TermOrder.SLUG))).containsExactly("Charlie", "Alpha", "Bravo");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").orderBy(TermOrder.DESCRIPTION)))
                .containsExactly("Charlie", "Bravo", "Alpha");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").orderBy(TermOrder.TERM_GROUP)))
                .containsExactly("Bravo", "Alpha", "Charlie");
        assertThat(names(TermQuery.builder().taxonomies("post_tag").descriptionLike("z")))
                .containsExactly("Alpha");
        assertThat(names(TermQuery.builder()
                        .taxonomies("post_tag", "category")
                        .hideEmpty(false)
                        .orderBy(TermOrder.TAXONOMY)))
                .containsExactly("Uncategorized", "Bravo", "Alpha", "Charlie");
        assertThat(names(TermQuery.builder()
                        .taxonomies("post_tag", "category")
                        .hideEmpty(false)
                        .orderBy(TermOrder.TAXONOMY)
                        .order("DESC")))
                .containsExactly("Charlie", "Alpha", "Bravo", "Uncategorized");
        TermId kid = term("category", "Kid", null, 1);
        TermId root = term("category", "Root", null, 1);
        Jdbc.update(dataSource, "update qp_term set parent_id = ? where id = ?", root.value(), kid.value());
        assertThat(names(TermQuery.builder()
                        .taxonomies("category")
                        .hideEmpty(false)
                        .orderBy(TermOrder.PARENT)))
                .containsExactly("Uncategorized", "Root", "Kid");
        assertThat(names(TermQuery.builder()
                        .taxonomies("category")
                        .hideEmpty(false)
                        .orderBy(TermOrder.PARENT)
                        .order("DESC")))
                .containsExactly("Kid", "Root", "Uncategorized");
    }

    @Test
    void taxonomiesMustBeKnownAndReadable() throws Exception {
        term("secret", "Hidden", null, 1);
        assertThatThrownBy(() -> names(TermQuery.builder().taxonomies("bogus")))
                .isInstanceOfSatisfying(
                        TermException.class,
                        refused -> assertThat(refused.code()).isEqualTo("invalid_taxonomy"));
        assertThatThrownBy(() -> Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> terms.query(
                                TermQuery.builder().taxonomies("secret").build())))
                .isInstanceOf(UnauthorizedException.class);
        List<String> visible = Identities.onSite(
                        site,
                        Identities.anonymous(),
                        () -> terms.query(TermQuery.builder().hideEmpty(false).build()))
                .stream()
                .map(TermSnapshot::name)
                .toList();
        assertThat(visible).contains("Uncategorized").doesNotContain("Hidden");
    }
}
