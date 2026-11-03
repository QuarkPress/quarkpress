package org.quarkpress.taxonomy.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.TaxClause;
import org.quarkpress.posts.TaxClause.Operator;
import org.quarkpress.posts.TaxClause.Relation;
import org.quarkpress.posts.TaxQuery;
import org.quarkpress.posts.TermCondition;
import org.quarkpress.posts.TermConditionProvider;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.runtime.JpaTermConditions;
import org.quarkpress.taxonomy.runtime.TermRepository;

/**
 * The taxonomy module's answers to the posts module's term filters: WordPress's tax query semantics over this site's
 * terms. Categories c3 (with children c7 and c8) and tags t4 and t5; posts named by what they are in.
 */
@SiteScopedNativeSql
class TermConditionsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = TaxonomyApp.with(TermConditionsDbTest.class)
            .overrideConfigKey("quarkus.hibernate-orm.\"quarkpress\".statistics", "true");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TermConditionProvider conditions;

    @Inject
    TermRepository termRows;

    @Inject
    JpaTermConditions provider;

    private SiteId site;
    private TermId c3;
    private TermId c7;
    private TermId c8;
    private TermId t4;
    private TermId t5;
    private PostId inC3;
    private PostId inC7;
    private PostId inC8;
    private PostId inT5;
    private PostId inT4AndT5;
    private PostId plain;
    private List<PostId> all;

    @BeforeEach
    void newSite() {
        site = TestData.site(dataSource);
        c3 = TestData.term(dataSource, site, "category", "C3", "c3", null);
        c7 = TestData.term(dataSource, site, "category", "C7", "c7", c3);
        c8 = TestData.term(dataSource, site, "category", "C8", "c8", c3);
        t4 = TestData.term(dataSource, site, "post_tag", "T4", "t4", null);
        t5 = TestData.term(dataSource, site, "post_tag", "T5", "t5", null);
        inC3 = post("in-c3");
        inC7 = post("in-c7");
        inC8 = post("in-c8");
        inT5 = post("in-t5");
        inT4AndT5 = post("in-t4-and-t5");
        plain = post("plain");
        TestData.assign(dataSource, site, inC3, c3);
        TestData.assign(dataSource, site, inC7, c7);
        TestData.assign(dataSource, site, inC8, c8);
        TestData.assign(dataSource, site, inT5, t5);
        TestData.assign(dataSource, site, inT4AndT5, t4);
        TestData.assign(dataSource, site, inT4AndT5, t5);
        all = List.of(inC3, inC7, inC8, inT5, inT4AndT5, plain);
    }

    private PostId post(String slug) {
        return TestData.post(dataSource, site, "post", "publish", slug, slug);
    }

    private static List<Long> values(Collection<TermId> terms) {
        return terms.stream().map(TermId::value).toList();
    }

    private static TaxClause categories(List<Long> ids, boolean includeChildren, Operator operator) {
        return new TaxClause.Terms("category", ids, includeChildren, operator);
    }

    private static TaxClause excludeCategories(List<Long> ids) {
        return categories(ids, false, Operator.NOT_IN);
    }

    private static TaxClause tags(List<Long> ids, Operator operator) {
        return new TaxClause.Terms("post_tag", ids, false, operator);
    }

    private static TaxQuery query(Relation relation, TaxClause... clauses) {
        return new TaxQuery(relation, List.of(clauses));
    }

    private List<PostId> ids(TaxClause clause) throws Exception {
        return ids(query(Relation.AND, clause));
    }

    private List<PostId> ids(TaxQuery query) throws Exception {
        PostPage page = Identities.onSite(
                site,
                Identities.anonymous(),
                () -> posts.query(
                        PostQuery.builder().perPage(-1).taxQuery(query).build()));
        return page.posts().stream().map(PostSnapshot::id).toList();
    }

    private TermCondition condition(TaxClause clause) throws Exception {
        return Identities.onSite(site, Identities.anonymous(), () -> conditions.condition(site, clause, "p.id", "t0_"));
    }

    @Test
    void wordPressExamples() throws Exception {
        assertThat(ids(categories(List.of(999_999L), false, Operator.IN))).isEmpty();
        assertThat(ids(categories(List.of(c3.value(), 999_999L), false, Operator.IN)))
                .containsExactly(inC3);
        assertThat(ids(excludeCategories(List.of(999_999L)))).containsExactlyInAnyOrderElementsOf(all);
        assertThat(ids(categories(List.of(c3.value()), true, Operator.IN))).containsExactlyInAnyOrder(inC3, inC7, inC8);
        assertThat(ids(tags(List.of(t4.value(), t5.value()), Operator.AND))).containsExactly(inT4AndT5);
        assertThat(ids(tags(List.of(t4.value(), 999_999L), Operator.AND))).isEmpty();
        assertThat(ids(query(
                        Relation.OR,
                        categories(List.of(c3.value()), false, Operator.IN),
                        tags(List.of(t5.value()), Operator.IN))))
                .containsExactlyInAnyOrder(inC3, inT5, inT4AndT5);
    }

    @Test
    void idsAreMadePositiveZerosDroppedAndChildrenExcludedToo() throws Exception {
        // under AND an id that is not made positive, a zero kept or a duplicate kept would leave the listed and the
        // resolved sizes apart, so the clause would answer nothing
        assertThat(ids(categories(List.of(0L, -c3.value(), c3.value(), c3.value()), false, Operator.AND)))
                .containsExactly(inC3);
        assertThat(ids(categories(List.of(0L, -c3.value(), c3.value()), false, Operator.IN)))
                .containsExactly(inC3);
        assertThat(ids(categories(List.of(0L), false, Operator.IN))).isEmpty();
        assertThat(ids(categories(List.of(0L), false, Operator.AND))).isEmpty();
        assertThat(ids(categories(List.of(c3.value()), true, Operator.NOT_IN)))
                .containsExactlyInAnyOrder(inT5, inT4AndT5, plain);
        assertThat(ids(categories(List.of(c3.value()), false, Operator.NOT_IN)))
                .containsExactlyInAnyOrder(inC7, inC8, inT5, inT4AndT5, plain);
        // a flat taxonomy has no children to include
        assertThat(ids(new TaxClause.Terms("post_tag", List.of(t5.value()), true, Operator.IN)))
                .containsExactlyInAnyOrder(inT5, inT4AndT5);
        // all of the listed terms and their descendants (WordPress's quirk)
        assertThat(ids(categories(List.of(c3.value()), true, Operator.AND))).isEmpty();
        TestData.assign(dataSource, site, inC3, c7);
        TestData.assign(dataSource, site, inC3, c8);
        assertThat(ids(categories(List.of(c3.value()), true, Operator.AND))).containsExactly(inC3);
    }

    @Test
    void slugsAndNotExists() throws Exception {
        assertThat(ids(new TaxClause.Slugs("post_tag", List.of("t5", "nope"))))
                .containsExactlyInAnyOrder(inT5, inT4AndT5);
        assertThat(ids(new TaxClause.Slugs("post_tag", List.of("nope")))).isEmpty();
        assertThat(ids(new TaxClause.Slugs("category", List.of("t5")))).isEmpty();
        assertThat(ids(new TaxClause.NotExists("post_tag"))).containsExactlyInAnyOrder(inC3, inC7, inC8, plain);
        assertThat(ids(query(
                        Relation.AND,
                        categories(List.of(c3.value()), false, Operator.IN),
                        new TaxClause.Group(
                                Relation.OR,
                                List.of(
                                        new TaxClause.NotExists("post_tag"),
                                        new TaxClause.Slugs("post_tag", List.of("t4")))))))
                .containsExactly(inC3);
        assertThat(condition(new TaxClause.Slugs("post_tag", List.of("nope"))))
                .isInstanceOf(TermCondition.Nothing.class);
        assertThat(condition(excludeCategories(List.of(999_999L)))).isInstanceOf(TermCondition.None.class);
        assertThat(condition(categories(List.of(999_999L), false, Operator.IN)))
                .isInstanceOf(TermCondition.Nothing.class);
    }

    @Test
    void anotherSitesTermIdsResolveToNothing() throws Exception {
        SiteId other = TestData.site(dataSource);
        TermId foreign = TestData.term(dataSource, other, "category", "Foreign", "foreign", null);
        PostId otherPost = TestData.post(dataSource, other, "post", "publish", "other", "other");
        TestData.assign(dataSource, other, otherPost, foreign);
        assertThat(ids(categories(List.of(foreign.value()), false, Operator.IN)))
                .isEmpty();
        assertThat(ids(categories(List.of(foreign.value()), true, Operator.IN))).isEmpty();
        assertThat(ids(categories(List.of(foreign.value()), false, Operator.AND)))
                .isEmpty();
        assertThat(ids(excludeCategories(List.of(foreign.value())))).containsExactlyInAnyOrderElementsOf(all);
        // a term of another taxonomy on this site is not a term of the queried one either
        assertThat(ids(categories(List.of(t5.value()), false, Operator.IN))).isEmpty();
    }

    @Test
    void aDeepHierarchyExpandsLevelByLevel() throws Exception {
        // 5000 category terms in a 50-level chain: one root, then 49 levels of 102 terms each (one more on the last),
        // each term's parent a term of the level above
        TermId root = TestData.term(dataSource, site, "category", "Root", "deep-root", null);
        List<TermId> level = List.of(root);
        int made = 1;
        for (int depth = 2; depth <= 50; depth++) {
            int size = depth == 50 ? 5000 - made : 102;
            level = insertLevel("deep-" + depth + "-", size, level);
            made += size;
        }
        assertThat(made).isEqualTo(5000);
        TermId leaf = level.get(level.size() - 1);
        PostId deepest = post("deepest");
        TestData.assign(dataSource, site, deepest, leaf);

        Statistics statistics = termRows.getSession().getSessionFactory().getStatistics();
        statistics.clear();
        TermCondition condition = condition(categories(List.of(root.value()), true, Operator.IN));
        assertThat(condition).isInstanceOf(TermCondition.Jpql.class);
        assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(51);
        assertThat(((TermCondition.Jpql) condition)
                        .params().values().stream()
                                .filter(Collection.class::isInstance)
                                .mapToInt(value -> ((Collection<?>) value).size())
                                .sum())
                .isEqualTo(5000);

        assertThat(ids(categories(List.of(root.value()), true, Operator.IN))).containsExactly(deepest);
    }

    @Test
    void expandedIdListsAreBoundInChunks() throws Exception {
        List<TermId> tags = insertLevel("many-", 2500, List.of());
        PostId tagged = post("tagged");
        TestData.assign(dataSource, site, tagged, tags.get(2499));

        TermCondition.Jpql jpql = (TermCondition.Jpql) condition(tags(values(tags), Operator.IN));
        assertThat(jpql.text())
                .contains("in :t0_ids0 or ")
                .contains("in :t0_ids1 or ")
                .contains("in :t0_ids2")
                .doesNotContain("t0_ids3");
        assertThat(jpql.params()).containsKeys("t0_ids0", "t0_ids1", "t0_ids2");
        assertThat((Collection<?>) jpql.params().get("t0_ids0")).hasSize(1000);
        assertThat((Collection<?>) jpql.params().get("t0_ids1")).hasSize(1000);
        assertThat((Collection<?>) jpql.params().get("t0_ids2")).hasSize(500);
        assertThat(jpql.params().keySet()).allMatch(name -> name.startsWith("t0_"));

        TermCondition.Jpql one = (TermCondition.Jpql) condition(tags(values(tags.subList(0, 1000)), Operator.IN));
        assertThat(one.text()).contains("in :t0_ids0").doesNotContain("t0_ids1").doesNotContain(" or ");
        assertThat((Collection<?>) one.params().get("t0_ids0")).hasSize(1000);

        // the chunked statement runs on the database
        assertThat(ids(tags(values(tags), Operator.IN))).containsExactly(tagged);
        assertThat(ids(tags(values(tags), Operator.NOT_IN))).containsExactlyInAnyOrderElementsOf(all);
        assertThat(ids(tags(values(tags), Operator.AND))).isEmpty();
    }

    @Test
    void anExpansionBeyondTheCapIsRefused() throws Exception {
        assertThat(JpaTermConditions.MAX_EXPANDED_IDS).isEqualTo(10_000);
        provider.limitExpandedIds(3);
        try {
            // c3 and its two children are exactly three ids
            assertThat(ids(categories(List.of(c3.value()), true, Operator.IN)))
                    .containsExactlyInAnyOrder(inC3, inC7, inC8);
            TermId c9 = TestData.term(dataSource, site, "category", "C9", "c9", c7);
            assertThatThrownBy(() -> ids(categories(List.of(c3.value()), true, Operator.IN)))
                    .isInstanceOfSatisfying(
                            TermException.class,
                            refused -> assertThat(refused.code()).isEqualTo("term_filter_too_large"))
                    .hasMessage("The term filter expands to too many terms.");
            assertThatThrownBy(() -> ids(categories(List.of(c3.value()), true, Operator.NOT_IN)))
                    .isInstanceOf(TermException.class);
            // listed ids are not expanded, so they are not counted against the cap
            assertThat(ids(categories(List.of(c3.value(), c7.value(), c8.value(), c9.value()), false, Operator.IN)))
                    .containsExactlyInAnyOrder(inC3, inC7, inC8);
            assertThat(ids(categories(List.of(c7.value()), true, Operator.IN))).containsExactly(inC7);
        } finally {
            provider.limitExpandedIds(JpaTermConditions.MAX_EXPANDED_IDS);
        }
    }

    /**
     * Inserts {@code size} terms (tags without parents, categories under {@code parents} taken in turn) in one batch
     * and answers their ids by ascending id.
     */
    private List<TermId> insertLevel(String prefix, int size, List<TermId> parents) throws Exception {
        String taxonomy = parents.isEmpty() ? "post_tag" : "category";
        try (Connection connection = dataSource.getConnection();
                PreparedStatement insert = connection.prepareStatement(
                        "insert into qp_term (site_id, taxonomy, name, slug, parent_id, description)"
                                + " values (?, ?, ?, ?, ?, '')")) {
            for (int i = 0; i < size; i++) {
                insert.setLong(1, site.value());
                insert.setString(2, taxonomy);
                insert.setString(3, prefix + i);
                insert.setString(4, prefix + i);
                if (parents.isEmpty()) {
                    insert.setObject(5, null);
                } else {
                    insert.setLong(5, parents.get(i % parents.size()).value());
                }
                insert.addBatch();
            }
            insert.executeBatch();
        }
        List<TermId> ids = new ArrayList<>();
        for (String id : Jdbc.strings(
                dataSource,
                "select id from qp_term where site_id = ? and taxonomy = ? and slug like ? order by id",
                site.value(),
                taxonomy,
                prefix + "%")) {
            ids.add(TermId.of(Long.parseLong(id)));
        }
        assertThat(ids).hasSize(size);
        return ids;
    }
}
