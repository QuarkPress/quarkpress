package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSearchProvider;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.SearchColumn;
import org.quarkpress.posts.TermCondition;

/**
 * The search SPI: a module adds a searchable column by key, OR-ed with the post columns for each search term and
 * required not to match for an excluded term.
 */
class PostSearchProviderDbTest {

    /** Each provider call's term, exact flag, post id path and parameter prefix, in order. */
    static final List<String> CALLS = new CopyOnWriteArrayList<>();

    /** The post ids each term matches in the provided column; a term not listed matches nothing. */
    static final Map<String, List<Long>> MATCHES = new ConcurrentHashMap<>();

    /** A column holding, for each term, the post ids listed for it in {@link #MATCHES}; {@code skip} adds nothing. */
    @ApplicationScoped
    static class ListedIds implements PostSearchProvider {
        @Override
        public String key() {
            return "test.listed-ids";
        }

        @Override
        public TermCondition term(SiteId site, String term, boolean exact, String postId, String paramPrefix) {
            CALLS.add(term + " " + exact + " " + postId + " " + paramPrefix);
            if (term.equals("skip")) {
                return new TermCondition.None();
            }
            List<Long> ids = MATCHES.get(term);
            if (ids == null) {
                return new TermCondition.Nothing();
            }
            return new TermCondition.Jpql(postId + " in :" + paramPrefix + "ids", Map.of(paramPrefix + "ids", ids));
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PostSearchProviderDbTest.class, ListedIds.class);

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
        MATCHES.clear();
    }

    /** A published post with this title, each newer than the one before. */
    private PostId post(String title) throws Exception {
        PostChanges changes = PostChanges.builder()
                .title(title)
                .status("publish")
                .dateGmt(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(86_400L * ++day))
                .build();
        return Identities.onSite(site, Identities.system(), () -> posts.create(changes))
                .id();
    }

    private List<PostId> ids(PostQuery.Builder query) throws Exception {
        return Identities.onSite(site, Identities.anonymous(), () -> posts.query(query.build())).posts().stream()
                .map(PostSnapshot::id)
                .toList();
    }

    private static PostQuery.Builder search(String text) {
        return PostQuery.builder().search(text).searchAlso("test.listed-ids");
    }

    @Test
    void aProvidedColumnIsOredWithThePostColumnsPerTerm() throws Exception {
        PostId alphaBeta = post("alpha");
        PostId betaAlpha = post("beta");
        PostId alphaOnly = post("alpha only");
        PostId neither = post("gamma");
        MATCHES.put("beta", List.of(alphaBeta.value()));
        MATCHES.put("alpha", List.of(betaAlpha.value()));

        // each term is found in the title or in the provided column
        assertThat(ids(search("alpha beta"))).containsExactlyInAnyOrder(betaAlpha, alphaBeta);
        assertThat(CALLS).containsExactly("alpha false p.id s0_", "beta false p.id s1_");
        assertThat(ids(search("alpha"))).containsExactlyInAnyOrder(alphaOnly, betaAlpha, alphaBeta);
        // without the key, only the post columns
        assertThat(ids(PostQuery.builder().search("alpha beta"))).isEmpty();
        assertThat(ids(PostQuery.builder().search("alpha"))).containsExactlyInAnyOrder(alphaOnly, alphaBeta);
        // whatever the searched columns
        assertThat(ids(search("beta").searchColumns(SearchColumn.EXCERPT))).containsExactly(alphaBeta);
        // a term the column matches nothing for, or adds no condition for, is searched in the post columns only
        assertThat(ids(search("gamma"))).containsExactly(neither);
        assertThat(ids(search("gamma skip"))).isEmpty();
        assertThat(ids(search("gamma -skip"))).containsExactly(neither);
        // the exact flag reaches the provider
        CALLS.clear();
        assertThat(ids(search("beta").exactSearch(true))).containsExactlyInAnyOrder(betaAlpha, alphaBeta);
        assertThat(CALLS).containsExactly("beta true p.id s0_");
        // the provided column counts in the totals
        assertThat(Identities.onSite(
                                site,
                                Identities.anonymous(),
                                () -> posts.query(search("alpha").perPage(1).build()))
                        .found())
                .isEqualTo(3);
    }

    @Test
    void anExcludedTermExcludesOnTheProvidedColumnToo() throws Exception {
        PostId listed = post("alpha");
        PostId kept = post("alpha too");
        PostId titled = post("alpha beta");
        MATCHES.put("beta", List.of(listed.value()));

        assertThat(ids(search("alpha -beta"))).containsExactly(kept);
        assertThat(ids(PostQuery.builder().search("alpha -beta"))).containsExactlyInAnyOrder(kept, listed);
        // an excluded term the column matches nothing for excludes by the post columns only
        assertThat(ids(search("alpha -gamma"))).containsExactlyInAnyOrder(titled, kept, listed);
        assertThat(ids(search("alpha -skip"))).containsExactlyInAnyOrder(titled, kept, listed);
    }

    @Test
    void anUnknownKeyIsRefused() throws Exception {
        post("alpha");
        assertThatThrownBy(() -> ids(PostQuery.builder().search("alpha").searchAlso("test.unknown")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("no post search provider for test.unknown");
        assertThat(PostQuery.builder()
                        .searchAlso("test.listed-ids")
                        .searchAlso("test.listed-ids")
                        .build()
                        .searchAlso())
                .containsExactly("test.listed-ids");
        assertThat(search("x").build().toBuilder().build().searchAlso()).containsExactly("test.listed-ids");
    }
}
