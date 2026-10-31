package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.time.ZoneId;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import org.quarkpress.lib.wpkses.Kses;
import org.quarkpress.restwp.runtime.Formats;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.TextFields;

/**
 * WordPress's sanitizing of an option's value when it is written (persistence's option writes do none): titles and
 * taglines HTML-escaped, date and time formats without tags, identifiers as absolute integers, the posts per page
 * kept at 1 or more (or -1), comment and ping statuses closed on "0" or "", and the time zone and the language (unless
 * empty, "" or "0"), the site URL and the administrator's address refused unless valid, which leaves the stored value
 * as it was. Other options are kept as given. Every rule looks at each character a bounded number of times.
 */
public final class OptionSanitizers {

    /** The outcome: the value to store, or a refusal that keeps the stored one. */
    public sealed interface Sanitized {

        record Kept(JsonNode value) implements Sanitized {
            public Kept {
                Objects.requireNonNull(value, "value");
            }
        }

        record Rejected() implements Sanitized {}
    }

    private static final Set<String> ABSOLUTE_INTEGERS =
            Set.of("start_of_week", "default_category", "page_on_front", "page_for_posts", "site_icon");

    /**
     * The time zone identifiers PHP knows, with the ones it keeps for backward compatibility: the Java runtime's zone
     * database, without its {@code SystemV/} zones, which PHP does not have, and with the names PHP has besides.
     */
    private static final Set<String> TIME_ZONES = timeZones();

    private OptionSanitizers() {}

    /** The value {@code value} of option {@code optionName} as WordPress would store it, or its refusal. */
    public static Sanitized sanitize(String optionName, JsonNode value) {
        Objects.requireNonNull(optionName, "optionName");
        Objects.requireNonNull(value, "value");
        switch (optionName) {
            case "blogname", "blogdescription" -> {
                return value.isTextual() ? kept(escapeHtml(value.textValue())) : new Sanitized.Kept(value);
            }
            case "date_format", "time_format" -> {
                return value.isTextual() ? kept(Kses.stripTags(value.textValue())) : new Sanitized.Kept(value);
            }
            case "posts_per_page" -> {
                long number = PhpValues.toLong(value);
                if (number == 0) {
                    number = 1;
                }
                return number < -1 ? absolute(number) : kept(number);
            }
            case "default_ping_status", "default_comment_status" -> {
                String text = value.isValueNode() ? PhpValues.toPhpString(value) : null;
                return "0".equals(text) || "".equals(text) ? kept("closed") : new Sanitized.Kept(value);
            }
            case "timezone_string" -> {
                return value.isTextual() && (phpEmpty(value.textValue()) || TIME_ZONES.contains(value.textValue()))
                        ? new Sanitized.Kept(value)
                        : new Sanitized.Rejected();
            }
            case "WPLANG" -> {
                // no language is installed besides WordPress's built-in one; an empty value ("" or "0") is kept
                return value.isTextual() && phpEmpty(value.textValue())
                        ? new Sanitized.Kept(value)
                        : new Sanitized.Rejected();
            }
            case "admin_email" -> {
                return value.isTextual() && Formats.email(value.textValue())
                        ? new Sanitized.Kept(value)
                        : new Sanitized.Rejected();
            }
            case "siteurl", "home" -> {
                if (!value.isTextual()) {
                    return new Sanitized.Rejected();
                }
                String url = TextFields.url(value.textValue());
                return url.startsWith("http://") || url.startsWith("https://") ? kept(url) : new Sanitized.Rejected();
            }
            default -> {
                return ABSOLUTE_INTEGERS.contains(optionName)
                        ? absolute(PhpValues.toLong(value))
                        : new Sanitized.Kept(value);
            }
        }
    }

    /**
     * A value as WordPress's option table holds it: true as "1" and false as "" (which a boolean setting then reads as
     * null, as WordPress does); other values as they are.
     */
    public static JsonNode stored(JsonNode value) {
        if (value.isBoolean()) {
            return TextNode.valueOf(value.booleanValue() ? "1" : "");
        }
        return value;
    }

    /** WordPress's HTML escaping of text: '&' not encoded again where it starts a character reference. */
    static String escapeHtml(String text) {
        if (hasLoneSurrogate(text)) {
            return "";
        }
        String special = Kses.specialChars(text);
        if (special.indexOf('"') < 0 && special.indexOf('\'') < 0) {
            return special;
        }
        StringBuilder out = new StringBuilder(special.length() + 16);
        for (int i = 0; i < special.length(); i++) {
            char c = special.charAt(i);
            switch (c) {
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#039;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    /** PHP's {@code empty} of a text: "" and "0". */
    private static boolean phpEmpty(String text) {
        return text.isEmpty() || text.equals("0");
    }

    private static boolean hasLoneSurrogate(String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isHighSurrogate(c)) {
                if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
                    return true;
                }
                i++;
            } else if (Character.isLowSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    /** An integer as the module's JSON holds one: an int node when it fits. */
    private static Sanitized kept(long number) {
        return new Sanitized.Kept(number == (int) number ? IntNode.valueOf((int) number) : LongNode.valueOf(number));
    }

    private static Sanitized kept(String text) {
        return new Sanitized.Kept(TextNode.valueOf(text));
    }

    /** PHP's {@code abs} of an integer: the smallest one has no integer opposite and becomes a float. */
    private static Sanitized absolute(long number) {
        if (number == Long.MIN_VALUE) {
            return new Sanitized.Kept(DoubleNode.valueOf(-(double) Long.MIN_VALUE));
        }
        return kept(Math.abs(number));
    }

    private static Set<String> timeZones() {
        Set<String> zones = new HashSet<>();
        for (String zone : ZoneId.getAvailableZoneIds()) {
            if (!zone.startsWith("SystemV/")) {
                zones.add(zone);
            }
        }
        zones.addAll(Set.of("EST", "MST", "HST", "ROC", "GMT+0", "GMT-0", "Factory"));
        return Set.copyOf(zones);
    }
}
