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
 * Unit equality on a site in Paris, compared on the local post date as WordPress compares {@code MONTH(post_date) =
 * 5}, {@code DAYOFMONTH(post_date) = 31}, {@code HOUR(post_date) = 8} and the time formats (main-query.out, FQ13):
 * whatever the units above the given ones, and in the site's zone, across summer time.
 */
class DateUnitsQueryDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(DateUnitsQueryDbTest.class, TestClock.class);

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
        // 00:30 local on 1 January is 23:30 UTC on 31 December: the local day and hour decide
        newYear = post("2026-01-01 00:30:00");
        // 25 October 2026: 02:30 local happens twice in Paris, at 00:30 and at 01:30 UTC
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

    @Test
    void aMonthAloneMatchesThatMonthInEveryYear() throws Exception {
        // MONTH(post_date) = 5
        assertThat(query(units(null, 5, null, null, null, null))).containsExactly(may20.id(), may31of2025.id());
        assertThat(query(units(null, 3, null, null, null, null))).containsExactly(march31.id());
        assertThat(query(units(null, 12, null, null, null, null))).isEmpty();
    }

    @Test
    void aDayAloneMatchesThatDayInEveryMonth() throws Exception {
        // DAYOFMONTH(post_date) = 31: 31 December 23:30 UTC is 1 January in Paris
        assertThat(query(units(null, null, 31, null, null, null))).containsExactly(march31.id(), may31of2025.id());
        assertThat(query(units(null, null, 1, null, null, null))).containsExactly(newYear.id());
        assertThat(query(units(2026, null, 31, null, null, null))).containsExactly(march31.id());
        assertThat(query(units(null, 5, 20, null, null, null))).containsExactly(may20.id());
        // MONTH = 2 AND DAYOFMONTH = 31, MONTH = 13, DAYOFMONTH = 40: no day has them
        assertThat(query(units(null, 2, 31, null, null, null))).isEmpty();
        assertThat(query(units(null, 13, null, null, null, null))).isEmpty();
        assertThat(query(units(null, null, 40, null, null, null))).isEmpty();
    }

    @Test
    void timeUnitsCompareTheLocalTime() throws Exception {
        // HOUR(post_date) = 0 and = 23: the local hour
        assertThat(query(units(null, null, null, 0, null, null))).containsExactly(newYear.id());
        assertThat(query(units(null, null, null, 23, null, null))).containsExactly(march31.id());
        // both instants of the repeated 02:30 have the hour 2
        assertThat(query(units(null, null, null, 2, null, null)))
                .containsExactly(autumnOverlapLater.id(), autumnOverlap.id());
        assertThat(query(units(null, null, null, 2, 30, null)))
                .containsExactly(autumnOverlapLater.id(), autumnOverlap.id());
        // DATE_FORMAT(post_date, '%H.%i') = 08.15, DATE_FORMAT(post_date, '0.%i%s') = 0.1505, SECOND(post_date) = 5
        assertThat(query(units(null, null, null, 8, 15, null))).containsExactly(may31of2025.id());
        assertThat(query(units(null, null, null, 8, 16, null))).isEmpty();
        assertThat(query(units(null, null, null, null, 15, 5))).containsExactly(may31of2025.id());
        assertThat(query(units(null, null, null, null, null, 5))).containsExactly(may31of2025.id());
        assertThat(query(units(null, null, null, null, 59, null))).containsExactly(march31.id());
        // with the date units: YEAR = 2026 AND HOUR = 12; DAYOFMONTH = 20 AND HOUR = 8
        assertThat(query(units(2026, null, null, 12, null, null))).containsExactly(may20.id());
        assertThat(query(units(null, null, 20, 8, null, null))).isEmpty();
        // HOUR = 24, MINUTE = 60
        assertThat(query(units(null, null, null, 24, null, null))).isEmpty();
        assertThat(query(units(null, null, null, null, 60, null))).isEmpty();
    }

    @Test
    void unitsAreOneMoreConditionNextToTheOthers() throws Exception {
        DateCondition year2026 =
                DateCondition.between(DateColumn.DATE, DateBound.year(2026), DateBound.year(2026), true);
        assertThat(query(units(null, 5, null, null, null, null).date(year2026))).containsExactly(may20.id());
        // WEEK(post_date, 1) = 22 holds 31 May 2025 (a Saturday) and not 20 May 2026
        assertThat(query(units(null, 5, null, null, null, null).week(22, 1))).containsExactly(may31of2025.id());
    }
}
