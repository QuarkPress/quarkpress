package org.quarkword.front.runtime;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * The URL and query-string primitives WordPress's redirects are built from, with PHP's results: {@code parse_url},
 * {@code parse_str}, {@code urlencode}, {@code rawurlencode}, {@code urldecode}, WordPress's {@code build_query},
 * {@code add_query_arg} and {@code remove_query_arg}, and the slash helpers. PHP strings are bytes: the text these
 * work on is a byte string, one {@code char} (0 to 255) per byte; {@link #bytes} and {@link #text} convert from and to
 * Java text through UTF-8.
 */
final class PhpUrls {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private PhpUrls() {}

    // ---- bytes ----------------------------------------------------------------------------------------------------

    /** The UTF-8 bytes of {@code text} as a byte string. */
    static String bytes(String text) {
        return new String(text.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1);
    }

    /** The byte string {@code bytes} read as UTF-8. */
    static String text(String bytes) {
        return new String(bytes.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8);
    }

    // ---- encoding -------------------------------------------------------------------------------------------------

    /** PHP's {@code urlencode}: letters, digits and {@code -_.} kept, a space as {@code +}, every other byte {@code %XX}. */
    static String urlencode(String bytes) {
        return encode(bytes, false);
    }

    /** PHP's {@code rawurlencode}: letters, digits and {@code -_.~} kept, every other byte {@code %XX}. */
    static String rawurlencode(String bytes) {
        return encode(bytes, true);
    }

    private static String encode(String bytes, boolean raw) {
        StringBuilder out = new StringBuilder(bytes.length() + 8);
        for (int i = 0; i < bytes.length(); i++) {
            char c = bytes.charAt(i);
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || (raw && c == '~')) {
                out.append(c);
            } else if (!raw && c == ' ') {
                out.append('+');
            } else {
                out.append('%').append(HEX[(c >> 4) & 0xF]).append(HEX[c & 0xF]);
            }
        }
        return out.toString();
    }

    /** PHP's {@code urldecode}: {@code +} is a space, {@code %XX} a byte; a {@code %} not followed by two hex digits stays. */
    static String urldecode(String bytes) {
        return decode(bytes, true);
    }

    /** PHP's {@code rawurldecode}: {@code %XX} is a byte, {@code +} stays. */
    static String rawurldecode(String bytes) {
        return decode(bytes, false);
    }

    private static String decode(String bytes, boolean plusIsSpace) {
        StringBuilder out = new StringBuilder(bytes.length());
        for (int i = 0; i < bytes.length(); i++) {
            char c = bytes.charAt(i);
            if (plusIsSpace && c == '+') {
                out.append(' ');
            } else if (c == '%'
                    && i + 2 < bytes.length()
                    && hex(bytes.charAt(i + 1)) >= 0
                    && hex(bytes.charAt(i + 2)) >= 0) {
                out.append((char) (hex(bytes.charAt(i + 1)) * 16 + hex(bytes.charAt(i + 2))));
                i += 2;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    static int hex(char c) {
        if (c >= '0' && c <= '9') {
            return c - '0';
        }
        if (c >= 'a' && c <= 'f') {
            return c - 'a' + 10;
        }
        if (c >= 'A' && c <= 'F') {
            return c - 'A' + 10;
        }
        return -1;
    }

    // ---- parse_url ------------------------------------------------------------------------------------------------

    /** The parts of a URL as PHP's {@code parse_url} gives them; a part absent from the URL is null. */
    static final class Url {
        String scheme;
        String user;
        String pass;
        String host;
        Integer port;
        String path;
        String query;
        String fragment;
    }

    /** PHP's {@code parse_url} for the URLs redirects meet; empty where PHP answers {@code false}. */
    static Optional<Url> parseUrl(String url) {
        Objects.requireNonNull(url, "url");
        Url parts = new Url();
        String rest = url;
        int colon = rest.indexOf(':');
        if (colon > 0 && schemeChars(rest.substring(0, colon))) {
            String after = rest.substring(colon + 1);
            int end = 0;
            while (end < after.length() && after.charAt(end) >= '0' && after.charAt(end) <= '9') {
                end++;
            }
            boolean portOnly = end > 0 && end < 6 && (end == after.length() || after.charAt(end) == '/');
            if (portOnly) {
                // "host:port[/path]": PHP reads a host and a port
                parts.host = rest.substring(0, colon);
                parts.port = Integer.parseInt(after.substring(0, end));
                if (parts.port > 65535) {
                    return Optional.empty();
                }
                return pathQueryFragment(after.substring(end), parts);
            }
            parts.scheme = rest.substring(0, colon);
            rest = after;
            if (!rest.startsWith("//")) {
                return pathQueryFragment(rest, parts);
            }
        } else if (!rest.startsWith("//")) {
            return pathQueryFragment(rest, parts);
        }
        rest = rest.substring(2);
        int authorityEnd = 0;
        while (authorityEnd < rest.length()
                && rest.charAt(authorityEnd) != '/'
                && rest.charAt(authorityEnd) != '?'
                && rest.charAt(authorityEnd) != '#') {
            authorityEnd++;
        }
        String authority = rest.substring(0, authorityEnd);
        int at = authority.lastIndexOf('@');
        if (at >= 0) {
            String userInfo = authority.substring(0, at);
            int split = userInfo.indexOf(':');
            if (split >= 0) {
                parts.user = userInfo.substring(0, split);
                parts.pass = userInfo.substring(split + 1);
            } else {
                parts.user = userInfo;
            }
            authority = authority.substring(at + 1);
        }
        int portColon = authority.lastIndexOf(':');
        if (portColon >= 0 && authority.indexOf(']', portColon) < 0) {
            String port = authority.substring(portColon + 1);
            authority = authority.substring(0, portColon);
            if (!port.isEmpty()) {
                if (port.length() > 5 || !digits(port)) {
                    return Optional.empty();
                }
                int number = Integer.parseInt(port);
                if (number > 65535) {
                    return Optional.empty();
                }
                parts.port = number;
            }
        }
        if (authority.isEmpty()) {
            return Optional.empty();
        }
        parts.host = authority;
        return pathQueryFragment(rest.substring(authorityEnd), parts);
    }

    private static Optional<Url> pathQueryFragment(String rest, Url parts) {
        int hash = rest.indexOf('#');
        if (hash >= 0) {
            parts.fragment = rest.substring(hash + 1);
            rest = rest.substring(0, hash);
        }
        int question = rest.indexOf('?');
        if (question >= 0) {
            parts.query = rest.substring(question + 1);
            rest = rest.substring(0, question);
        }
        if (!rest.isEmpty()) {
            parts.path = rest;
        }
        return Optional.of(parts);
    }

    private static boolean schemeChars(String scheme) {
        for (int i = 0; i < scheme.length(); i++) {
            char c = scheme.charAt(i);
            if (!((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '+'
                    || c == '.'
                    || c == '-')) {
                return false;
            }
        }
        return true;
    }

    static boolean digits(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < '0' || text.charAt(i) > '9') {
                return false;
            }
        }
        return true;
    }

    // ---- parse_str / build_query ----------------------------------------------------------------------------------

    /**
     * PHP's {@code parse_str}: the pairs split on {@code &}, names and values url-decoded, empty names dropped; in a
     * name, leading spaces are dropped and spaces and dots before any bracket become {@code _}; {@code a[]} appends to
     * an array and {@code a[k]} sets its key {@code k}, nested as written; a later value replaces an earlier one in
     * its place; a name nested more than 64 levels deep removes its variable. A value is a byte string or a nested map.
     */
    static Map<String, Object> parseStr(String query) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (query == null || query.isEmpty()) {
            return result;
        }
        int start = 0;
        while (start <= query.length()) {
            int end = query.indexOf('&', start);
            if (end < 0) {
                end = query.length();
            }
            String pair = query.substring(start, end);
            if (!pair.isEmpty()) {
                int equals = pair.indexOf('=');
                String name = urldecode(equals < 0 ? pair : pair.substring(0, equals));
                String value = equals < 0 ? "" : urldecode(pair.substring(equals + 1));
                register(result, name, value);
            }
            start = end + 1;
        }
        return result;
    }

    /** PHP's default {@code max_input_nesting_level}: a name with more brackets than this is dropped. */
    static final int MAX_NESTING = 64;

    @SuppressWarnings("unchecked")
    private static void register(Map<String, Object> target, String rawName, String value) {
        int skip = 0;
        while (skip < rawName.length() && rawName.charAt(skip) == ' ') {
            skip++;
        }
        String name = rawName.substring(skip);
        int bracket = name.indexOf('[');
        String base;
        List<String> indexes = new ArrayList<>();
        int levels = 0;
        if (bracket < 0) {
            base = underscores(name);
        } else {
            int close = name.indexOf(']', bracket);
            if (close < 0) {
                // an unmatched bracket is a plain character, turned into an underscore like a space or a dot
                base = underscores(name.substring(0, bracket)) + "_" + name.substring(bracket + 1);
            } else {
                base = underscores(name.substring(0, bracket));
                int at = bracket;
                while (at < name.length() && name.charAt(at) == '[') {
                    levels++;
                    int end = name.indexOf(']', at);
                    if (end < 0) {
                        break;
                    }
                    indexes.add(name.substring(at + 1, end));
                    at = end + 1;
                }
            }
        }
        if (base.isEmpty()) {
            return;
        }
        if (levels > MAX_NESTING) {
            // PHP gives up on the whole variable, dropping what earlier pairs set in it
            target.remove(base);
            return;
        }
        Map<String, Object> level = target;
        String key = base;
        for (String index : indexes) {
            Object existing = level.get(key);
            Map<String, Object> child;
            if (existing instanceof Map<?, ?> map) {
                child = (Map<String, Object>) map;
            } else {
                child = new LinkedHashMap<>();
                level.put(key, child);
            }
            level = child;
            key = index.isEmpty() ? Long.toString(nextIndex(child)) : index;
        }
        level.put(key, value);
    }

    private static String underscores(String name) {
        return name.replace(' ', '_').replace('.', '_');
    }

    private static long nextIndex(Map<String, Object> array) {
        long next = 0;
        for (String key : array.keySet()) {
            if (digits(key) && key.length() < 19) {
                next = Math.max(next, Long.parseLong(key) + 1);
            }
        }
        return next;
    }

    /** WordPress's {@code build_query}: {@code name=value} joined by {@code &}, nothing encoded; nested names bracketed. */
    static String buildQuery(Map<String, Object> data) {
        List<String> parts = new ArrayList<>();
        build(data, "", parts);
        return String.join("&", parts);
    }

    @SuppressWarnings("unchecked")
    private static void build(Map<String, Object> data, String prefix, List<String> parts) {
        for (Map.Entry<String, Object> entry : data.entrySet()) {
            String name = prefix.isEmpty() ? entry.getKey() : prefix + "%5B" + entry.getKey() + "%5D";
            Object value = entry.getValue();
            if (value == null) {
                continue;
            }
            if (value instanceof Map<?, ?> map) {
                build((Map<String, Object>) map, name, parts);
            } else {
                parts.add(name + "=" + value);
            }
        }
    }

    /** {@code data} with every value (nested ones too) passed through {@code urlencode}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> urlencodeDeep(Map<String, Object> data) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        data.forEach((key, value) -> encoded.put(
                key,
                value instanceof Map<?, ?> map ? urlencodeDeep((Map<String, Object>) map) : urlencode((String) value)));
        return encoded;
    }

    /** {@code data} with every name and value (nested ones too) passed through {@code rawurlencode}. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> rawurlencodeDeep(Map<String, Object> data) {
        Map<String, Object> encoded = new LinkedHashMap<>();
        data.forEach((key, value) -> encoded.put(
                rawurlencode(key),
                value instanceof Map<?, ?> map
                        ? rawurlencodeDeep((Map<String, Object>) map)
                        : rawurlencode((String) value)));
        return encoded;
    }

    // ---- add_query_arg / remove_query_arg -------------------------------------------------------------------------

    /**
     * WordPress's {@code add_query_arg} with an array of arguments: {@code uri}'s query (a bare query string when it
     * has no {@code ?}, no scheme and an {@code =}) is parsed and url-encoded again, {@code args} set into it (a null
     * value removes the name), and the query rebuilt with {@code build_query}, an {@code =} before an empty value
     * dropped.
     */
    static String addQueryArgs(Map<String, Object> args, String uri) {
        String fragment = "";
        int hash = uri.indexOf('#');
        String rest = uri;
        if (hash >= 0) {
            fragment = uri.substring(hash);
            rest = uri.substring(0, hash);
        }
        String protocol = "";
        if (startsWithIgnoreCase(rest, "http://")) {
            protocol = rest.substring(0, 7);
            rest = rest.substring(7);
        } else if (startsWithIgnoreCase(rest, "https://")) {
            protocol = rest.substring(0, 8);
            rest = rest.substring(8);
        }
        String base;
        String query;
        int question = rest.indexOf('?');
        if (question >= 0) {
            base = rest.substring(0, question + 1);
            query = rest.substring(question + 1);
        } else if (!protocol.isEmpty() || rest.indexOf('=') < 0) {
            base = rest + "?";
            query = "";
        } else {
            base = "";
            query = rest;
        }
        Map<String, Object> qs = urlencodeDeep(parseStr(query));
        args.forEach(qs::put);
        qs.values().removeIf(Objects::isNull);
        String built = trim(buildQuery(qs), '?');
        built = dropEqualsBeforeEmpty(built);
        String result = protocol + base + built + fragment;
        result = rtrim(result, '?');
        return result.replace("?#", "#");
    }

    /** WordPress's {@code remove_query_arg} for each of {@code names} in turn. */
    static String removeQueryArgs(String uri, String... names) {
        String result = uri;
        for (String name : names) {
            Map<String, Object> remove = new LinkedHashMap<>();
            remove.put(name, null);
            result = addQueryArgs(remove, result);
        }
        return result;
    }

    /** WordPress's {@code _remove_qs_args_if_not_in_url}: each of {@code names} that {@code url}'s query lacks. */
    static String removeQueryArgsNotIn(String query, String url, String... names) {
        String urlQuery = parseUrl(url).map(parts -> parts.query).orElse(null);
        if (urlQuery == null || urlQuery.isEmpty()) {
            return removeQueryArgs(query, names);
        }
        Map<String, Object> carried = parseStr(urlQuery);
        String result = query;
        for (String name : names) {
            if (carried.get(name) == null) {
                result = removeQueryArgs(result, name);
            }
        }
        return result;
    }

    /** {@code text} with every {@code =} followed by {@code &} or the end removed. */
    private static String dropEqualsBeforeEmpty(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '=' && (i + 1 == text.length() || text.charAt(i + 1) == '&')) {
                continue;
            }
            out.append(c);
        }
        return out.toString();
    }

    // ---- small string helpers -------------------------------------------------------------------------------------

    static boolean startsWithIgnoreCase(String text, String prefix) {
        return text.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    static String trim(String text, char c) {
        return rtrim(ltrim(text, c), c);
    }

    static String ltrim(String text, char c) {
        int start = 0;
        while (start < text.length() && text.charAt(start) == c) {
            start++;
        }
        return text.substring(start);
    }

    static String rtrim(String text, char c) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == c) {
            end--;
        }
        return text.substring(0, end);
    }

    /** WordPress's {@code trailingslashit}: trailing slashes and backslashes replaced by one slash. */
    static String trailingslashit(String text) {
        return untrailingslashit(text) + "/";
    }

    /** WordPress's {@code untrailingslashit}: trailing slashes and backslashes removed. */
    static String untrailingslashit(String text) {
        int end = text.length();
        while (end > 0 && (text.charAt(end - 1) == '/' || text.charAt(end - 1) == '\\')) {
            end--;
        }
        return text.substring(0, end);
    }
}
