package org.quarkpress.posts.runtime;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.QuarkPressClock;
import org.quarkpress.posts.PostException;

/**
 * Post dates as WordPress resolves them. The GMT date ({@code publishedAt}) is the stored one; a post without it has
 * a floating date, whose local value is its modification time. Local times convert with the site's zone: a time in
 * a spring-forward gap moves forward by the gap; an ambiguous autumn time takes the offset in force at the UTC
 * instant with the same digits, which is its first occurrence west of UTC and its second east of it.
 */
public final class PostDates {

    /** A published post dated at least this far ahead is scheduled instead. */
    public static final long FUTURE_THRESHOLD_SECONDS = 60;

    private static final int MIN_YEAR = 1000;
    private static final int MAX_YEAR = 9999;
    private static final DateTimeFormatter FORMAT = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    /** The earliest storable date. */
    public static final Instant STORABLE_FROM =
            LocalDateTime.of(MIN_YEAR, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);

    /** The first date past the storable ones. */
    public static final Instant STORABLE_UNTIL =
            LocalDateTime.of(MAX_YEAR + 1, 1, 1, 0, 0).toInstant(ZoneOffset.UTC);

    private PostDates() {}

    /** A run of instants from {@code from} (kept) until {@code until} (left out); an empty end is open. */
    public record Span(Optional<Instant> from, Optional<Instant> until) {
        public Span {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(until, "until");
        }
    }

    /**
     * The instants whose wall time in the zone, to the second, is {@code wall} or later, in order. Wall times compare
     * as WordPress compares its local date text, which is not the order of the instants: in an autumn overlap the
     * repeated times before {@code wall} are left out although they come after its first occurrence; a wall time in a
     * spring gap is reached at the transition.
     */
    public static List<Span> wallFrom(LocalDateTime wall, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        List<ZoneOffset> offsets = rules.getValidOffsets(wall);
        if (offsets.size() == 1) {
            return List.of(new Span(Optional.of(wall.toInstant(offsets.get(0))), Optional.empty()));
        }
        ZoneOffsetTransition transition = rules.getTransition(wall);
        if (transition.isGap()) {
            return List.of(new Span(Optional.of(transition.getInstant()), Optional.empty()));
        }
        return List.of(
                new Span(
                        Optional.of(wall.toInstant(transition.getOffsetBefore())),
                        Optional.of(transition.getInstant())),
                new Span(Optional.of(wall.toInstant(transition.getOffsetAfter())), Optional.empty()));
    }

    /** The instants whose wall time in the zone is before {@code wall}, in order (see {@link #wallFrom}). */
    public static List<Span> wallBefore(LocalDateTime wall, ZoneId zone) {
        List<Span> before = new ArrayList<>();
        Optional<Instant> start = Optional.empty();
        for (Span from : wallFrom(wall, zone)) {
            before.add(new Span(start, from.from()));
            start = from.until();
        }
        if (start.isPresent()) {
            before.add(new Span(start, Optional.empty()));
        }
        return before;
    }

    /** The resolved dates of a save: the GMT date (empty while floating) and the modification time. */
    public record Resolved(Optional<Instant> publishedAt, Instant modifiedAt) {
        public Resolved {
            Objects.requireNonNull(publishedAt, "publishedAt");
            Objects.requireNonNull(modifiedAt, "modifiedAt");
        }
    }

    /** The clock's time to the second, as WordPress keeps post dates. */
    public static Instant now(QuarkPressClock clock) {
        return clock.now().truncatedTo(ChronoUnit.SECONDS);
    }

    public static Instant toInstant(LocalDateTime local, ZoneId zone) {
        LocalDateTime wall = local.truncatedTo(ChronoUnit.SECONDS);
        ZoneOffset guess = zone.getRules().getOffset(wall.toInstant(ZoneOffset.UTC));
        return zone.getRules().isValidOffset(wall, guess)
                ? wall.toInstant(guess)
                : ZonedDateTime.ofLocal(wall, zone, null).toInstant();
    }

    public static LocalDateTime toLocal(Instant instant, ZoneId zone) {
        return LocalDateTime.ofInstant(instant.truncatedTo(ChronoUnit.SECONDS), zone);
    }

    /** WordPress's {@code Y-m-d H:i:s} text of a local or UTC wall time. */
    public static String format(LocalDateTime local) {
        return FORMAT.format(local);
    }

    /** A post's local date: its GMT date in the zone, or its modification time while its date floats. */
    public static LocalDateTime localDate(Optional<Instant> publishedAt, Instant modifiedAt, ZoneId zone) {
        return toLocal(publishedAt.orElse(modifiedAt), zone);
    }

    /**
     * The dates of a save. The local date is the given one, else the given GMT date in the zone, else now; the GMT
     * date is the given one, else none for a floating status, else the local date converted. An insert is modified
     * at its post date, an update now. Dates are kept to the second; years outside 1000 to 9999 are refused.
     */
    public static Resolved resolve(
            Optional<LocalDateTime> date,
            Optional<Instant> dateGmt,
            boolean floating,
            boolean update,
            Instant now,
            ZoneId zone) {
        date.ifPresent(PostDates::requireStorable);
        dateGmt.ifPresent(PostDates::requireStorable);
        Optional<Instant> gmt = dateGmt.map(instant -> instant.truncatedTo(ChronoUnit.SECONDS));
        Instant postDate =
                date.map(local -> toInstant(local, zone)).or(() -> gmt).orElse(now);
        requireStorable(postDate);
        Optional<Instant> publishedAt = gmt.isPresent() || floating ? gmt : Optional.of(postDate);
        Instant modifiedAt = update ? now : publishedAt.orElse(postDate);
        return new Resolved(publishedAt, modifiedAt);
    }

    /**
     * WordPress's switch between publish and future (never for attachments): a published post dated at least 60
     * seconds after now is scheduled; a scheduled post dated less than 60 seconds ahead, in the past, or without a
     * date is published. Every other status keeps any date.
     */
    public static String coerceStatus(String status, Optional<Instant> publishedAt, Instant now, boolean attachment) {
        if (attachment) {
            return status;
        }
        if (status.equals("publish")) {
            if (publishedAt.isPresent() && ahead(publishedAt.get(), now) >= FUTURE_THRESHOLD_SECONDS) {
                return "future";
            }
        } else if (status.equals("future")) {
            if (publishedAt.isEmpty() || ahead(publishedAt.get(), now) < FUTURE_THRESHOLD_SECONDS) {
                return "publish";
            }
        }
        return status;
    }

    public static void requireStorable(LocalDateTime local) {
        if (local.getYear() < MIN_YEAR || local.getYear() > MAX_YEAR) {
            throw PostException.invalidDate();
        }
    }

    public static void requireStorable(Instant instant) {
        if (instant.isBefore(STORABLE_FROM) || !instant.isBefore(STORABLE_UNTIL)) {
            throw PostException.invalidDate();
        }
    }

    private static long ahead(Instant date, Instant now) {
        return date.getEpochSecond() - now.getEpochSecond();
    }
}
