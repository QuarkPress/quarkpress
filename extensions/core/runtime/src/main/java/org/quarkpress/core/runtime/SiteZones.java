package org.quarkpress.core.runtime;

import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalDouble;

/**
 * A site's time zone from its options, as WordPress derives it: the {@code timezone_string} when it is an IANA region
 * id (matched ignoring case and surrounding whitespace), otherwise the {@code gmt_offset} in hours, read as PHP's
 * {@code (float)} cast reads it and cut to whole hours and whole minutes the way WordPress formats it, otherwise UTC.
 *
 * <p>Deliberate differences, where WordPress itself fails or Java cannot follow:
 *
 * <ul>
 *   <li>a {@code timezone_string} that is no IANA region id falls back to the offset (WordPress fails); this includes
 *       forms only Java knows ({@code UTC+5}, {@code UTC-03:30}, {@code UT}, {@code SystemV/*}), PHP's fixed-offset
 *       abbreviations ({@code EST}, {@code PST}, {@code CET} as a fixed offset, {@code z}) and names found only in a
 *       host's own time zone data, which are not reconciled;
 *   <li>offsets beyond 18 hours, which {@code java.time} cannot represent, give UTC.
 * </ul>
 */
public final class SiteZones {

    private static final int MAX_ZONE_NAME = 64;
    private static final int MAX_SECONDS = 18 * 3600;
    private static final Map<String, String> REGIONS_BY_LOWER_CASE = regionsByLowerCase();

    private SiteZones() {}

    public static ZoneId zoneOf(String timezoneString, String gmtOffset) {
        if (timezoneString != null && timezoneString.length() <= MAX_ZONE_NAME) {
            String region = REGIONS_BY_LOWER_CASE.get(trim(timezoneString).toLowerCase(Locale.ROOT));
            if (region != null) {
                return ZoneId.of(region);
            }
        }
        if (gmtOffset == null) {
            return ZoneOffset.UTC;
        }
        OptionalDouble hours = PhpValues.leadingFloat(gmtOffset);
        return hours.isPresent() ? offsetOf(hours.getAsDouble()) : ZoneOffset.UTC;
    }

    private static ZoneOffset offsetOf(double offset) {
        if (!(Math.abs(offset) < 19)) {
            return ZoneOffset.UTC;
        }
        // as WordPress: whole hours toward zero, then the fraction times 60, truncated by its %02d format
        long hours = (long) offset;
        int minutes = (int) Math.abs((offset - hours) * 60);
        long seconds = Math.abs(hours) * 3600 + minutes * 60L;
        if (seconds > MAX_SECONDS) {
            return ZoneOffset.UTC;
        }
        return ZoneOffset.ofTotalSeconds((int) (offset < 0 ? -seconds : seconds));
    }

    /** PHP's {@code trim}: space, tab, line feed, carriage return, NUL and vertical tab from both ends. */
    private static String trim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && isTrimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && isTrimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean isTrimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u0000' || c == '\u000B';
    }

    /** IANA region ids (with a slash, such as {@code Europe/Paris}, plus the tzdb names such as {@code UTC}). */
    private static Map<String, String> regionsByLowerCase() {
        Map<String, String> regions = new HashMap<>();
        for (String zone : ZoneId.getAvailableZoneIds()) {
            if (!zone.startsWith("SystemV/")) {
                regions.put(zone.toLowerCase(Locale.ROOT), zone);
            }
        }
        return Map.copyOf(regions);
    }
}
