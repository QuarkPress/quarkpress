package org.quarkword.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.OptionalInt;
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
import org.quarkword.posts.PostQuery.DateUnits;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.Posts;

/**
 * With {@code quarkword.posts.query-date-spans=0}, every week and unit condition compares the local date the database
 * computes: the rows of {@code DateUnitsQueryDbTest} and {@code WeekQueryDbTest} (MySQL 9.5's {@code WEEK} numbers),
 * in Paris across summer time, and on a site with a fixed offset.
 */
class DateUnitsInSqlDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(DateUnitsInSqlDbTest.class, TestClock.class)
            .overrideConfigKey("quarkword.posts.query-date-spans", "0");

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TestClock clock;

    SiteId site;
    PostSnapshot may20;
    PostSnapshot may31of2025;
    PostSnapshot newYear;
    PostSnapshot autumnOverlap;
    PostSnapshot autumnOverlapLater;
    PostSnapshot march31;

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
        clock.set(Instant.parse("2027-06-01T10:00:00Z"));
        site = TestData.siteIn(dataSource, "Europe/Paris");
        may20 = post("2026-05-20 12:00:00");
        may31of2025 = post("2025-05-31 08:15:05");
        newYear = post("2026-01-01 00:30:00");
        autumnOverlap = postAt("2026-10-25T00:30:00Z");
        autumnOverlapLater = postAt("2026-10-25T01:30:00Z");
        march31 = post("2026-03-31 23:59:59");
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

    private static OptionalInt unit(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static PostQuery.Builder units(
            Integer year, Integer month, Integer day, Integer hour, Integer minute, Integer second) {
        return PostQuery.builder()
                .units(new DateUnits(unit(year), unit(month), unit(day), unit(hour), unit(minute), unit(second)));
    }

    private static DateCondition year(int year) {
        return DateCondition.between(DateColumn.DATE, DateBound.year(year), DateBound.year(year), true);
    }

    @Test
    void dateUnitsCompareTheLocalDate() throws Exception {
        assertThat(query(units(null, 5, null, null, null, null))).containsExactly(may20.id(), may31of2025.id());
        assertThat(query(units(null, 12, null, null, null, null))).isEmpty();
        assertThat(query(units(null, null, 31, null, null, null))).containsExactly(march31.id(), may31of2025.id());
        assertThat(query(units(null, null, 1, null, null, null))).containsExactly(newYear.id());
        assertThat(query(units(2026, null, 31, null, null, null))).containsExactly(march31.id());
        assertThat(query(units(null, 5, 20, null, null, null))).containsExactly(may20.id());
        assertThat(query(units(null, 2, 31, null, null, null))).isEmpty();
        assertThat(query(units(null, 13, null, null, null, null))).isEmpty();
        assertThat(query(units(null, null, 40, null, null, null))).isEmpty();
    }

    @Test
    void timeUnitsCompareTheLocalTime() throws Exception {
        assertThat(query(units(null, null, null, 0, null, null))).containsExactly(newYear.id());
        assertThat(query(units(null, null, null, 23, null, null))).containsExactly(march31.id());
        // both instants of the repeated 02:30 have the hour 2
        assertThat(query(units(null, null, null, 2, null, null)))
                .containsExactly(autumnOverlapLater.id(), autumnOverlap.id());
        assertThat(query(units(null, null, null, 2, 30, null)))
                .containsExactly(autumnOverlapLater.id(), autumnOverlap.id());
        assertThat(query(units(null, null, null, 8, 15, null))).containsExactly(may31of2025.id());
        assertThat(query(units(null, null, null, 8, 16, null))).isEmpty();
        assertThat(query(units(null, null, null, null, 15, 5))).containsExactly(may31of2025.id());
        assertThat(query(units(null, null, null, null, null, 5))).containsExactly(may31of2025.id());
        assertThat(query(units(null, null, null, null, 59, null))).containsExactly(march31.id());
        assertThat(query(units(2026, null, null, 12, null, null))).containsExactly(may20.id());
        assertThat(query(units(null, null, 20, 8, null, null))).isEmpty();
        assertThat(query(units(null, null, null, 24, null, null))).isEmpty();
        assertThat(query(units(null, null, null, null, 60, null))).isEmpty();
    }

    @Test
    void weeksCountAsMySqlCountsThem() throws Exception {
        PostSnapshot may10 = post("2026-05-10 12:00:00");
        PostSnapshot may21of2025 = post("2025-05-21 12:00:00");
        PostSnapshot jan4 = post("2026-01-04 00:30:00");
        PostSnapshot jan3late = post("2026-01-03 23:30:00");
        PostSnapshot jan1of2027 = post("2027-01-01 08:00:00");
        // WEEK(..., 1): 2026-05-20 is 21, 2025-05-21 is 21; 2026-05-10 is 19
        assertThat(query(PostQuery.builder().week(21, 1))).containsExactly(may20.id(), may21of2025.id());
        assertThat(query(PostQuery.builder().week(19, 1))).containsExactly(may10.id());
        // WEEK(..., 0): 4 January 2026 (a Sunday) is 1, 1 to 3 January 0
        assertThat(query(PostQuery.builder().week(20, 0))).containsExactly(may20.id(), may21of2025.id());
        assertThat(query(PostQuery.builder().week(1, 0).date(year(2026)))).containsExactly(jan4.id());
        assertThat(query(PostQuery.builder().week(0, 0).date(year(2026)))).containsExactly(jan3late.id(), newYear.id());
        // start_of_week 3: 4 January counted as 1 January is week 0; 1 and 3 January, counted as 29 and 31 December
        // 2025, are week 52
        assertThat(query(PostQuery.builder().week(0, 3).date(year(2026)))).containsExactly(jan4.id());
        assertThat(query(PostQuery.builder().week(52, 3).date(year(2026))))
                .containsExactly(jan3late.id(), newYear.id());
        // WEEK('2027-01-01', 1) = 0; WEEK('2026-01-01 00:30', 1) = 1 in Paris (31 December 2025 in UTC is 53)
        assertThat(query(PostQuery.builder().week(0, 1))).containsExactly(jan1of2027.id());
        assertThat(query(PostQuery.builder().week(1, 1).date(year(2026))))
                .containsExactly(jan4.id(), jan3late.id(), newYear.id());
        assertThat(query(PostQuery.builder().week(54, 1))).isEmpty();
        assertThat(query(PostQuery.builder().week(-1, 1))).isEmpty();
    }

    @Test
    void aFixedOffsetIsTheLocalDate() throws Exception {
        site = TestData.site(dataSource);
        TestData.siteOption(dataSource, site, "gmt_offset", "5.5");
        // 02:00 on 1 January 2026 at +05:30 is 20:30 UTC on 31 December 2025
        PostSnapshot early = post("2026-01-01 02:00:00");
        PostSnapshot late = post("2026-01-01 23:30:00");
        assertThat(query(units(null, null, 1, null, null, null))).containsExactly(late.id(), early.id());
        assertThat(query(units(null, 1, null, 2, 0, null))).containsExactly(early.id());
        assertThat(query(units(2026, null, null, 23, 30, null))).containsExactly(late.id());
    }
}
