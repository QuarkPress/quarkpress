package org.quarkword.posts.deployment;

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
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.posts.DateBound;
import org.quarkword.posts.DateColumn;
import org.quarkword.posts.DateCondition;
import org.quarkword.posts.PostChanges;
import org.quarkword.posts.PostQuery;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.Posts;

/**
 * Week conditions on a site in Paris, compared on the local post date as WordPress compares {@code WEEK(post_date,
 * mode)}: the expected numbers are MySQL 9.5's (see {@code WeeksTest}).
 */
class WeekQueryDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(WeekQueryDbTest.class, TestClock.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TestClock clock;

    SiteId site;
    PostSnapshot may20;
    PostSnapshot may10;
    PostSnapshot may21of2025;
    PostSnapshot jan4;
    PostSnapshot jan3late;
    PostSnapshot jan1of2027;

    private PostSnapshot post(String date) throws Exception {
        return Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title(date)
                        .status("publish")
                        .date(LocalDateTime.parse(date.replace(' ', 'T')))
                        .build()));
    }

    @BeforeEach
    void setUp() throws Exception {
        clock.set(Instant.parse("2027-06-01T10:00:00Z"));
        site = TestData.siteIn(dataSource, "Europe/Paris");
        may20 = post("2026-05-20 12:00:00");
        may10 = post("2026-05-10 12:00:00");
        may21of2025 = post("2025-05-21 12:00:00");
        jan4 = post("2026-01-04 00:30:00");
        // 23:30 local on 3 January is 22:30 UTC: the local day decides
        jan3late = post("2026-01-03 23:30:00");
        jan1of2027 = post("2027-01-01 08:00:00");
    }

    private List<PostId> query(PostQuery.Builder query) throws Exception {
        return Identities.onSite(
                        site,
                        Identities.system(),
                        () -> posts.query(query.perPage(-1).build()))
                .posts()
                .stream()
                .map(PostSnapshot::id)
                .toList();
    }

    private static DateCondition year(int year) {
        return DateCondition.between(DateColumn.DATE, DateBound.year(year), DateBound.year(year), true);
    }

    @Test
    void aWeekWithAYearUnderAMondayStart() throws Exception {
        // WEEK('2026-05-20', 1) = 21, WEEK('2026-05-10', 1) = 19
        assertThat(query(PostQuery.builder().week(21, 1).date(year(2026)))).containsExactly(may20.id());
    }

    @Test
    void aWeekAloneMatchesThatWeekInEveryYear() throws Exception {
        // ISO week 21 of 2025 is 19-25 May
        assertThat(query(PostQuery.builder().week(21, 1))).containsExactly(may20.id(), may21of2025.id());
    }

    @Test
    void aSundayStartCountsWeeksFromTheFirstSunday() throws Exception {
        // WEEK('2026-05-20', 0) = 20 and WEEK('2025-05-21', 0) = 20; WEEK('2026-01-04', 0) = 1, and 3 January (a
        // Saturday) is week 0
        assertThat(query(PostQuery.builder().week(20, 0))).containsExactly(may20.id(), may21of2025.id());
        assertThat(query(PostQuery.builder().week(1, 0).date(year(2026)))).containsExactly(jan4.id());
        assertThat(query(PostQuery.builder().week(0, 0).date(year(2026)))).containsExactly(jan3late.id());
    }

    @Test
    void aShiftedStartCountsTheShiftedDate() throws Exception {
        // WEEK(DATE_SUB('2026-01-04', INTERVAL 3 DAY), 0) = 0: under start_of_week 3, 4 January is week 0 and
        // 3 January, counted as 31 December 2025, is week 52
        assertThat(query(PostQuery.builder().week(0, 3).date(year(2026)))).containsExactly(jan4.id());
        assertThat(query(PostQuery.builder().week(52, 3).date(year(2026)))).containsExactly(jan3late.id());
        // WEEK('2027-01-01', 1) = 0
        assertThat(query(PostQuery.builder().week(0, 1))).containsExactly(jan1of2027.id());
    }

    @Test
    void aWeekNoDateHasMatchesNothing() throws Exception {
        assertThat(query(PostQuery.builder().week(54, 1))).isEmpty();
        assertThat(query(PostQuery.builder().week(-1, 1))).isEmpty();
    }
}
