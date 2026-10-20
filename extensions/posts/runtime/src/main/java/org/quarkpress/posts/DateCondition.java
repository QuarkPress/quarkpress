package org.quarkpress.posts;

import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;

/**
 * A date condition (a clause of WordPress's {@code date_query} with {@code after}, {@code before} and {@code
 * inclusive}). {@code after} keeps dates past the bound's end, or from its start when inclusive; {@code before} keeps
 * dates before the bound's start, or up to its end when inclusive. Dates compare as WordPress compares their {@code
 * Y-m-d H:i:s} text, to the second: local columns in the site's zone, GMT ones in UTC. A condition without bounds
 * keeps every post.
 */
public record DateCondition(
        DateColumn column, Optional<DateBound> after, Optional<DateBound> before, boolean inclusive) {

    public DateCondition {
        Objects.requireNonNull(column, "column");
        Objects.requireNonNull(after, "after");
        Objects.requireNonNull(before, "before");
    }

    public static DateCondition after(DateColumn column, DateBound bound, boolean inclusive) {
        return new DateCondition(column, Optional.of(bound), Optional.empty(), inclusive);
    }

    public static DateCondition before(DateColumn column, DateBound bound, boolean inclusive) {
        return new DateCondition(column, Optional.empty(), Optional.of(bound), inclusive);
    }

    public static DateCondition between(DateColumn column, DateBound after, DateBound before, boolean inclusive) {
        return new DateCondition(column, Optional.of(after), Optional.of(before), inclusive);
    }

    /**
     * The start of the range of wall times the condition keeps: the earliest one the {@code after} bound keeps, every
     * later one being kept too. It is where the bound splits dates, not a date to show.
     */
    public Optional<LocalDateTime> from() {
        return after.map(bound -> inclusive ? bound.firstFrom(false, false) : bound.firstFrom(true, true));
    }

    /**
     * The end of the range of wall times the condition keeps: the earliest one the {@code before} bound drops, every
     * earlier one being kept. It is where the bound splits dates, not a date to show.
     */
    public Optional<LocalDateTime> until() {
        return before.map(bound -> inclusive ? bound.firstFrom(true, true) : bound.firstFrom(false, false));
    }

    /**
     * Whether the range of dates the condition keeps includes a missing GMT post date: WordPress's zero date {@code
     * 0000-00-00 00:00:00}, which a draft whose date floats has. Its text sorts before every bound but one whose
     * start digits are all zero.
     */
    public boolean zeroDateKept() {
        boolean afterKeeps = after.isEmpty() || (inclusive && after.get().startsAtZeroDate());
        boolean beforeKeeps = before.isEmpty() || inclusive || !before.get().startsAtZeroDate();
        return afterKeeps && beforeKeeps;
    }
}
