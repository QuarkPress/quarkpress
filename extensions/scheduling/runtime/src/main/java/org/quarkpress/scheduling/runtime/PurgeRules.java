package org.quarkpress.scheduling.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

/**
 * The rules of the housekeeping purges, as WordPress's cron applies them: a trashed post goes once its trash time
 * (the meta WordPress writes when trashing, in seconds) is strictly older than now minus the trash days; an
 * auto-draft goes once it was last modified more than seven days ago.
 */
public final class PurgeRules {

    public static final long SECONDS_PER_DAY = 86400L;

    public static final int AUTO_DRAFT_DAYS = 7;

    public static final String TRASH_TIME_KEY = "_wp_trash_meta_time";

    private static final int MAX_DIGITS = 18;

    private PurgeRules() {}

    /** The trash time, in seconds, before which a trashed post is purged: now minus the trash days. */
    public static long trashThreshold(Instant now, int emptyTrashDays) {
        return now.getEpochSecond() - emptyTrashDays * SECONDS_PER_DAY;
    }

    /**
     * The trash time kept in {@code meta}: the first value under the key, a JSON number or a text of 1 to 18 ASCII
     * digits; empty for a missing key, an empty list, any other value, or a meta that is not an object.
     */
    public static Optional<Long> trashTime(JsonNode meta) {
        if (meta == null || !meta.isObject()) {
            return Optional.empty();
        }
        JsonNode values = meta.get(TRASH_TIME_KEY);
        if (values == null || !values.isArray() || values.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = values.get(0);
        if (first.isNumber()) {
            return Optional.of(first.longValue());
        }
        if (first.isTextual()) {
            return digits(first.textValue());
        }
        return Optional.empty();
    }

    /** Whether {@code meta} holds a trash time strictly older than {@code threshold}. */
    public static boolean trashPurgeable(JsonNode meta, long threshold) {
        Optional<Long> time = trashTime(meta);
        return time.isPresent() && time.get() < threshold;
    }

    /** The modification time before which an auto-draft is purged: now minus seven days. */
    public static Instant autoDraftThreshold(Instant now) {
        return now.minus(AUTO_DRAFT_DAYS, ChronoUnit.DAYS);
    }

    private static Optional<Long> digits(String text) {
        int length = text.length();
        if (length < 1 || length > MAX_DIGITS) {
            return Optional.empty();
        }
        long value = 0;
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return Optional.empty();
            }
            value = value * 10 + (c - '0');
        }
        return Optional.of(value);
    }
}
