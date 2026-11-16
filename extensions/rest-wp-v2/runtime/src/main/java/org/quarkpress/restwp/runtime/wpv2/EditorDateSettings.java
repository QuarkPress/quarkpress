package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteOptions;
import org.quarkpress.core.runtime.SiteZones;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * What WordPress prints into the edit screen as the {@code wp.date} settings, for every caller who may edit the post:
 * the site's date and time formats, the start of the week, and the time zone as {@code offset} (the zone's current
 * offset in hours when {@code timezone_string} names one, else the stored {@code gmt_offset} as PHP's float cast reads
 * it), {@code string} (the stored {@code timezone_string}) and {@code abbr} (the named zone's abbreviation now, empty
 * without one). A missing option falls back as WordPress's: {@code start_of_week} 0 and {@code timezone_string}
 * {@code UTC}.
 *
 * <p>The abbreviation is the letters PHP prints from the time zone database, or its numeric form (sign, two-digit
 * hours, two-digit minutes when not zero) where the database has no letters for the zone. Java's English short name
 * of the zone is taken only when it is one of the database's letter abbreviations and stands for the zone's offset at
 * that instant, so that the answer does not change with the JDK's locale data; the few zones Java names although the
 * database abbreviates them numerically, and the one whose letters Java lacks, are listed. The names need an English
 * locale among the application's locales.
 */
@ApplicationScoped
public class EditorDateSettings {

    private static final DateTimeFormatter SHORT_NAME = DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH);

    /** The letter abbreviations of the time zone database, with the offsets in seconds each stands for. */
    private static final Map<String, Set<Integer>> LETTERS = letters();

    /** Java's older spellings of the database's letters. */
    private static final Map<String, String> JAVA_SPELLINGS = Map.of("HAST", "HST", "HADT", "HDT");

    /** Zones the database abbreviates numerically, which Java names. */
    private static final Set<String> NUMERIC_ZONES = Set.of(
            "Africa/Casablanca",
            "Africa/El_Aaiun",
            "Antarctica/Casey",
            "Antarctica/Troll",
            "Asia/Colombo",
            "Atlantic/Azores",
            "Europe/Minsk",
            "Pacific/Pitcairn");

    /** Zones whose letters some JDKs name otherwise. */
    private static final Map<String, String> ZONE_LETTERS = Map.of("Africa/Windhoek", "CAT");

    private static final int SECONDS_PER_HOUR = 3600;

    @Inject
    SiteContext site;

    /** The date settings of the current site, now. */
    public ObjectNode of() {
        SiteOptions options = site.options();
        ObjectNode settings = RestJson.NODES.objectNode();
        ObjectNode formats = settings.putObject("formats");
        formats.put("date", options.string("date_format", "F j, Y"));
        formats.put("time", options.string("time_format", "g:i a"));
        settings.put("startOfWeek", options.number("start_of_week", 0));
        String name = options.string("timezone_string", "UTC");
        ZoneId zone = site.zone();
        Instant now = Instant.now();
        ObjectNode timezone = settings.putObject("timezone");
        if (zone instanceof ZoneOffset) {
            // no named zone: the stored manual offset, unbounded by what a zone offset can hold
            putHours(timezone, PhpValues.toDouble(options.get("gmt_offset").orElse(null)));
        } else {
            putHours(timezone, zone.getRules().getOffset(now).getTotalSeconds() / (double) SECONDS_PER_HOUR);
        }
        timezone.put("string", name);
        // the zone the text names, also where the option is missing and the text is the fallback
        ZoneId named = name.isEmpty() ? ZoneOffset.UTC : SiteZones.zoneOf(name, null);
        timezone.put("abbr", named instanceof ZoneOffset ? "" : abbreviation(named, now));
        return settings;
    }

    /** Hours as PHP's JSON prints a float: an integer when whole, else the shortest decimal; 0 when not finite. */
    private static void putHours(ObjectNode timezone, double hours) {
        if (!Double.isFinite(hours)) {
            timezone.put("offset", 0);
        } else if (hours == Math.rint(hours) && Math.abs(hours) < Long.MAX_VALUE) {
            timezone.put("offset", (long) hours);
        } else {
            timezone.put("offset", hours);
        }
    }

    /** The zone's abbreviation at {@code now} as PHP prints it: the database's letters, else the numeric form. */
    static String abbreviation(ZoneId zone, Instant now) {
        int seconds = zone.getRules().getOffset(now).getTotalSeconds();
        if (!NUMERIC_ZONES.contains(zone.getId())) {
            String name = ZONE_LETTERS.get(zone.getId());
            if (name == null) {
                name = SHORT_NAME.format(now.atZone(zone));
                name = JAVA_SPELLINGS.getOrDefault(name, name);
            }
            Set<Integer> offsets = LETTERS.get(name);
            if (offsets != null && offsets.contains(seconds)) {
                return name;
            }
        }
        int total = Math.abs(seconds);
        int hours = total / SECONDS_PER_HOUR;
        int minutes = total % SECONDS_PER_HOUR / 60;
        StringBuilder numeric = new StringBuilder(5);
        numeric.append(seconds < 0 ? '-' : '+');
        twoDigits(numeric, hours);
        if (minutes != 0) {
            twoDigits(numeric, minutes);
        }
        return numeric.toString();
    }

    private static Map<String, Set<Integer>> letters() {
        Map<String, Set<Integer>> letters = new HashMap<>();
        letters.put("ACDT", Set.of(37800));
        letters.put("ACST", Set.of(34200));
        letters.put("ADT", Set.of(-10800));
        letters.put("AEDT", Set.of(39600));
        letters.put("AEST", Set.of(36000));
        letters.put("AKDT", Set.of(-28800));
        letters.put("AKST", Set.of(-32400));
        letters.put("AST", Set.of(-14400));
        letters.put("AWST", Set.of(28800));
        letters.put("BST", Set.of(3600));
        letters.put("CAT", Set.of(7200));
        letters.put("CDT", Set.of(-14400, -18000));
        letters.put("CEST", Set.of(7200));
        letters.put("CET", Set.of(3600));
        letters.put("ChST", Set.of(36000));
        letters.put("CST", Set.of(-18000, -21600, 28800));
        letters.put("EAT", Set.of(10800));
        letters.put("EDT", Set.of(-14400));
        letters.put("EEST", Set.of(10800));
        letters.put("EET", Set.of(7200));
        letters.put("EST", Set.of(-18000));
        letters.put("GMT", Set.of(0));
        letters.put("HDT", Set.of(-32400));
        letters.put("HKT", Set.of(28800));
        letters.put("HST", Set.of(-36000));
        letters.put("IDT", Set.of(10800));
        letters.put("IST", Set.of(19800, 3600, 7200));
        letters.put("JST", Set.of(32400));
        letters.put("KST", Set.of(32400));
        letters.put("MDT", Set.of(-21600));
        letters.put("MET", Set.of(3600));
        letters.put("MSK", Set.of(10800));
        letters.put("MST", Set.of(-25200));
        letters.put("NDT", Set.of(-9000));
        letters.put("NST", Set.of(-12600));
        letters.put("NZDT", Set.of(46800));
        letters.put("NZST", Set.of(43200));
        letters.put("PDT", Set.of(-25200));
        letters.put("PKT", Set.of(18000));
        letters.put("PST", Set.of(-28800, 28800));
        letters.put("SAST", Set.of(7200));
        letters.put("SST", Set.of(-39600));
        letters.put("UTC", Set.of(0));
        letters.put("WAT", Set.of(3600));
        letters.put("WEST", Set.of(3600));
        letters.put("WET", Set.of(0));
        letters.put("WIB", Set.of(25200));
        letters.put("WIT", Set.of(32400));
        letters.put("WITA", Set.of(28800));
        return Map.copyOf(letters);
    }

    private static void twoDigits(StringBuilder text, int value) {
        if (value < 10) {
            text.append('0');
        }
        text.append(value);
    }
}
