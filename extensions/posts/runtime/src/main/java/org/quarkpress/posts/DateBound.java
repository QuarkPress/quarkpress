package org.quarkpress.posts;

import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.Objects;
import java.util.OptionalInt;

/**
 * A bound of a date condition, given to the precision WordPress accepts: a year, then any of month, day, hour,
 * minute and second. A missing field stands for its whole range: {@link #start()} pads it to its first value (month
 * and day 1, the time 0) and {@link #end()} to its last (month 12, the month's last day, hour 23, minute and second
 * 59), each field on its own, as WordPress pads a bound before writing it as {@code Y-m-d H:i:s} text.
 *
 * <p>WordPress compares that text with the date text of each post, so a bound whose padded digits name no real time
 * (31 February, month 0 or 13, hour 24, minute 60) still splits dates where its text sorts: before every later real
 * time. Such a bound's {@link #start()} or {@link #end()} is the first real time whose text sorts after its digits.
 * The year runs from 0 to 9999 and the other fields from 0 to 99, the values the text can hold.
 */
public record DateBound(
        int year, OptionalInt month, OptionalInt day, OptionalInt hour, OptionalInt minute, OptionalInt second) {

    private static final int MAX_YEAR = 9999;
    private static final int MAX_FIELD = 99;

    public DateBound {
        Objects.requireNonNull(month, "month");
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(hour, "hour");
        Objects.requireNonNull(minute, "minute");
        Objects.requireNonNull(second, "second");
        check("year", year, MAX_YEAR);
        check("month", month);
        check("day", day);
        check("hour", hour);
        check("minute", minute);
        check("second", second);
    }

    public static DateBound year(int year) {
        return new DateBound(
                year,
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty());
    }

    public static DateBound month(int year, int month) {
        return new DateBound(
                year,
                OptionalInt.of(month),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty());
    }

    public static DateBound day(int year, int month, int day) {
        return new DateBound(
                year,
                OptionalInt.of(month),
                OptionalInt.of(day),
                OptionalInt.empty(),
                OptionalInt.empty(),
                OptionalInt.empty());
    }

    /** The minute of {@code time} (its seconds are ignored). */
    public static DateBound minute(LocalDateTime time) {
        return new DateBound(
                time.getYear(),
                OptionalInt.of(time.getMonthValue()),
                OptionalInt.of(time.getDayOfMonth()),
                OptionalInt.of(time.getHour()),
                OptionalInt.of(time.getMinute()),
                OptionalInt.empty());
    }

    /** Exactly {@code time}, to the second. */
    public static DateBound exact(LocalDateTime time) {
        return new DateBound(
                time.getYear(),
                OptionalInt.of(time.getMonthValue()),
                OptionalInt.of(time.getDayOfMonth()),
                OptionalInt.of(time.getHour()),
                OptionalInt.of(time.getMinute()),
                OptionalInt.of(time.getSecond()));
    }

    /** The first second of the period (for digits naming no real time, the first real time sorting after them). */
    public LocalDateTime start() {
        return firstFrom(digits(false));
    }

    /** The last second of the period (for digits naming no real time, the first real time sorting after them). */
    public LocalDateTime end() {
        return firstFrom(digits(true));
    }

    /** The first real time whose text sorts after the padded digits: at or after them, or strictly after them. */
    LocalDateTime firstFrom(boolean end, boolean strictly) {
        int[] digits = digits(end);
        LocalDateTime first = firstFrom(digits);
        return strictly && names(first, digits) ? first.plusSeconds(1) : first;
    }

    /** Whether the start digits are all zero, WordPress's zero date {@code 0000-00-00 00:00:00}. */
    boolean startsAtZeroDate() {
        for (int digit : digits(false)) {
            if (digit != 0) {
                return false;
            }
        }
        return true;
    }

    /** The padded fields, year to second, as WordPress pads them. */
    private int[] digits(boolean end) {
        int m = month.orElse(end ? 12 : 1);
        return new int[] {
            year,
            m,
            day.orElse(end ? monthLength(year, m) : 1),
            hour.orElse(end ? 23 : 0),
            minute.orElse(end ? 59 : 0),
            second.orElse(end ? 59 : 0)
        };
    }

    /** The length of the month, counting a month outside 1 to 12 into the years around, as PHP's mktime does. */
    private static int monthLength(int year, int month) {
        return YearMonth.of(year, 1).plusMonths(month - 1L).lengthOfMonth();
    }

    /**
     * The first real time whose text sorts at or after the digits: the digits themselves when they name a real time,
     * else the start of the period after the first field that is out of range for its place.
     */
    private static LocalDateTime firstFrom(int[] d) {
        if (d[1] == 0) {
            return LocalDateTime.of(d[0], 1, 1, 0, 0);
        }
        if (d[1] > 12) {
            return LocalDateTime.of(d[0], 1, 1, 0, 0).plusYears(1);
        }
        if (d[2] == 0) {
            return LocalDateTime.of(d[0], d[1], 1, 0, 0);
        }
        if (d[2] > YearMonth.of(d[0], d[1]).lengthOfMonth()) {
            return LocalDateTime.of(d[0], d[1], 1, 0, 0).plusMonths(1);
        }
        if (d[3] > 23) {
            return LocalDateTime.of(d[0], d[1], d[2], 0, 0).plusDays(1);
        }
        if (d[4] > 59) {
            return LocalDateTime.of(d[0], d[1], d[2], d[3], 0).plusHours(1);
        }
        if (d[5] > 59) {
            return LocalDateTime.of(d[0], d[1], d[2], d[3], d[4]).plusMinutes(1);
        }
        return LocalDateTime.of(d[0], d[1], d[2], d[3], d[4], d[5]);
    }

    private static boolean names(LocalDateTime time, int[] d) {
        return time.getYear() == d[0]
                && time.getMonthValue() == d[1]
                && time.getDayOfMonth() == d[2]
                && time.getHour() == d[3]
                && time.getMinute() == d[4]
                && time.getSecond() == d[5];
    }

    private static void check(String name, OptionalInt value) {
        if (value.isPresent()) {
            check(name, value.getAsInt(), MAX_FIELD);
        }
    }

    private static void check(String name, int value, int max) {
        if (value < 0 || value > max) {
            throw new IllegalArgumentException(name + " must be from 0 to " + max + ", was " + value);
        }
    }
}
