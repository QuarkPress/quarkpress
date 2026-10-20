package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.posts.PostException;

class PostDatesTest {

    static final ZoneId PARIS = ZoneId.of("Europe/Paris");
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    static final Instant NOW = Instant.parse("2026-01-15T12:00:00Z");

    private static LocalDateTime local(String text) {
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    private static Instant gmt(String text) {
        return Instant.parse(text.replace(' ', 'T') + "Z");
    }

    private static PostDates.Resolved insert(String date, String dateGmt, boolean floating) {
        return PostDates.resolve(
                Optional.ofNullable(date).map(PostDatesTest::local),
                Optional.ofNullable(dateGmt).map(PostDatesTest::gmt),
                floating,
                false,
                NOW,
                PARIS);
    }

    @Test
    void aDraftWithoutDatesFloatsAndIsDatedNow() {
        PostDates.Resolved dates = insert(null, null, true);
        assertThat(dates.publishedAt()).isEmpty();
        assertThat(dates.modifiedAt()).isEqualTo(NOW);
        assertThat(PostDates.localDate(dates.publishedAt(), dates.modifiedAt(), PARIS))
                .isEqualTo(local("2026-01-15 13:00:00"));
    }

    @Test
    void aDraftWithALocalDateKeepsItAsItsLocalDateAndModificationDate() {
        PostDates.Resolved dates = insert("2026-03-01 09:00:00", null, true);
        assertThat(dates.publishedAt()).isEmpty();
        assertThat(dates.modifiedAt()).isEqualTo(gmt("2026-03-01 08:00:00"));
        assertThat(PostDates.localDate(dates.publishedAt(), dates.modifiedAt(), PARIS))
                .isEqualTo(local("2026-03-01 09:00:00"));
    }

    @Test
    void aDraftGivenBothDatesKeepsTheGmtOne() {
        assertThat(insert("2026-03-01 09:00:00", "2026-03-01 08:00:00", true).publishedAt())
                .contains(gmt("2026-03-01 08:00:00"));
    }

    @Test
    void aDraftGivenOnlyTheGmtDateIsShownAtThatDateInTheZone() {
        PostDates.Resolved dates = insert(null, "2026-01-10 08:00:00", true);
        assertThat(dates.publishedAt()).contains(gmt("2026-01-10 08:00:00"));
        assertThat(PostDates.localDate(dates.publishedAt(), dates.modifiedAt(), PARIS))
                .isEqualTo(local("2026-01-10 09:00:00"));
    }

    @Test
    void aPublishedPostWithoutDatesIsDatedNowAndModifiedThen() {
        PostDates.Resolved dates = insert(null, null, false);
        assertThat(dates.publishedAt()).contains(NOW);
        assertThat(dates.modifiedAt()).isEqualTo(NOW);
    }

    @Test
    void anInsertedPostIsModifiedAtItsPostDate() {
        PostDates.Resolved dates = insert("2020-05-05 10:00:00", null, false);
        assertThat(dates.publishedAt()).contains(gmt("2020-05-05 08:00:00"));
        assertThat(dates.modifiedAt()).isEqualTo(gmt("2020-05-05 08:00:00"));
    }

    @Test
    void theGmtDateWinsWhenBothDisagree() {
        PostDates.Resolved dates = insert("2026-01-10 10:00:00", "2026-01-10 03:00:00", false);
        assertThat(dates.publishedAt()).contains(gmt("2026-01-10 03:00:00"));
        assertThat(PostDates.localDate(dates.publishedAt(), dates.modifiedAt(), PARIS))
                .isEqualTo(local("2026-01-10 04:00:00"));
    }

    @Test
    void summerTimeUsesTheSummerOffset() {
        assertThat(insert("2026-07-01 12:00:00", null, false).publishedAt()).contains(gmt("2026-07-01 10:00:00"));
    }

    @Test
    void anUpdateIsModifiedNow() {
        PostDates.Resolved dates =
                PostDates.resolve(Optional.of(local("2020-05-05 10:00:00")), Optional.empty(), false, true, NOW, PARIS);
        assertThat(dates.publishedAt()).contains(gmt("2020-05-05 08:00:00"));
        assertThat(dates.modifiedAt()).isEqualTo(NOW);
    }

    @Test
    void localTimesInDaylightSavingTransitionsResolveAsWordPressDoes() {
        assertThat(PostDates.toInstant(local("2024-03-10 02:30:00"), NEW_YORK)).isEqualTo(gmt("2024-03-10 07:30:00"));
        assertThat(PostDates.toInstant(local("2024-11-03 01:30:00"), NEW_YORK)).isEqualTo(gmt("2024-11-03 05:30:00"));
        assertThat(PostDates.toInstant(local("2026-01-15 10:00:00"), ZoneOffset.ofHoursMinutes(5, 30)))
                .isEqualTo(gmt("2026-01-15 04:30:00"));
        assertThat(PostDates.toLocal(gmt("2026-01-15 04:30:00"), ZoneOffset.ofHoursMinutes(5, 30)))
                .isEqualTo(local("2026-01-15 10:00:00"));
    }

    @Test
    void anOverlapTimeTakesTheOffsetInForceAtTheSameDigitsInUtc() {
        assertThat(PostDates.toInstant(local("2024-10-27 01:30:00"), ZoneId.of("Europe/London")))
                .isEqualTo(gmt("2024-10-27 01:30:00"));
        assertThat(PostDates.toInstant(local("2024-10-27 02:30:00"), PARIS)).isEqualTo(gmt("2024-10-27 01:30:00"));
        assertThat(PostDates.toInstant(local("2024-04-07 02:30:00"), ZoneId.of("Australia/Sydney")))
                .isEqualTo(gmt("2024-04-06 16:30:00"));
        assertThat(PostDates.toInstant(local("2024-04-07 02:30:00"), ZoneId.of("Pacific/Auckland")))
                .isEqualTo(gmt("2024-04-06 14:30:00"));
        assertThat(PostDates.toInstant(local("2024-03-31 01:30:00"), ZoneId.of("Europe/London")))
                .isEqualTo(gmt("2024-03-31 01:30:00"));
    }

    @Test
    void negativeAndFractionalFixedOffsetsConvertBothWays() {
        ZoneOffset plus545 = ZoneOffset.ofHoursMinutes(5, 45);
        ZoneOffset minus330 = ZoneOffset.ofHoursMinutes(-3, -30);
        assertThat(PostDates.toInstant(local("2026-01-15 10:00:00"), plus545)).isEqualTo(gmt("2026-01-15 04:15:00"));
        assertThat(PostDates.toLocal(gmt("2026-01-15 04:15:00"), plus545)).isEqualTo(local("2026-01-15 10:00:00"));
        assertThat(PostDates.toInstant(local("2026-01-15 10:00:00"), minus330)).isEqualTo(gmt("2026-01-15 13:30:00"));
        assertThat(PostDates.toLocal(gmt("2026-01-15 13:30:00"), minus330)).isEqualTo(local("2026-01-15 10:00:00"));
    }

    @Test
    void aLocalDateConvertedOutsideTheStorableYearsIsRefused() {
        assertThatThrownBy(() -> PostDates.resolve(
                        Optional.of(local("9999-12-31 23:00:00")), Optional.empty(), false, false, NOW, NEW_YORK))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_date"));
    }

    @Test
    void theStorableYearBoundsAreAccepted() {
        assertThat(PostDates.resolve(
                                Optional.of(local("1000-01-01 00:00:00")),
                                Optional.empty(),
                                false,
                                false,
                                NOW,
                                ZoneOffset.UTC)
                        .publishedAt())
                .contains(gmt("1000-01-01 00:00:00"));
        assertThat(PostDates.resolve(
                                Optional.of(local("9999-12-31 23:59:59")),
                                Optional.empty(),
                                false,
                                false,
                                NOW,
                                ZoneOffset.UTC)
                        .publishedAt())
                .contains(gmt("9999-12-31 23:59:59"));
        assertThat(PostDates.resolve(
                                Optional.empty(),
                                Optional.of(gmt("9999-12-31 23:59:59")),
                                false,
                                false,
                                NOW,
                                ZoneOffset.UTC)
                        .publishedAt())
                .contains(gmt("9999-12-31 23:59:59"));
    }

    @Test
    void nowIsTruncatedToTheSecond() {
        assertThat(PostDates.now(() -> Instant.parse("2026-01-15T12:00:00.900Z")))
                .isEqualTo(NOW);
    }

    @Test
    void exceptionMessagesAreWordPressesAndKeepTheDetailApart() {
        assertThat(PostException.invalidPost(new org.quarkpress.core.PostId(7)).getMessage())
                .isEqualTo("Invalid post ID.");
        PostException insert = PostException.dbError(false, "Data too long");
        assertThat(insert.code()).isEqualTo("db_insert_error");
        assertThat(insert.getMessage()).isEqualTo("Could not insert post into the database.");
        assertThat(insert.detail()).isEqualTo("Data too long");
        assertThat(PostException.dbError(true, "x").getMessage()).isEqualTo("Could not update post in the database.");
    }

    @Test
    void datesAreKeptToTheSecond() {
        assertThat(PostDates.toInstant(LocalDateTime.of(2026, 1, 15, 10, 0, 0, 700_000_000), ZoneOffset.UTC))
                .isEqualTo(gmt("2026-01-15 10:00:00"));
        assertThat(PostDates.format(local("2026-01-05 09:05:00"))).isEqualTo("2026-01-05 09:05:00");
    }

    @Test
    void publishInTheFutureBecomesFutureFromSixtySecondsOn() {
        assertThat(coerce("publish", "2026-01-15 12:30:00")).isEqualTo("future");
        assertThat(coerce("publish", "2026-01-15 12:01:00")).isEqualTo("future");
        assertThat(coerce("publish", "2026-01-15 12:00:59")).isEqualTo("publish");
        assertThat(coerce("publish", "2026-01-15 11:00:00")).isEqualTo("publish");
    }

    @Test
    void futureInThePastOrWithoutDateBecomesPublish() {
        assertThat(coerce("future", "2026-01-14 09:00:00")).isEqualTo("publish");
        assertThat(PostDates.coerceStatus("future", Optional.empty(), NOW, false))
                .isEqualTo("publish");
        assertThat(coerce("future", "2026-01-15 12:01:00")).isEqualTo("future");
        assertThat(coerce("future", "2026-01-15 12:00:59")).isEqualTo("publish");
        assertThat(coerce("future", "2026-01-15 12:00:00")).isEqualTo("publish");
    }

    @Test
    void otherStatusesAndAttachmentsKeepAnyDate() {
        assertThat(coerce("private", "2027-01-01 00:00:00")).isEqualTo("private");
        assertThat(coerce("draft", "2027-01-01 00:00:00")).isEqualTo("draft");
        assertThat(coerce("approved", "2027-01-01 00:00:00")).isEqualTo("approved");
        assertThat(PostDates.coerceStatus("inherit", Optional.of(gmt("2027-01-01 00:00:00")), NOW, true))
                .isEqualTo("inherit");
        assertThat(PostDates.coerceStatus("publish", Optional.of(gmt("2027-01-01 00:00:00")), NOW, true))
                .isEqualTo("publish");
    }

    @Test
    void yearsOutsideWhatBothDatabasesStoreAreRefused() {
        assertThatThrownBy(() -> insert("0999-12-31 23:59:59", null, false))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_date"))
                .hasMessage("Invalid date.");
        assertThatThrownBy(() -> PostDates.resolve(
                        Optional.empty(),
                        Optional.of(Instant.parse("+10000-01-01T00:00:00Z")),
                        false,
                        false,
                        NOW,
                        PARIS))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_date"));
    }

    private static String coerce(String status, String gmtDate) {
        return PostDates.coerceStatus(status, Optional.of(gmt(gmtDate)), NOW, false);
    }
}
