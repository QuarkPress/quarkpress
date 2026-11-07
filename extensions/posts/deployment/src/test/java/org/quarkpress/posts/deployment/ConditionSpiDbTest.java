package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostConditionProvider;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostQueryFilter;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.QueryContext;
import org.quarkpress.posts.TaxClause;
import org.quarkpress.posts.TaxQuery;
import org.quarkpress.posts.TermCondition;
import org.quarkpress.posts.TermConditionProvider;

/**
 * The post-condition SPI: modules add their own conditions to a {@link PostQuery} by key, each answered by the bean
 * that provides the key, AND-ed with the rest of the query and counted in its total.
 */
class ConditionSpiDbTest {

    /** Each provider call's key, post id path and parameter prefix, in order. */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    /**
     * Posts whose slug starts with the text given; {@code "*"} applies no condition, {@code "!"} matches nothing and
     * {@code "stray"} names a parameter outside the prefix.
     */
    @ApplicationScoped
    static class SlugPrefix implements PostConditionProvider {
        @Override
        public String key() {
            return "test.slug-prefix";
        }

        @Override
        public TermCondition condition(SiteId site, JsonNode argument, String postId, String paramPrefix) {
            CALLS.add(key() + " " + postId + " " + paramPrefix);
            String text = argument.asText();
            return switch (text) {
                case "*" -> new TermCondition.None();
                case "!" -> new TermCondition.Nothing();
                case "stray" -> new TermCondition.Jpql(postId + " in :stray", Map.of("stray", List.of(0L)));
                default ->
                    new TermCondition.Jpql(
                            postId + " in (select p2.id from PostEntity p2 where p2.slug like :" + paramPrefix + "0)",
                            Map.of(paramPrefix + "0", text + "%"));
            };
        }
    }

    /** Posts whose title ends with the text given. */
    @ApplicationScoped
    static class TitleSuffix implements PostConditionProvider {
        @Override
        public String key() {
            return "test.title-suffix";
        }

        @Override
        public TermCondition condition(SiteId site, JsonNode argument, String postId, String paramPrefix) {
            CALLS.add(key() + " " + postId + " " + paramPrefix);
            return new TermCondition.Jpql(
                    postId + " in (select p3.id from PostEntity p3 where p3.title like :" + paramPrefix + "end)",
                    Map.of(paramPrefix + "end", "%" + argument.asText()));
        }
    }

    /** Term filters naming the term 1 keep only the posts whose slug starts with {@code b}. */
    @ApplicationScoped
    static class BTerms implements TermConditionProvider {
        @Override
        public TermCondition condition(SiteId site, TaxClause clause, String postId, String paramPrefix) {
            return new TermCondition.Jpql(
                    postId + " in (select p4.id from PostEntity p4 where p4.slug like :" + paramPrefix + "b)",
                    Map.of(paramPrefix + "b", "b%"));
        }
    }

    /** Replaces a slug-prefix condition {@code "filtered"} with {@code "a"}: filters see and change conditions. */
    @ApplicationScoped
    static class Rewrite implements PostQueryFilter {
        @Override
        public PostQuery apply(PostQuery query, QueryContext context) {
            JsonNode given = query.conditions().get("test.slug-prefix");
            if (given == null || !given.asText().equals("filtered")) {
                return query;
            }
            return query.toBuilder().condition("test.slug-prefix", text("a")).build();
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app =
            PostsApp.with(ConditionSpiDbTest.class, SlugPrefix.class, TitleSuffix.class, BTerms.class, Rewrite.class);

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
        CALLS.clear();
    }

    private static JsonNode text(String value) {
        return JsonNodeFactory.instance.textNode(value);
    }

    /** A published post with this slug and title, each newer than the one before. */
    private PostId post(String slug, String title) throws Exception {
        PostChanges changes = PostChanges.builder()
                .title(title)
                .slug(slug)
                .status("publish")
                .dateGmt(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(86_400L * ++day))
                .build();
        return Identities.onSite(site, Identities.system(), () -> posts.create(changes))
                .id();
    }

    private PostPage page(PostQuery.Builder query) throws Exception {
        return Identities.onSite(site, Identities.anonymous(), () -> posts.query(query.build()));
    }

    private static List<PostId> ids(PostPage page) {
        return page.posts().stream().map(PostSnapshot::id).toList();
    }

    @Test
    void aConditionKeepsOnlyMatchingPostsAndCountsWithThem() throws Exception {
        PostId a1 = post("apple", "one");
        PostId a2 = post("apricot", "two");
        PostId b = post("banana", "three");
        PostId a3 = post("avocado", "four");
        assertThat(ids(page(PostQuery.builder()))).containsExactly(a3, b, a2, a1);

        PostPage first = page(PostQuery.builder().perPage(2).condition("test.slug-prefix", text("a")));
        assertThat(ids(first)).containsExactly(a3, a2);
        assertThat(first.found()).isEqualTo(3);
        assertThat(first.maxPages()).isEqualTo(2);
        PostPage second = page(PostQuery.builder().perPage(2).page(2).condition("test.slug-prefix", text("a")));
        assertThat(ids(second)).containsExactly(a1);
        assertThat(second.found()).isEqualTo(3);
        assertThat(ids(page(PostQuery.builder().condition("test.slug-prefix", text("ban")))))
                .containsExactly(b);
    }

    @Test
    void conditionsAreAndedInTheOrderGivenWithTheirOwnPrefixes() throws Exception {
        PostId a1 = post("apple", "red fruit");
        post("apricot", "orange fruit");
        PostId b = post("banana", "yellow fruit");
        post("cherry", "red berry");
        PostQuery.Builder query = PostQuery.builder()
                .condition("test.title-suffix", text("fruit"))
                .condition("test.slug-prefix", text("a"));
        assertThat(query.build().conditions()).containsOnlyKeys("test.title-suffix", "test.slug-prefix");
        assertThat(List.copyOf(query.build().conditions().keySet()))
                .containsExactly("test.title-suffix", "test.slug-prefix");
        assertThat(ids(page(query.condition("test.title-suffix", text("red fruit")))))
                .containsExactly(a1);
        assertThat(CALLS).containsExactly("test.title-suffix p.id c0_", "test.slug-prefix p.id c1_");

        // next to the term filters and every other condition
        PostQuery.Builder withTerms = PostQuery.builder()
                .condition("test.title-suffix", text("fruit"))
                .taxQuery(new TaxQuery(
                        TaxClause.Relation.AND,
                        List.of(new TaxClause.Terms("category", List.of(1L), false, TaxClause.Operator.IN))));
        assertThat(ids(page(withTerms))).containsExactly(b);
        assertThat(ids(page(PostQuery.builder()
                        .condition("test.slug-prefix", text("a"))
                        .search("orange"))))
                .hasSize(1);
    }

    @Test
    void nothingEmptiesTheQueryAndNoneIsSkipped() throws Exception {
        PostId a = post("apple", "one");
        PostId b = post("banana", "two");
        PostPage emptied = page(PostQuery.builder()
                .perPage(1)
                .condition("test.title-suffix", text("one"))
                .condition("test.slug-prefix", text("!")));
        assertThat(emptied.posts()).isEmpty();
        assertThat(emptied.found()).isZero();
        assertThat(emptied.maxPages()).isZero();
        PostPage unpaged = page(PostQuery.builder().perPage(-1).condition("test.slug-prefix", text("!")));
        assertThat(unpaged.posts()).isEmpty();
        assertThat(unpaged.found()).isZero();

        PostPage skipped = page(PostQuery.builder().perPage(1).condition("test.slug-prefix", text("*")));
        assertThat(ids(skipped)).containsExactly(b);
        assertThat(skipped.found()).isEqualTo(2);
        assertThat(ids(page(PostQuery.builder().condition("test.slug-prefix", text("*")))))
                .containsExactly(b, a);
    }

    @Test
    void queryFiltersSeeAndChangeTheConditions() throws Exception {
        PostId a = post("apple", "one");
        post("filtered-banana", "two");
        assertThat(ids(page(PostQuery.builder().condition("test.slug-prefix", text("filtered")))))
                .containsExactly(a);
        PostQuery query =
                PostQuery.builder().condition("test.slug-prefix", text("x")).build();
        assertThat(query.toBuilder().build().conditions()).isEqualTo(query.conditions());
        assertThat(PostQuery.builder().build().conditions()).isEmpty();
    }

    @Test
    void aKeyWithoutProviderIsRefusedWhenTheQueryRuns() throws Exception {
        post("apple", "one");
        PostQuery.Builder unknown = PostQuery.builder().condition("test.unknown", text("a"));
        assertThatThrownBy(() -> page(unknown))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no post condition provider for test.unknown");
    }

    @Test
    void aParameterOutsideThePrefixIsRefused() throws Exception {
        post("apple", "one");
        PostQuery.Builder stray = PostQuery.builder()
                .condition("test.title-suffix", text("one"))
                .condition("test.slug-prefix", text("stray"));
        assertThatThrownBy(() -> page(stray))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the post condition parameter stray does not start with c1_");
    }
}
