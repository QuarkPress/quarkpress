package org.quarkword.posts.runtime;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.Objects;
import java.util.Optional;

/**
 * Week numbers as WordPress compares them for its {@code w} variable: MySQL's {@code WEEK(date, 1)} when the site's
 * {@code start_of_week} is 1 (weeks start on Monday, week 1 is the first one with four days or more in the year),
 * {@code WEEK(date, 0)} when it is 0 or any value outside 1 to 6 (weeks start on Sunday, week 1 starts on the year's
 * first Sunday), and {@code WEEK(date - n days, 0)} for a value n from 2 to 6. Days before the first week are week 0;
 * a week is never counted in the next year, so the last one can be 53.
 */
public final class Weeks {

    /** The highest number a week can have. */
    public static final int MAX_WEEK = 53;

    /** A range of days: from {@code from} (included) until {@code until} (excluded). */
    public record Range(LocalDate from, LocalDate until) {
        public Range {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(until, "until");
            if (!from.isBefore(until)) {
                throw new IllegalArgumentException("an empty range: " + from + " to " + until);
            }
        }
    }

    private Weeks() {}

    /** The week number of {@code date} under {@code startOfWeek}. */
    public static int number(LocalDate date, long startOfWeek) {
        Objects.requireNonNull(date, "date");
        int shift = shift(startOfWeek);
        LocalDate counted = date.minusDays(shift);
        return week(counted, mondayFirst(startOfWeek));
    }

    /**
     * The days whose week number is {@code week} under {@code startOfWeek} among those counted in {@code year}: one
     * range, as a week number names consecutive days within a year (for a shifted start, the days whose date {@code
     * n} days earlier falls in {@code year}); empty when no day of the year has that number.
     */
    public static Optional<Range> range(int year, int week, long startOfWeek) {
        if (week < 0 || week > MAX_WEEK) {
            return Optional.empty();
        }
        boolean mondayFirst = mondayFirst(startOfWeek);
        LocalDate first = LocalDate.of(year, 1, 1);
        LocalDate next = first.plusYears(1);
        LocalDate firstWeek = firstWeek(year, mondayFirst);
        LocalDate from;
        LocalDate until;
        if (week == 0) {
            from = first;
            until = firstWeek;
        } else {
            from = firstWeek.plusWeeks(week - 1L);
            until = from.plusWeeks(1);
        }
        // a week reaching into the years around keeps only its days in this year: the others have other numbers
        if (from.isBefore(first)) {
            from = first;
        }
        if (until.isAfter(next)) {
            until = next;
        }
        if (!from.isBefore(until)) {
            return Optional.empty();
        }
        int shift = shift(startOfWeek);
        return Optional.of(new Range(from.plusDays(shift), until.plusDays(shift)));
    }

    /** MySQL's {@code WEEK(date, 1)} or {@code WEEK(date, 0)}. */
    private static int week(LocalDate date, boolean mondayFirst) {
        LocalDate start = firstWeek(date.getYear(), mondayFirst);
        if (date.isBefore(start)) {
            return 0;
        }
        return (int) ((date.toEpochDay() - start.toEpochDay()) / 7) + 1;
    }

    /**
     * The first day of week 1: with weeks starting on Monday, the Monday of the week holding 1 January when that week
     * has four days or more in the year, else the Monday after; with weeks starting on Sunday, the year's first Sunday.
     */
    private static LocalDate firstWeek(int year, boolean mondayFirst) {
        LocalDate first = LocalDate.of(year, 1, 1);
        if (!mondayFirst) {
            return first.with(TemporalAdjusters.nextOrSame(DayOfWeek.SUNDAY));
        }
        int daysFromMonday = first.getDayOfWeek().getValue() - 1;
        return daysFromMonday <= 3 ? first.minusDays(daysFromMonday) : first.plusDays(7L - daysFromMonday);
    }

    private static boolean mondayFirst(long startOfWeek) {
        return startOfWeek == 1;
    }

    /** The days a date is moved back before it is counted: n for a start of week from 2 to 6, else none. */
    private static int shift(long startOfWeek) {
        return startOfWeek >= 2 && startOfWeek <= 6 ? (int) startOfWeek : 0;
    }
}
