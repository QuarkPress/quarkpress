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
import org.quarkword.posts.PostChanges;
import org.quarkword.posts.PostQuery;
import org.quarkword.posts.PostQuery.DateUnits;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.posts.Posts;

/**
 * Week and unit conditions stay small statements whatever the dates of the site's posts: drafts dated 1000-01-01 and
 * 9999-12-31 do not widen the years a query for published posts looks through (WordPress's {@code ?day=1},
 * {@code ?monthnum=5}, {@code ?w=1}, {@code ?minute=0}), and a published post six centuries older than the others
 * makes the condition compare the local date in SQL, with the same rows (see {@code DateUnitsInSqlDbTest} for every
 * unit compared in SQL).
 */
class DateUnitsBoundDbTest {

    /** Far below the 512 ranges of dates a condition is written as at most, four placeholders each. */
    private static final int MOST_PLACEHOLDERS = 4 * 512 + 100;

    @RegisterExtension
    static final QuarkusExtensionTest app =
            PostsApp.with(DateUnitsBoundDbTest.class, TestClock.class, SqlPlaceholders.class);

    @Inject
    @DataSource("quarkword")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    TestClock clock;

    SiteId site;
    PostSnapshot newYear;
    PostSnapshot dec31;
    PostSnapshot may20;
    PostSnapshot autumnOverlap;
    PostSnapshot autumnOverlapLater;

    private PostSnapshot post(String date, String status) throws Exception {
        return Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title(date)
                        .status(status)
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
        post("1000-01-01 12:00:00", "draft");
        post("9999-12-31 12:00:00", "draft");
        // 00:30 local on 1 January 2026 is 23:30 UTC on 31 December 2025: week 1 of 2026 only in the site's zone
        newYear = post("2026-01-01 00:30:00", "publish");
        // week 53 of 2025: a week is never counted in the next year
        dec31 = post("2025-12-31 12:00:00", "publish");
        may20 = post("2026-05-20 12:00:00", "publish");
        // 25 October 2026: 02:30 local happens twice in Paris, at 00:30 and at 01:30 UTC
        autumnOverlap = postAt("2026-10-25T00:30:00Z");
        autumnOverlapLater = postAt("2026-10-25T01:30:00Z");
    }

    private List<PostId> query(PostQuery.Builder query) throws Exception {
        SqlPlaceholders.reset();
        List<PostId> found =
                Identities.onSite(site, Identities.system(), () -> posts.query(query.build())).posts().stream()
                        .map(PostSnapshot::id)
                        .toList();
        assertThat(SqlPlaceholders.most()).isBetween(1, MOST_PLACEHOLDERS);
        return found;
    }

    private static OptionalInt unit(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    private static PostQuery.Builder units(Integer month, Integer day, Integer hour, Integer minute) {
        return PostQuery.builder()
                .units(new DateUnits(
                        OptionalInt.empty(), unit(month), unit(day), unit(hour), unit(minute), OptionalInt.empty()));
    }

    @Test
    void draftsOfAnyDateDoNotWidenAQueryForPublishedPosts() throws Exception {
        assertThat(query(units(null, 1, null, null))).containsExactly(newYear.id());
        assertThat(query(units(5, null, null, null))).containsExactly(may20.id());
        assertThat(query(PostQuery.builder().week(1, 1))).containsExactly(newYear.id());
        assertThat(query(units(null, null, null, 0))).containsExactly(may20.id(), dec31.id());
        assertThat(query(units(null, null, 2, null))).containsExactly(autumnOverlapLater.id(), autumnOverlap.id());
    }

    @Test
    void centuriesOfPublishedPostsCompareTheLocalDateInSql() throws Exception {
        PostSnapshot old = post("1600-05-01 12:00:00", "publish");
        // 427 years from 1600 to 2026: one range a month (?day=1) is too many, the database compares the local day
        assertThat(query(units(null, 1, null, null))).containsExactly(newYear.id(), old.id());
        // one range a year (?monthnum=5, ?w=1) is still within the 512
        assertThat(query(units(5, null, null, null))).containsExactly(may20.id(), old.id());
        assertThat(query(units(5, 1, null, null))).containsExactly(old.id());
        assertThat(query(PostQuery.builder().week(1, 1))).containsExactly(newYear.id());
        assertThat(query(PostQuery.builder().week(53, 1))).containsExactly(dec31.id());
        assertThat(query(units(null, null, null, 0))).containsExactly(may20.id(), dec31.id(), old.id());
        assertThat(query(units(null, null, 2, null))).containsExactly(autumnOverlapLater.id(), autumnOverlap.id());
    }

    @Test
    void aQueryOfNearlyTheMostRangesIsStillWrittenAsRanges() throws Exception {
        // 42 years from 1985 to 2026: 504 ranges of one day for ?day=1, within the 512 a condition is written as
        PostSnapshot older = post("1985-05-01 12:00:00", "publish");
        assertThat(query(units(null, 1, null, null))).containsExactly(newYear.id(), older.id());
        assertThat(SqlPlaceholders.most()).isGreaterThan(1000);
    }
}
