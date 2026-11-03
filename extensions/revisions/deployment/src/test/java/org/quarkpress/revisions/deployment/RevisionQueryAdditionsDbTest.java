package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.revisions.RevisionOrder;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;

/** The list parameters of WordPress's revisions controller: include, exclude, search and the orders. */
class RevisionQueryAdditionsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(RevisionQueryAdditionsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Revisions revisions;

    private SiteId site;
    private PostId post;
    private RevisionId r8;
    private RevisionId r9;
    private RevisionId r10;

    @BeforeEach
    void revisions() {
        site = TestData.site(dataSource);
        UserId author = TestData.profile(dataSource, "author");
        post = TestData.post(dataSource, site, "post", "publish", "p", "Post");
        r8 = TestData.revision(
                dataSource,
                site,
                post,
                null,
                "banana zebra cherry",
                "",
                "zebra ostrich",
                "{}",
                false,
                "2026-01-15T10:00:00Z");
        r9 = TestData.revision(
                dataSource,
                site,
                post,
                null,
                "zebra needle banana",
                "zebra penguin",
                "",
                "{}",
                false,
                "2026-01-15T11:00:00Z");
        r10 = TestData.revision(
                dataSource, site, post, author, "banana title", "", "zebra", "{}", true, "2026-01-15T12:00:00Z");
    }

    private List<RevisionId> ids(RevisionQuery.Builder query) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> revisions.ofPost(post, query.build())).stream()
                .map(RevisionSnapshot::id)
                .toList();
    }

    private long count(RevisionQuery.Builder query) throws Exception {
        return Identities.onSite(site, Identities.system(), () -> revisions.count(post, query.build()));
    }

    private static RevisionQuery.Builder order(RevisionOrder order, boolean ascending) {
        return RevisionQuery.builder().order(order).ascending(ascending);
    }

    @Test
    void wordPressOrders() throws Exception {
        assertThat(ids(order(RevisionOrder.DATE, false))).containsExactly(r10, r9, r8);
        assertThat(ids(order(RevisionOrder.DATE, true))).containsExactly(r8, r9, r10);
        assertThat(ids(order(RevisionOrder.ID, false))).containsExactly(r10, r9, r8);
        assertThat(ids(order(RevisionOrder.ID, true))).containsExactly(r8, r9, r10);
        assertThat(ids(order(RevisionOrder.TITLE, true))).containsExactly(r10, r8, r9);
        assertThat(ids(order(RevisionOrder.TITLE, false))).containsExactly(r9, r8, r10);
        assertThat(ids(RevisionQuery.builder()
                        .include(r9, r8, r10)
                        .order(RevisionOrder.INCLUDE)
                        .ascending(false)))
                .containsExactly(r9, r8, r10);
        assertThat(ids(RevisionQuery.builder()
                        .include(r9, r8, r10)
                        .order(RevisionOrder.INCLUDE)
                        .ascending(true)))
                .containsExactly(r9, r8, r10);
        assertThat(ids(order(RevisionOrder.INCLUDE_SLUGS, false))).containsExactly(r8, r9, r10);
        assertThat(ids(order(RevisionOrder.INCLUDE_SLUGS, true))).containsExactly(r8, r9, r10);
        assertThat(ids(order(RevisionOrder.SLUG, false))).startsWith(r8, r9).endsWith(r10);
        assertThat(ids(order(RevisionOrder.SLUG, true))).startsWith(r10).endsWith(r8, r9);
    }

    @Test
    void includeExcludeAndSearchRestrictTheRows() throws Exception {
        assertThat(ids(RevisionQuery.builder().search("needle"))).containsExactly(r9);
        assertThat(ids(RevisionQuery.builder().exclude(r9))).containsExactly(r10, r8);
        assertThat(ids(RevisionQuery.builder().include(r8, r9))).containsExactly(r9, r8);
        assertThat(ids(RevisionQuery.builder().include(r8).exclude(r8))).isEmpty();
        // the phrase, the exclusion and the content and excerpt columns
        assertThat(ids(RevisionQuery.builder().search("\"zebra cherry\""))).containsExactly(r8);
        assertThat(ids(RevisionQuery.builder().search("banana -cherry"))).containsExactly(r10, r9);
        assertThat(ids(RevisionQuery.builder().search("ostrich"))).containsExactly(r8);
        assertThat(ids(RevisionQuery.builder().search("penguin"))).containsExactly(r9);
        assertThat(ids(RevisionQuery.builder().search("BANANA"))).containsExactly(r10, r9, r8);
        // another post's revision never appears
        PostId other = TestData.post(dataSource, site, "post", "publish", "o", "Other");
        RevisionId elsewhere =
                TestData.revision(dataSource, site, other, null, "needle", "", "", "{}", false, "2026-01-15T13:00:00Z");
        assertThat(ids(RevisionQuery.builder().include(elsewhere, r8))).containsExactly(r8);
    }

    @Test
    void relevanceRanksTheTitleFirstThenDatesNewestFirst() throws Exception {
        // one token: a title match ranks before a match in the content, whatever the dates
        RevisionId r11 = TestData.revision(
                dataSource, site, post, null, "no fruit", "banana", "", "{}", false, "2026-01-15T13:00:00Z");
        assertThat(ids(RevisionQuery.builder().search("banana").order(RevisionOrder.RELEVANCE)))
                .containsExactly(r10, r9, r8, r11);
        assertThat(ids(RevisionQuery.builder().search("banana"))).containsExactly(r11, r10, r9, r8);
        // the whole search in the title ranks before every term in the title, before any term in the title
        assertThat(ids(RevisionQuery.builder().search("banana zebra").order(RevisionOrder.RELEVANCE)))
                .containsExactly(r8, r9, r10);
        assertThatThrownBy(() ->
                        RevisionQuery.builder().order(RevisionOrder.RELEVANCE).build())
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                        RevisionQuery.builder().order(RevisionOrder.INCLUDE).build())
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void countIgnoresLimitAndOffset() throws Exception {
        assertThat(count(RevisionQuery.builder().limit(1).offset(1))).isEqualTo(3);
        assertThat(ids(RevisionQuery.builder().limit(1).offset(1))).containsExactly(r9);
        assertThat(count(RevisionQuery.builder().exclude(r9))).isEqualTo(2);
        assertThat(count(RevisionQuery.builder().search("needle"))).isEqualTo(1);
        assertThat(count(RevisionQuery.builder().include(r9, r8, r10).order(RevisionOrder.INCLUDE)))
                .isEqualTo(3);
        assertThat(ids(RevisionQuery.builder()
                        .include(r9, r8, r10)
                        .order(RevisionOrder.INCLUDE)
                        .limit(1)
                        .offset(1)))
                .containsExactly(r8);
        TestData.featureOff(dataSource, site, "revisions");
        assertThat(count(RevisionQuery.builder())).isZero();
        assertThat(count(RevisionQuery.builder().checkEnabled(false))).isEqualTo(3);
    }
}
