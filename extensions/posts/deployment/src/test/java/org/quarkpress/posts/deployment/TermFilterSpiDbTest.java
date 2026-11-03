package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.TaxClause;
import org.quarkpress.posts.TaxQuery;
import org.quarkpress.posts.TermCondition;
import org.quarkpress.posts.TermConditionProvider;

/**
 * The term-filter SPI from the posts side: a test provider answers id lists for terms it knows, and the query combines
 * the clauses as WordPress's tax query combines them.
 */
class TermFilterSpiDbTest {

    /** The term id the provider answers "matches nothing" for. */
    static final long UNKNOWN = 999;

    /** The term id the provider answers with a parameter named outside its prefix for. */
    static final long STRAY = 998;

    /**
     * A provider over its own map of term id to post ids: {@code IN} and {@code AND} terms answer an id list (nothing
     * for the unknown term), {@code NOT_IN} terms the complement, slugs and the {@code none} taxonomy no condition.
     */
    @ApplicationScoped
    static class TestTermConditions implements TermConditionProvider {

        static final Map<Long, List<Long>> TERM_POSTS = new ConcurrentHashMap<>();

        /** Each call's post id path and parameter prefix, in order. */
        static final List<String> CALLS = new CopyOnWriteArrayList<>();

        @Override
        public TermCondition condition(SiteId site, TaxClause clause, String postId, String paramPrefix) {
            CALLS.add(postId + " " + paramPrefix);
            return switch (clause) {
                case TaxClause.Terms terms -> terms(terms, postId, paramPrefix);
                case TaxClause.Slugs slugs -> new TermCondition.None();
                case TaxClause.NotExists notExists -> new TermCondition.None();
                case TaxClause.Group group -> throw new AssertionError("a group reached the provider");
            };
        }

        private static TermCondition terms(TaxClause.Terms terms, String postId, String paramPrefix) {
            if (terms.termIds().contains(UNKNOWN)) {
                return new TermCondition.Nothing();
            }
            if (terms.termIds().contains(STRAY)) {
                return new TermCondition.Jpql(postId + " in :stray", Map.of("stray", List.of(0L)));
            }
            Set<Long> ids = new LinkedHashSet<>();
            for (long term : terms.termIds()) {
                ids.addAll(TERM_POSTS.getOrDefault(term, List.of()));
            }
            String name = paramPrefix + "ids";
            String text = terms.operator() == TaxClause.Operator.NOT_IN
                    ? postId + " not in :" + name
                    : postId + " in :" + name;
            return new TermCondition.Jpql(text, Map.of(name, List.copyOf(ids)));
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(TermFilterSpiDbTest.class, TestTermConditions.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    SiteId site;
    int day;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        day = 0;
        TestTermConditions.TERM_POSTS.clear();
        TestTermConditions.CALLS.clear();
    }

    /** A published post, each newer than the one before. */
    private PostId post(String title) throws Exception {
        PostChanges changes = PostChanges.builder()
                .title(title)
                .status("publish")
                .dateGmt(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(86_400L * ++day))
                .build();
        return Identities.onSite(site, Identities.system(), () -> posts.create(changes))
                .id();
    }

    private static void term(long id, PostId... members) {
        List<Long> ids = new ArrayList<>();
        for (PostId member : members) {
            ids.add(member.value());
        }
        TestTermConditions.TERM_POSTS.put(id, ids);
    }

    private static TaxClause in(long... termIds) {
        List<Long> ids = new ArrayList<>();
        for (long id : termIds) {
            ids.add(id);
        }
        return new TaxClause.Terms("category", ids, false, TaxClause.Operator.IN);
    }

    private static TaxClause notIn(long termId) {
        return new TaxClause.Terms("category", List.of(termId), false, TaxClause.Operator.NOT_IN);
    }

    private static TaxClause none() {
        return new TaxClause.NotExists("none");
    }

    private PostPage page(PostQuery.Builder query) throws Exception {
        return Identities.onSite(site, Identities.anonymous(), () -> posts.query(query.build()));
    }

    private List<PostId> query(TaxClause.Relation relation, TaxClause... clauses) throws Exception {
        return ids(page(PostQuery.builder().taxQuery(new TaxQuery(relation, List.of(clauses)))));
    }

    private static List<PostId> ids(PostPage page) {
        return page.posts().stream().map(PostSnapshot::id).toList();
    }

    @Test
    void clausesCombineByRelation() throws Exception {
        PostId a = post("a");
        PostId b = post("b");
        PostId c = post("c");
        term(1, a, b);
        term(2, b, c);
        assertThat(query(TaxClause.Relation.AND, in(1), in(2))).containsExactly(b);
        assertThat(query(TaxClause.Relation.OR, in(1), in(2))).containsExactly(c, b, a);
        assertThat(query(TaxClause.Relation.AND, in(1), in(UNKNOWN))).isEmpty();
        assertThat(query(TaxClause.Relation.OR, in(1), in(UNKNOWN))).containsExactly(b, a);
        assertThat(query(TaxClause.Relation.AND, in(1), none())).containsExactly(b, a);
        assertThat(query(TaxClause.Relation.OR, in(2), none())).containsExactly(c, b);
        assertThat(query(TaxClause.Relation.OR, in(UNKNOWN))).isEmpty();
        assertThat(query(TaxClause.Relation.OR, in(UNKNOWN), none())).isEmpty();
        assertThat(query(TaxClause.Relation.AND, none())).containsExactly(c, b, a);
        assertThat(query(TaxClause.Relation.AND, in(1, 2), notIn(2))).containsExactly(a);
    }

    @Test
    void anEmptiedQueryHasNoRowsAndCountsNone() throws Exception {
        PostId a = post("a");
        term(1, a);
        PostPage page = page(PostQuery.builder()
                .perPage(2)
                .taxQuery(new TaxQuery(TaxClause.Relation.AND, List.of(in(1), in(UNKNOWN)))));
        assertThat(page.posts()).isEmpty();
        assertThat(page.found()).isZero();
        assertThat(page.maxPages()).isZero();
        PostPage unpaged = page(
                PostQuery.builder().perPage(-1).taxQuery(new TaxQuery(TaxClause.Relation.OR, List.of(in(UNKNOWN)))));
        assertThat(unpaged.posts()).isEmpty();
        assertThat(unpaged.found()).isZero();
    }

    @Test
    void aGroupCombinesItsClausesAndCountsAsOne() throws Exception {
        PostId a = post("a");
        PostId b = post("b");
        PostId c = post("c");
        term(1, a, b);
        term(2, b, c);
        term(3, c);
        TaxClause orGroup = new TaxClause.Group(TaxClause.Relation.OR, List.of(in(2), in(UNKNOWN), in(3)));
        assertThat(query(TaxClause.Relation.AND, in(1), orGroup)).containsExactly(b);
        TaxClause andGroup = new TaxClause.Group(TaxClause.Relation.AND, List.of(in(2), in(3)));
        assertThat(query(TaxClause.Relation.OR, in(1), andGroup)).containsExactly(c, b, a);
        TaxClause emptied = new TaxClause.Group(TaxClause.Relation.AND, List.of(in(2), in(UNKNOWN)));
        assertThat(query(TaxClause.Relation.OR, in(1), emptied)).containsExactly(b, a);
        assertThat(query(TaxClause.Relation.AND, in(1), emptied)).isEmpty();
        TaxClause skipped = new TaxClause.Group(TaxClause.Relation.OR, List.of(none()));
        assertThat(query(TaxClause.Relation.AND, in(3), skipped)).containsExactly(c);
    }

    @Test
    void theProviderGetsThePostIdPathAndAPrefixPerClause() throws Exception {
        PostId a = post("a");
        term(1, a);
        TaxClause group = new TaxClause.Group(TaxClause.Relation.OR, List.of(in(1), none()));
        assertThat(query(TaxClause.Relation.AND, in(1), none(), group)).containsExactly(a);
        assertThat(TestTermConditions.CALLS).containsExactly("p.id t0_", "p.id t1_", "p.id t2_0_", "p.id t2_1_");
    }

    @Test
    void aPositiveClauseTurnsStickyFirstOffAndAnExcludeOnlyQueryKeepsIt() throws Exception {
        PostId oldest = post("oldest");
        PostId p2 = post("p2");
        PostId p3 = post("p3");
        PostId p4 = post("p4");
        Identities.onSite(site, Identities.system(), () -> posts.stick(oldest));
        term(1, oldest, p2, p3, p4);
        term(2, p2);
        PostQuery.Builder plain = PostQuery.builder().perPage(2).stickyFirst(true);
        assertThat(ids(page(plain))).containsExactly(oldest, p4, p3);
        PostQuery.Builder positive = PostQuery.builder()
                .perPage(2)
                .stickyFirst(true)
                .taxQuery(new TaxQuery(TaxClause.Relation.AND, List.of(in(1))));
        assertThat(ids(page(positive))).containsExactly(p4, p3);
        PostQuery.Builder excludeOnly = PostQuery.builder()
                .perPage(2)
                .stickyFirst(true)
                .taxQuery(new TaxQuery(TaxClause.Relation.AND, List.of(notIn(2))));
        PostPage excluded = page(excludeOnly);
        assertThat(ids(excluded)).containsExactly(oldest, p4, p3);
        assertThat(excluded.found()).isEqualTo(3);
        PostQuery.Builder stickyInExcluded = PostQuery.builder()
                .perPage(2)
                .stickyFirst(true)
                .taxQuery(new TaxQuery(TaxClause.Relation.AND, List.of(notIn(1))));
        assertThat(ids(page(stickyInExcluded))).containsExactly(oldest);
        PostQuery.Builder groupWithPositive = PostQuery.builder()
                .perPage(2)
                .stickyFirst(true)
                .taxQuery(new TaxQuery(
                        TaxClause.Relation.AND,
                        List.of(new TaxClause.Group(TaxClause.Relation.OR, List.of(none(), in(1))))));
        assertThat(ids(page(groupWithPositive))).containsExactly(p4, p3);
    }

    @Test
    void positiveClausesAreInAndAndTermsSlugsAndGroupsHoldingThem() {
        TaxClause slugs = new TaxClause.Slugs("post_format", List.of("post-format-aside"));
        TaxClause andTerms = new TaxClause.Terms("category", List.of(1L), true, TaxClause.Operator.AND);
        assertThat(new TaxQuery(TaxClause.Relation.AND, List.of()).positive()).isFalse();
        assertThat(new TaxQuery(TaxClause.Relation.AND, List.of(notIn(1), none())).positive())
                .isFalse();
        assertThat(new TaxQuery(TaxClause.Relation.OR, List.of(in(1))).positive())
                .isTrue();
        assertThat(new TaxQuery(TaxClause.Relation.OR, List.of(andTerms)).positive())
                .isTrue();
        assertThat(new TaxQuery(TaxClause.Relation.OR, List.of(slugs)).positive())
                .isTrue();
        assertThat(new TaxQuery(
                                TaxClause.Relation.AND,
                                List.of(new TaxClause.Group(TaxClause.Relation.OR, List.of(none(), slugs))))
                        .positive())
                .isTrue();
        assertThat(new TaxQuery(
                                TaxClause.Relation.AND,
                                List.of(new TaxClause.Group(TaxClause.Relation.OR, List.of(none(), notIn(1)))))
                        .positive())
                .isFalse();
    }

    @Test
    void anEmptySlugListIsNotPositiveAndAddsNoClause() throws Exception {
        PostId a = post("a");
        PostId b = post("b");
        Identities.onSite(site, Identities.system(), () -> posts.stick(a));
        term(1, b);
        TaxClause empty = new TaxClause.Slugs("post_format", List.of());
        assertThat(empty.positive()).isFalse();
        assertThat(new TaxQuery(TaxClause.Relation.AND, List.of(empty)).positive())
                .isFalse();
        assertThat(query(TaxClause.Relation.AND, in(1), empty)).containsExactly(b);
        assertThat(query(TaxClause.Relation.OR, empty)).containsExactly(b, a);
        assertThat(TestTermConditions.CALLS).containsExactly("p.id t0_");
        PostQuery.Builder stickyKept = PostQuery.builder()
                .perPage(1)
                .stickyFirst(true)
                .taxQuery(new TaxQuery(TaxClause.Relation.AND, List.of(empty)));
        assertThat(ids(page(stickyKept))).containsExactly(a, b);
    }

    @Test
    void aGroupInsideAGroupIsRefused() {
        TaxClause inner = new TaxClause.Group(TaxClause.Relation.OR, List.of(in(1)));
        assertThatThrownBy(() -> new TaxClause.Group(TaxClause.Relation.AND, List.of(inner)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("term filter groups do not nest");
    }

    @Test
    void aParameterOutsideThePrefixIsRefused() throws Exception {
        PostId a = post("a");
        term(1, a);
        assertThatThrownBy(() -> query(TaxClause.Relation.AND, in(1), in(STRAY)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the term condition parameter stray does not start with t1_");
    }
}
