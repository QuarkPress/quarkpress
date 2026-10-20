package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.DateBound;
import org.quarkpress.posts.DateColumn;
import org.quarkpress.posts.DateCondition;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

/** Date conditions on a site in Paris; the post dates below are local unless they end in Z. */
class DateQueryDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(DateQueryDbTest.class, TestClock.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TestClock clock;

    SiteId site;
    PostSnapshot dec31;
    PostSnapshot jan1;
    PostSnapshot jan15noon;
    PostSnapshot jan15late;
    PostSnapshot feb1;

    private static LocalDateTime at(String text) {
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    private PostSnapshot post(String date) throws Exception {
        return Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title(date)
                        .status("publish")
                        .date(at(date))
                        .build()));
    }

    private PostSnapshot postAt(String gmt) throws Exception {
        return Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title(gmt)
                        .status("publish")
                        .dateGmt(Instant.parse(gmt))
                        .build()));
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.set(Instant.parse("2026-10-18T10:00:00Z"));
        site = TestData.siteIn(dataSource, "Europe/Paris");
        dec31 = post("2025-12-31 23:59:59");
        jan1 = post("2026-01-01 00:00:00");
        jan15noon = post("2026-01-15 12:00:00");
        jan15late = post("2026-01-15 23:59:59");
        feb1 = post("2026-02-01 00:00:00");
    }

    private List<PostId> query(DateCondition... conditions) throws Exception {
        PostQuery.Builder query = PostQuery.builder().perPage(-1);
        for (DateCondition condition : conditions) {
            query.date(condition);
        }
        return Identities.onSite(site, Identities.system(), () -> posts.query(query.build())).posts().stream()
                .map(PostSnapshot::id)
                .toList();
    }

    @Test
    void afterAMonthIsAfterItsEndUnlessInclusive() throws Exception {
        assertThat(query(DateCondition.after(DateColumn.DATE, DateBound.month(2026, 1), false)))
                .containsExactly(feb1.id());
        assertThat(query(DateCondition.after(DateColumn.DATE, DateBound.month(2026, 1), true)))
                .containsExactly(feb1.id(), jan15late.id(), jan15noon.id(), jan1.id());
    }

    @Test
    void beforeADayIsBeforeItsStartUnlessInclusive() throws Exception {
        assertThat(query(DateCondition.before(DateColumn.DATE, DateBound.day(2026, 1, 15), false)))
                .containsExactly(jan1.id(), dec31.id());
        assertThat(query(DateCondition.before(DateColumn.DATE, DateBound.day(2026, 1, 15), true)))
                .containsExactly(jan15late.id(), jan15noon.id(), jan1.id(), dec31.id());
    }

    @Test
    void minutesAndExactTimesAreComparedAsWordPressPadsThem() throws Exception {
        assertThat(query(DateCondition.after(DateColumn.DATE, DateBound.minute(at("2026-01-15 11:59:00")), false)))
                .containsExactly(feb1.id(), jan15late.id(), jan15noon.id());
        assertThat(query(DateCondition.after(DateColumn.DATE, DateBound.minute(at("2026-01-15 12:00:00")), false)))
                .containsExactly(feb1.id(), jan15late.id());
        assertThat(query(DateCondition.after(DateColumn.DATE, DateBound.exact(at("2026-01-15 12:00:00")), false)))
                .containsExactly(feb1.id(), jan15late.id());
        assertThat(query(DateCondition.after(DateColumn.DATE, DateBound.exact(at("2026-01-15 12:00:00")), true)))
                .containsExactly(feb1.id(), jan15late.id(), jan15noon.id());
        assertThat(query(DateCondition.before(DateColumn.DATE, DateBound.exact(at("2026-01-15 23:59:59")), false)))
                .containsExactly(jan15noon.id(), jan1.id(), dec31.id());
        assertThat(query(DateCondition.before(DateColumn.DATE, DateBound.exact(at("2026-01-15 23:59:59")), true)))
                .containsExactly(jan15late.id(), jan15noon.id(), jan1.id(), dec31.id());
    }

    @Test
    void gmtColumnsCompareInUtc() throws Exception {
        assertThat(query(DateCondition.before(DateColumn.DATE_GMT, DateBound.exact(at("2025-12-31 23:00:00")), true)))
                .containsExactly(jan1.id(), dec31.id());
        assertThat(query(DateCondition.before(DateColumn.DATE_GMT, DateBound.exact(at("2025-12-31 23:00:00")), false)))
                .containsExactly(dec31.id());
    }

    @Test
    void modificationDatesCompareInTheSiteZoneOrInUtc() throws Exception {
        assertThat(query(DateCondition.after(DateColumn.MODIFIED, DateBound.month(2026, 1), false)))
                .containsExactly(feb1.id());
        assertThat(query(DateCondition.before(
                        DateColumn.MODIFIED_GMT, DateBound.exact(at("2025-12-31 23:00:00")), true)))
                .containsExactly(jan1.id(), dec31.id());
    }

    @Test
    void betweenAndSeveralConditionsAllApply() throws Exception {
        assertThat(query(DateCondition.between(
                        DateColumn.DATE, DateBound.day(2026, 1, 1), DateBound.day(2026, 1, 15), true)))
                .containsExactly(jan15late.id(), jan15noon.id(), jan1.id());
        assertThat(query(DateCondition.between(
                        DateColumn.DATE, DateBound.day(2026, 1, 1), DateBound.day(2026, 1, 15), false)))
                .isEmpty();
        assertThat(query(DateCondition.between(
                        DateColumn.DATE, DateBound.year(2025), DateBound.day(2026, 1, 15), false)))
                .containsExactly(jan1.id());
        assertThat(query(
                        DateCondition.after(DateColumn.DATE, DateBound.year(2025), false),
                        DateCondition.before(DateColumn.DATE_GMT, DateBound.day(2026, 1, 15), true)))
                .containsExactly(jan15late.id(), jan15noon.id(), jan1.id());
    }

    @Test
    void aBoundNamingNoRealDateSplitsWhereItsTextSorts() throws Exception {
        PostSnapshot feb28 = post("2026-02-28 23:59:59");
        PostSnapshot mar1 = post("2026-03-01 00:00:00");
        for (boolean inclusive : new boolean[] {false, true}) {
            assertThat(query(DateCondition.after(DateColumn.DATE, DateBound.day(2026, 2, 30), inclusive)))
                    .containsExactly(mar1.id());
            assertThat(query(DateCondition.between(
                            DateColumn.DATE, DateBound.day(2026, 1, 31), DateBound.day(2026, 2, 30), inclusive)))
                    .containsExactly(feb28.id(), feb1.id());
        }
    }

    @Test
    void localDatesCompareAsTheirTextEvenAcrossAnAutumnOverlap() throws Exception {
        // 26 October 2025 in Paris: 02:00 to 03:00 happens twice, first at UTC+2 then at UTC+1
        PostSnapshot first0230 = postAt("2025-10-26T00:30:00Z");
        PostSnapshot second0210 = postAt("2025-10-26T01:10:00Z");
        PostSnapshot second0240 = postAt("2025-10-26T01:40:00Z");
        DateBound twoTwenty = DateBound.exact(at("2025-10-26 02:20:00"));
        assertThat(query(
                        DateCondition.after(DateColumn.DATE, twoTwenty, false),
                        DateCondition.before(DateColumn.DATE, DateBound.month(2025, 10), true)))
                .containsExactly(second0240.id(), first0230.id());
        assertThat(query(DateCondition.before(DateColumn.DATE, twoTwenty, false)))
                .containsExactly(second0210.id());
    }

    @Test
    void aLocalBoundInASpringGapSplitsAtTheTransition() throws Exception {
        // 29 March 2026 in Paris: 02:00 to 03:00 does not happen
        PostSnapshot beforeGap = postAt("2026-03-29T00:59:59Z");
        PostSnapshot afterGap = postAt("2026-03-29T01:00:00Z");
        DateBound inGap = DateBound.exact(at("2026-03-29 02:30:00"));
        assertThat(query(DateCondition.after(DateColumn.DATE, inGap, true))).containsExactly(afterGap.id());
        assertThat(query(DateCondition.between(DateColumn.DATE, DateBound.month(2026, 2), inGap, false)))
                .containsExactly(beforeGap.id());
    }

    @Test
    void aFloatingDraftCountsAsDatedBeforeAnyGmtDate() throws Exception {
        PostSnapshot draft = Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder().title("Floating").build()));
        PostSnapshot dated = Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title("Dated")
                        .dateGmt(Instant.parse("2026-01-01T00:00:00Z"))
                        .build()));
        assertThat(drafts(DateCondition.before(DateColumn.DATE_GMT, DateBound.year(2000), false)))
                .containsExactly(draft.id());
        assertThat(drafts(DateCondition.after(DateColumn.DATE_GMT, DateBound.year(2000), true)))
                .containsExactly(dated.id());
        assertThat(drafts(DateCondition.after(DateColumn.DATE_GMT, DateBound.year(500), false)))
                .containsExactly(dated.id());
        assertThat(drafts(DateCondition.after(DateColumn.DATE_GMT, DateBound.year(0), true)))
                .containsExactly(dated.id());
    }

    @Test
    void aFloatingDraftsLocalDateIsItsModificationTimeInTheSiteZone() throws Exception {
        clock.set(Instant.parse("2026-01-20T10:00:00Z"));
        PostSnapshot draft = Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder().title("Floating").build()));
        assertThat(draft.publishedAt()).isEmpty();
        // 10:00 UTC is 11:00 in Paris
        assertThat(drafts(DateCondition.after(DateColumn.DATE, DateBound.exact(at("2026-01-20 10:59:59")), false)))
                .containsExactly(draft.id());
        assertThat(drafts(DateCondition.after(DateColumn.DATE, DateBound.exact(at("2026-01-20 11:00:00")), false)))
                .isEmpty();
        assertThat(drafts(DateCondition.before(DateColumn.DATE, DateBound.exact(at("2026-01-20 11:00:00")), true)))
                .containsExactly(draft.id());
        assertThat(drafts(DateCondition.after(DateColumn.MODIFIED, DateBound.minute(at("2026-01-20 10:59:00")), false)))
                .containsExactly(draft.id());
        assertThat(drafts(DateCondition.after(
                        DateColumn.MODIFIED_GMT, DateBound.minute(at("2026-01-20 10:59:00")), false)))
                .isEmpty();
    }

    @Test
    void anOutOfRangeBoundKeepsEveryOrNoStoredDate() throws Exception {
        assertThat(query(DateCondition.after(DateColumn.DATE, DateBound.year(0), true)))
                .containsExactly(feb1.id(), jan15late.id(), jan15noon.id(), jan1.id(), dec31.id());
        assertThat(query(DateCondition.after(DateColumn.DATE, DateBound.year(9999), false)))
                .isEmpty();
        assertThat(query(DateCondition.before(DateColumn.DATE, DateBound.year(9999), true)))
                .containsExactly(feb1.id(), jan15late.id(), jan15noon.id(), jan1.id(), dec31.id());
        assertThat(query(DateCondition.before(DateColumn.DATE_GMT, DateBound.year(500), true)))
                .isEmpty();
    }

    private List<PostId> drafts(DateCondition condition) throws Exception {
        return Identities.onSite(
                        site,
                        Identities.system(),
                        () -> posts.query(PostQuery.builder()
                                .statuses("draft")
                                .date(condition)
                                .build()))
                .posts()
                .stream()
                .map(PostSnapshot::id)
                .toList();
    }
}
