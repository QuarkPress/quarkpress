package org.quarkpress.testing.wpscenario;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Objects;

/**
 * When a side ran a scenario. A stamp of the run shows as {@code now}, since the two sides run at different times;
 * scenario-given dates are years away from any run, so nothing they hold is lost.
 */
public record RunWindow(Instant start, Instant end) {

    /** The widest distance of a site's local time from UTC. */
    private static final long LOCAL_SLACK_SECONDS = 14 * 3600;

    public RunWindow {
        Objects.requireNonNull(start, "start");
        Objects.requireNonNull(end, "end");
    }

    /** An instant inside the run, with two seconds of slack at both ends, as {@code now}. */
    public String show(Instant at, String text) {
        return within(at, 2) ? "now" : text;
    }

    /**
     * A local {@code Y-m-d H:i:s} text within 14 hours of the run as {@code now}, whatever zone the site had when it
     * was written; any other text as it is.
     */
    public String showLocal(String text) {
        LocalDateTime local;
        try {
            local = PostRecords.parse(text);
        } catch (DateTimeParseException notADate) {
            return text;
        }
        return within(local.toInstant(ZoneOffset.UTC), LOCAL_SLACK_SECONDS) ? "now" : text;
    }

    /** A local {@code Y-m-d} date within one day of the run's UTC dates as {@code now}; any other text as it is. */
    public String showLocalDay(String text) {
        LocalDate day;
        try {
            day = LocalDate.parse(text);
        } catch (DateTimeParseException notADay) {
            return text;
        }
        LocalDate first = start.atZone(ZoneOffset.UTC).toLocalDate().minusDays(1);
        LocalDate last = end.atZone(ZoneOffset.UTC).toLocalDate().plusDays(1);
        return !day.isBefore(first) && !day.isAfter(last) ? "now" : text;
    }

    private boolean within(Instant at, long slackSeconds) {
        return !at.isBefore(start.minusSeconds(slackSeconds)) && !at.isAfter(end.plusSeconds(slackSeconds));
    }
}
