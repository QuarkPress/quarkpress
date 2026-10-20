package org.quarkpress.posts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class DateBoundTest {

    private static LocalDateTime at(String text) {
        return LocalDateTime.parse(text.replace(' ', 'T'));
    }

    private static OptionalInt of(int value) {
        return OptionalInt.of(value);
    }

    private static final OptionalInt NONE = OptionalInt.empty();

    @Test
    void partialBoundsArePaddedToTheStartOrTheEndOfTheirPeriod() {
        assertThat(DateBound.month(2026, 1).start()).isEqualTo(at("2026-01-01 00:00:00"));
        assertThat(DateBound.month(2026, 1).end()).isEqualTo(at("2026-01-31 23:59:59"));
        assertThat(DateBound.month(2028, 2).end()).isEqualTo(at("2028-02-29 23:59:59"));
        assertThat(DateBound.year(2026).end()).isEqualTo(at("2026-12-31 23:59:59"));
        assertThat(DateBound.day(2026, 1, 15).start()).isEqualTo(at("2026-01-15 00:00:00"));
        assertThat(DateBound.day(2026, 1, 15).end()).isEqualTo(at("2026-01-15 23:59:59"));
        assertThat(DateBound.minute(at("2026-01-15 10:30:42")).end()).isEqualTo(at("2026-01-15 10:30:59"));
        assertThat(DateBound.exact(at("2026-01-15 10:30:00")).start()).isEqualTo(at("2026-01-15 10:30:00"));
        assertThat(DateBound.exact(at("2026-01-15 10:30:00")).end()).isEqualTo(at("2026-01-15 10:30:00"));
    }

    @Test
    void missingFieldsArePaddedOneByOneEvenBetweenGivenOnes() {
        DateBound dayWithoutMonth = new DateBound(2026, NONE, of(30), NONE, NONE, NONE);
        assertThat(dayWithoutMonth.start()).isEqualTo(at("2026-01-30 00:00:00"));
        assertThat(dayWithoutMonth.end()).isEqualTo(at("2026-12-30 23:59:59"));
        DateBound hourOnly = new DateBound(2026, NONE, NONE, of(5), NONE, NONE);
        assertThat(hourOnly.start()).isEqualTo(at("2026-01-01 05:00:00"));
        assertThat(hourOnly.end()).isEqualTo(at("2026-12-31 05:59:59"));
    }

    @Test
    void digitsNamingNoRealTimeStandForTheFirstRealTimeWhoseTextSortsAfterThem() {
        assertThat(DateBound.day(2026, 2, 30).start()).isEqualTo(at("2026-03-01 00:00:00"));
        assertThat(DateBound.day(2026, 2, 30).end()).isEqualTo(at("2026-03-01 00:00:00"));
        assertThat(new DateBound(2026, of(13), NONE, NONE, NONE, NONE).start()).isEqualTo(at("2027-01-01 00:00:00"));
        assertThat(new DateBound(2026, of(0), NONE, NONE, NONE, NONE).end()).isEqualTo(at("2026-01-01 00:00:00"));
        assertThat(new DateBound(2026, of(3), of(0), NONE, NONE, NONE).start()).isEqualTo(at("2026-03-01 00:00:00"));
        assertThat(new DateBound(2026, of(1), of(15), of(24), NONE, NONE).start())
                .isEqualTo(at("2026-01-16 00:00:00"));
        assertThat(new DateBound(2026, NONE, NONE, of(24), NONE, NONE).start()).isEqualTo(at("2026-01-02 00:00:00"));
        assertThat(new DateBound(2026, NONE, NONE, of(24), NONE, NONE).end()).isEqualTo(at("2027-01-01 00:00:00"));
        assertThat(new DateBound(2026, of(1), of(15), of(10), of(60), NONE).start())
                .isEqualTo(at("2026-01-15 11:00:00"));
        assertThat(new DateBound(2026, of(12), of(31), of(23), of(59), of(99)).start())
                .isEqualTo(at("2027-01-01 00:00:00"));
    }

    @Test
    void conditionsKeepWallTimesFromTheirFirstKeptOneUntilTheirFirstDroppedOne() {
        DateBound day = DateBound.day(2026, 1, 15);
        assertThat(DateCondition.after(DateColumn.DATE, day, false).from()).contains(at("2026-01-16 00:00:00"));
        assertThat(DateCondition.after(DateColumn.DATE, day, true).from()).contains(at("2026-01-15 00:00:00"));
        assertThat(DateCondition.after(DateColumn.DATE, day, true).until()).isEmpty();
        assertThat(DateCondition.before(DateColumn.DATE, day, false).until()).contains(at("2026-01-15 00:00:00"));
        assertThat(DateCondition.before(DateColumn.DATE, day, true).until()).contains(at("2026-01-16 00:00:00"));
        assertThat(DateCondition.before(DateColumn.DATE, day, true).from()).isEmpty();
        DateCondition between = DateCondition.between(
                DateColumn.DATE, DateBound.year(2025), DateBound.exact(at("2026-03-01 10:00:00")), true);
        assertThat(between.from()).contains(at("2025-01-01 00:00:00"));
        assertThat(between.until()).contains(at("2026-03-01 10:00:01"));
    }

    @Test
    void anUnrealBoundSplitsTheSameWayWhetherInclusiveOrNot() {
        DateBound february30 = DateBound.day(2026, 2, 30);
        for (boolean inclusive : new boolean[] {false, true}) {
            assertThat(DateCondition.after(DateColumn.DATE, february30, inclusive)
                            .from())
                    .contains(at("2026-03-01 00:00:00"));
            assertThat(DateCondition.before(DateColumn.DATE, february30, inclusive)
                            .until())
                    .contains(at("2026-03-01 00:00:00"));
        }
    }

    @Test
    void theZeroDateSortsBeforeEveryBoundButAnAllZeroOne() {
        assertThat(DateCondition.before(DateColumn.DATE_GMT, DateBound.year(2000), false)
                        .zeroDateKept())
                .isTrue();
        assertThat(DateCondition.before(DateColumn.DATE_GMT, DateBound.year(2000), true)
                        .zeroDateKept())
                .isTrue();
        assertThat(DateCondition.after(DateColumn.DATE_GMT, DateBound.year(0), true)
                        .zeroDateKept())
                .isFalse();
        assertThat(DateCondition.between(DateColumn.DATE_GMT, DateBound.year(0), DateBound.year(2000), true)
                        .zeroDateKept())
                .isFalse();
        assertThat(new DateCondition(DateColumn.DATE_GMT, Optional.empty(), Optional.empty(), false).zeroDateKept())
                .isTrue();
        DateBound zero = new DateBound(0, of(0), of(0), of(0), of(0), of(0));
        assertThat(DateCondition.after(DateColumn.DATE_GMT, zero, true).zeroDateKept())
                .isTrue();
        assertThat(DateCondition.after(DateColumn.DATE_GMT, zero, false).zeroDateKept())
                .isFalse();
        assertThat(DateCondition.before(DateColumn.DATE_GMT, zero, false).zeroDateKept())
                .isFalse();
        assertThat(DateCondition.before(DateColumn.DATE_GMT, zero, true).zeroDateKept())
                .isTrue();
    }

    @Test
    void fieldsThatDoNotFitWordPressDateTextAreRefused() {
        assertThatThrownBy(() -> DateBound.year(10_000))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("year must be from 0 to 9999, was 10000");
        assertThatThrownBy(() -> DateBound.year(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("year must be from 0 to 9999, was -1");
        assertThatThrownBy(() -> DateBound.month(2026, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("month must be from 0 to 99, was -1");
        assertThatThrownBy(() -> DateBound.day(2026, 1, 100))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("day must be from 0 to 99, was 100");
        assertThatThrownBy(() -> new DateBound(2026, NONE, NONE, NONE, NONE, of(100)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("second must be from 0 to 99, was 100");
        assertThatThrownBy(() -> new DateBound(2026, null, NONE, NONE, NONE, NONE))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("month");
        assertThatThrownBy(() -> DateCondition.after(null, DateBound.year(2026), false))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("column");
    }
}
