package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
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
 * <p>The abbreviation is Java's English short name of the zone; where Java has none and falls back to a {@code GMT}
 * form (a bare {@code GMT} at a non-zero offset included), PHP's numeric form is given instead (sign, two-digit hours,
 * two-digit minutes when not zero). A zone whose time zone database abbreviation is numeric but which Java names may
 * therefore read differently from PHP's. The names need an English locale among the application's locales.
 */
@ApplicationScoped
public class EditorDateSettings {

    private static final DateTimeFormatter SHORT_NAME = DateTimeFormatter.ofPattern("zzz", Locale.ENGLISH);

    private static final String GMT = "GMT";

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

    /** The zone's English short name at {@code now}, or PHP's numeric form where Java falls back to a GMT form. */
    static String abbreviation(ZoneId zone, Instant now) {
        int seconds = zone.getRules().getOffset(now).getTotalSeconds();
        String text = SHORT_NAME.format(now.atZone(zone));
        if (!text.startsWith(GMT) || (text.length() == GMT.length() && seconds == 0)) {
            return text;
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

    private static void twoDigits(StringBuilder text, int value) {
        if (value < 10) {
            text.append('0');
        }
        text.append(value);
    }
}
