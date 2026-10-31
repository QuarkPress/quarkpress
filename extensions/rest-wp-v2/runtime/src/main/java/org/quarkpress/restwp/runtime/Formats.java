package org.quarkpress.restwp.runtime;

/**
 * The string formats WordPress checks request values against, as hand-written scans: each looks at every character a
 * bounded number of times. A single line feed at the very end is accepted where WordPress's own check accepts it
 * (dates, IPv4 addresses, UUIDs and hex colours).
 */
public final class Formats {

    private Formats() {}

    /**
     * A date and time: {@code YYYY-MM-DD}, then {@code T}, {@code t} or a space, then {@code hh:mm:ss}, an optional
     * fraction, and an optional {@code Z} or offset ({@code +/-hh} or {@code +/-hh:mm}); with the field ranges PHP's
     * date parser accepts (month 0-12, day 0-31, hour 0-24, minute 0-59, second 0-60, offset hours 0-24 with minutes
     * 0-59 when written with a colon; the day is not checked against the month).
     */
    static boolean dateTime(String text) {
        int end = withoutFinalLineFeed(text);
        int[] at = {0};
        int month;
        int day;
        int hour;
        int minute;
        int second;
        if (digits(text, at, 4, end) < 0
                || !expect(text, at, '-', end)
                || (month = digits(text, at, 2, end)) < 0
                || !expect(text, at, '-', end)
                || (day = digits(text, at, 2, end)) < 0) {
            return false;
        }
        if (at[0] >= end || "Tt ".indexOf(text.charAt(at[0])) < 0) {
            return false;
        }
        at[0]++;
        if ((hour = digits(text, at, 2, end)) < 0
                || !expect(text, at, ':', end)
                || (minute = digits(text, at, 2, end)) < 0
                || !expect(text, at, ':', end)
                || (second = digits(text, at, 2, end)) < 0) {
            return false;
        }
        if (at[0] < end && text.charAt(at[0]) == '.') {
            int start = ++at[0];
            while (at[0] < end && digit(text.charAt(at[0]))) {
                at[0]++;
            }
            if (at[0] == start) {
                return false;
            }
        }
        if (at[0] < end) {
            char c = text.charAt(at[0]);
            if (c == 'Z') {
                at[0]++;
            } else if (c == '+' || c == '-') {
                at[0]++;
                int offsetHours = digits(text, at, 2, end);
                if (offsetHours < 0) {
                    return false;
                }
                if (at[0] < end && text.charAt(at[0]) == ':') {
                    at[0]++;
                    int offsetMinutes = digits(text, at, 2, end);
                    if (offsetMinutes < 0 || offsetHours > 24 || offsetMinutes > 59) {
                        return false;
                    }
                }
            } else {
                return false;
            }
        }
        return at[0] == end && month <= 12 && day <= 31 && hour <= 24 && minute <= 59 && second <= 60;
    }

    /**
     * WordPress's e-mail check: at least 6 characters; an '@' after the first character; a local part of letters,
     * digits and {@code !#$%&'*+/=?^_`{|}~.-}; a domain of at least two dot-separated labels, without "..", not
     * starting or ending with a dot or whitespace, each label of letters, digits and '-' and not starting or ending
     * with '-'.
     */
    public static boolean email(String text) {
        if (text.length() < 6) {
            return false;
        }
        int at = text.indexOf('@');
        if (at < 1) {
            return false;
        }
        for (int i = 0; i < at; i++) {
            if (!localChar(text.charAt(i))) {
                return false;
            }
        }
        int start = at + 1;
        int labels = 0;
        int labelStart = start;
        for (int i = start; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '.') {
                if (!label(text, labelStart, i)) {
                    return false;
                }
                labels++;
                labelStart = i + 1;
            } else if (!domainChar(text.charAt(i))) {
                return false;
            }
        }
        return labels >= 2;
    }

    /** An IPv4 address (octets of one to three digits up to 255, leading zeros allowed) or an IPv6 address. */
    static boolean ip(String text) {
        return ipv4(text, 0, withoutFinalLineFeed(text), true) || ipv6(text);
    }

    /** A UUID in lower-case hexadecimal, {@code 8-4-4-4-12}. */
    static boolean uuid(String text) {
        int end = withoutFinalLineFeed(text);
        if (end != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = text.charAt(i);
            boolean dash = i == 8 || i == 13 || i == 18 || i == 23;
            if (dash ? c != '-' : !((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }
        return true;
    }

    /** {@code #} and three or six hexadecimal digits. */
    static boolean hexColor(String text) {
        int end = withoutFinalLineFeed(text);
        if ((end != 4 && end != 7) || text.charAt(0) != '#') {
            return false;
        }
        for (int i = 1; i < end; i++) {
            if (!hex(text.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * An IPv6 address as WordPress's HTTP library checks it: a single "::" is first expanded to the zero groups that
     * make eight (an expansion that would need a negative count makes the address invalid); then eight groups of one
     * to four hexadecimal digits, or six groups and a dotted IPv4 tail of canonical decimal octets up to 255.
     */
    static boolean ipv6(String text) {
        String ip = uncompress(text);
        if (ip == null) {
            return false;
        }
        String v6 = ip;
        String v4 = "";
        if (ip.indexOf('.') >= 0) {
            int colon = ip.lastIndexOf(':');
            if (colon < 0) {
                return false;
            }
            v6 = ip.substring(0, colon);
            v4 = ip.substring(colon + 1);
        }
        int groups = count(v6, ':') + 1;
        int octets = count(v4, '.') + 1;
        if (!((groups == 8 && octets == 1) || (groups == 6 && octets == 4))) {
            return false;
        }
        int start = 0;
        for (int i = 0; i <= v6.length(); i++) {
            if (i == v6.length() || v6.charAt(i) == ':') {
                int length = i - start;
                if (length == 0 || length > 4) {
                    return false;
                }
                for (int j = start; j < i; j++) {
                    if (!hex(v6.charAt(j))) {
                        return false;
                    }
                }
                start = i + 1;
            }
        }
        return octets == 1 || ipv4(v4, 0, v4.length(), false);
    }

    private static String uncompress(String ip) {
        int first = ip.indexOf("::");
        if (first < 0 || occurrences(ip, "::") != 1) {
            return ip;
        }
        String left = ip.substring(0, first);
        String right = ip.substring(first + 2);
        int c1 = left.isEmpty() ? -1 : count(left, ':');
        int c2 = right.isEmpty() ? -1 : count(right, ':');
        if (right.indexOf('.') >= 0) {
            c2++;
        }
        String fill;
        if (c1 == -1 && c2 == -1) {
            return "0:0:0:0:0:0:0:0";
        } else if (c1 == -1) {
            fill = repeat("0:", 7 - c2);
        } else if (c2 == -1) {
            fill = repeat(":0", 7 - c1);
        } else {
            String zeros = repeat("0:", 6 - c2 - c1);
            fill = zeros == null ? null : ":" + zeros;
        }
        return fill == null ? null : left + fill + right;
    }

    /** {@code unit} repeated; null for a negative count, which PHP refuses. */
    private static String repeat(String unit, int times) {
        return times < 0 ? null : unit.repeat(times);
    }

    /** Non-overlapping occurrences, counted as PHP's substr_count does. */
    private static int occurrences(String text, String part) {
        int found = 0;
        int i = text.indexOf(part);
        while (i >= 0) {
            found++;
            i = text.indexOf(part, i + part.length());
        }
        return found;
    }

    private static int count(String text, char c) {
        int found = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == c) {
                found++;
            }
        }
        return found;
    }

    /**
     * Four dot-separated octets between {@code start} and {@code end}; {@code leadingZeros}: one to three digits up to
     * 255 ("001" allowed), else canonical decimals only ("0" to "255").
     */
    private static boolean ipv4(String text, int start, int end, boolean leadingZeros) {
        int octets = 0;
        int octetStart = start;
        for (int i = start; i <= end; i++) {
            if (i == end || text.charAt(i) == '.') {
                int length = i - octetStart;
                if (length < 1 || length > 3 || (!leadingZeros && length > 1 && text.charAt(octetStart) == '0')) {
                    return false;
                }
                int value = 0;
                for (int j = octetStart; j < i; j++) {
                    char c = text.charAt(j);
                    if (!digit(c)) {
                        return false;
                    }
                    value = value * 10 + (c - '0');
                }
                if (value > 255) {
                    return false;
                }
                octets++;
                octetStart = i + 1;
            }
        }
        return octets == 4;
    }

    private static boolean label(String text, int start, int end) {
        if (start == end) {
            return false;
        }
        return text.charAt(start) != '-' && text.charAt(end - 1) != '-';
    }

    private static boolean localChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || digit(c) || "!#$%&'*+/=?^_`{|}~.-".indexOf(c) >= 0;
    }

    private static boolean domainChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || digit(c) || c == '-';
    }

    /** The length without one final line feed. */
    private static int withoutFinalLineFeed(String text) {
        int length = text.length();
        return length > 0 && text.charAt(length - 1) == '\n' ? length - 1 : length;
    }

    /** Reads exactly {@code count} digits at {@code at[0]} before {@code end}; their value, or -1. */
    private static int digits(String text, int[] at, int count, int end) {
        if (at[0] + count > end) {
            return -1;
        }
        int value = 0;
        for (int i = 0; i < count; i++) {
            char c = text.charAt(at[0] + i);
            if (!digit(c)) {
                return -1;
            }
            value = value * 10 + (c - '0');
        }
        at[0] += count;
        return value;
    }

    private static boolean expect(String text, int[] at, char c, int end) {
        if (at[0] < end && text.charAt(at[0]) == c) {
            at[0]++;
            return true;
        }
        return false;
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }

    private static boolean hex(char c) {
        return digit(c) || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
