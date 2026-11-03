package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.SearchColumn;
import org.quarkpress.posts.TaxClause;
import org.quarkpress.posts.TaxQuery;

/** Slug lists with their order, search columns and exact searches; an application without a term-filter provider. */
class QueryAdditionsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(QueryAdditionsDbTest.class);

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
    }

    /** A published post, each newer than the one before. */
    private PostId post(String title, String excerpt, String content) throws Exception {
        PostChanges changes = PostChanges.builder()
                .title(title)
                .excerpt(excerpt)
                .content(content)
                .status("publish")
                .dateGmt(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(86_400L * ++day))
                .build();
        return Identities.onSite(site, Identities.system(), () -> posts.create(changes))
                .id();
    }

    private List<PostId> query(PostQuery.Builder query) throws Exception {
        return Identities.onSite(site, Identities.anonymous(), () -> posts.query(query.build())).posts().stream()
                .map(PostSnapshot::id)
                .toList();
    }

    @Test
    void slugListsMatchAndOrderByPosition() throws Exception {
        PostId a = post("a", "", "");
        PostId b = post("b", "", "");
        PostId c = post("c", "", "");
        assertThat(query(PostQuery.builder().nameIn("c", "a"))).containsExactly(c, a);
        assertThat(query(PostQuery.builder().nameIn("c", "a").orderBy(OrderKey.NAME_IN, true)))
                .containsExactly(c, a);
        assertThat(query(PostQuery.builder().nameIn("a", "c").orderBy(OrderKey.NAME_IN, false)))
                .containsExactly(a, c);
        assertThat(query(PostQuery.builder().nameIn("A"))).containsExactly(a);
        assertThat(query(PostQuery.builder().nameIn("zzz"))).isEmpty();
        assertThat(query(PostQuery.builder().nameIn("b", "b").orderBy(OrderKey.NAME_IN, true)))
                .containsExactly(b);
        assertThat(query(PostQuery.builder().orderBy(OrderKey.NAME_IN, true))).containsExactly(c, b, a);
        assertThat(query(PostQuery.builder().nameIn("b", "a").name("a"))).containsExactly(a);
    }

    @Test
    void searchColumnsAndExactSearchNarrowTheSearch() throws Exception {
        PostId p1 = post("First", "needle", "");
        PostId p2 = post("Second", "", "needle");
        PostId p3 = post("needle title", "", "");
        PostId p4 = post("needle title 2", "", "");
        assertThat(query(PostQuery.builder().search("needle").searchColumns(SearchColumn.EXCERPT)))
                .containsExactly(p1);
        assertThat(query(PostQuery.builder().search("needle").searchColumns(SearchColumn.CONTENT)))
                .containsExactly(p2);
        assertThat(query(PostQuery.builder().search("needle").searchColumns(SearchColumn.TITLE)))
                .containsExactly(p4, p3);
        assertThat(query(
                        PostQuery.builder().search("needle").searchColumns(SearchColumn.EXCERPT, SearchColumn.CONTENT)))
                .containsExactly(p2, p1);
        assertThat(query(PostQuery.builder().search("needle"))).containsExactly(p4, p3, p2, p1);
        assertThat(query(PostQuery.builder().search("needle -second").searchColumns(SearchColumn.CONTENT)))
                .containsExactly(p2);
        assertThat(query(PostQuery.builder()
                        .search("needle -second")
                        .searchColumns(SearchColumn.TITLE, SearchColumn.CONTENT)))
                .containsExactly(p4, p3);
        assertThat(query(PostQuery.builder().search("needle -first").searchColumns(SearchColumn.CONTENT)))
                .containsExactly(p2);
        assertThat(query(PostQuery.builder()
                        .search("Needle Title")
                        .sentence(true)
                        .exactSearch(true)))
                .containsExactly(p3);
        assertThat(query(PostQuery.builder().search("needle title").exactSearch(true)))
                .isEmpty();
        assertThat(query(PostQuery.builder().search("needle").exactSearch(true)))
                .containsExactly(p2, p1);
        assertThat(query(PostQuery.builder().search("needle").exactSearch(true).searchColumns(SearchColumn.TITLE)))
                .isEmpty();
        assertThat(query(PostQuery.builder().search("needle%").exactSearch(true)))
                .isEmpty();
    }

    @Test
    void aQueryWithTermFiltersAndNoProviderIsRefused() throws Exception {
        PostId a = post("a", "", "");
        TaxClause clause = new TaxClause.Terms("category", List.of(1L), false, TaxClause.Operator.IN);
        assertThatThrownBy(() ->
                        query(PostQuery.builder().taxQuery(new TaxQuery(TaxClause.Relation.AND, List.of(clause)))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("term filters need the taxonomy module");
        assertThat(query(PostQuery.builder().taxQuery(new TaxQuery(TaxClause.Relation.OR, List.of()))))
                .containsExactly(a);
    }
}
