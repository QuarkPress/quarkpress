package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WordPress's pagination headers for a collection: the totals, and one {@code Link} line holding the previous and the
 * next page's links. A link is the collection's URL with every query parameter of the request, built as WordPress
 * builds it: the parameters are written out (values URL-encoded, names as they are, array members as
 * {@code name%5Bkey%5D}, a parameter with an empty value as its name alone), that query is read back as PHP reads a
 * query string, {@code page} is set (in its place when present, else last) and the result is written out the same
 * way. Each step is linear in the size of the query.
 */
public final class Pagination {

    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    private Pagination() {}

    /**
     * {@code X-WP-Total}, {@code X-WP-TotalPages} (the total divided by {@code perPage}, rounded up) and, when there is
     * a previous or a next page, one {@code Link} value: {@code rel="prev"} (to {@code page - 1}, or to the last page
     * when that is lower, even 0) when {@code page} is above 1, then {@code rel="next"} when pages remain after
     * {@code page}, joined with {@code ", "}.
     *
     * @param base the collection's URL, without a query
     * @param query the request's query parameters as parsed
     */
    public static Map<String, List<String>> headers(long total, int perPage, int page, String base, ObjectNode query) {
        if (perPage < 1) {
            throw new IllegalArgumentException("per_page must be at least 1: " + perPage);
        }
        long totalPages = total <= 0 ? 0 : (total - 1) / perPage + 1;
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("X-WP-Total", List.of(Long.toString(total)));
        headers.put("X-WP-TotalPages", List.of(Long.toString(totalPages)));
        StringBuilder links = new StringBuilder();
        if (page > 1) {
            long previous = Math.min(page - 1L, totalPages);
            links.append('<').append(link(base, query, previous)).append(">; rel=\"prev\"");
        }
        if (totalPages > page) {
            if (!links.isEmpty()) {
                links.append(", ");
            }
            links.append('<').append(link(base, query, page + 1L)).append(">; rel=\"next\"");
        }
        if (!links.isEmpty()) {
            headers.put("Link", List.of(links.toString()));
        }
        return headers;
    }

    /** {@code base} with the request's query and {@code page} set to {@code target}. */
    static String link(String base, ObjectNode query, long target) {
        return link(base, query, target, new long[1]);
    }

    /**
     * {@link #link(String, ObjectNode, long)}, adding to {@code work[0]} the characters handled: each name built, each
     * value encoded, and each character the read-back looks at.
     */
    static String link(String base, ObjectNode query, long target, long[] work) {
        StringBuilder first = new StringBuilder();
        write(first, query, null, false, new boolean[1], work);
        // read back as PHP reads a query string: PhpQuery takes chars up to U+00FF as bytes
        ObjectNode reread = PhpQuery.parse(
                new String(first.toString().getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1), work);
        reread.put("page", Long.toString(target));
        StringBuilder link = new StringBuilder(base).append('?');
        write(link, reread, null, true, new boolean[1], work);
        return link.toString();
    }

    /**
     * Writes {@code params} as a query: values URL-encoded as PHP's urlencode does, names as they are, members of an
     * array under {@code prefix%5Bkey%5D}, a parameter whose value is empty as its name alone, pairs joined with
     * {@code &}.
     *
     * @param safeNames whether bytes of a name that may not appear in a header value (control characters and
     *     non-ASCII) are percent-encoded
     * @param started whether a pair was written already (so the next one needs a separator)
     * @param work counts the characters of each name built and each value encoded
     */
    private static void write(
            StringBuilder out, JsonNode params, String prefix, boolean safeNames, boolean[] started, long[] work) {
        for (Iterator<Map.Entry<String, JsonNode>> it = params.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> entry = it.next();
            String key = safeNames ? safeName(entry.getKey()) : entry.getKey();
            String name = prefix == null ? key : prefix + "%5B" + key + "%5D";
            work[0] += name.length();
            JsonNode value = entry.getValue();
            if (value.isObject()) {
                write(out, value, name, safeNames, started, work);
                continue;
            }
            if (value.isArray()) {
                ObjectNode indexed = RestJson.NODES.objectNode();
                for (int i = 0; i < value.size(); i++) {
                    indexed.set(Integer.toString(i), value.get(i));
                }
                write(out, indexed, name, safeNames, started, work);
                continue;
            }
            if (started[0]) {
                out.append('&');
            }
            started[0] = true;
            out.append(name);
            String encoded = RequestRestUrls.urlencode(PhpValues.toPhpString(value));
            work[0] += encoded.length();
            if (!encoded.isEmpty()) {
                out.append('=').append(encoded);
            }
        }
    }

    /** {@code name} with each UTF-8 byte below 0x20, 0x7F or above as {@code %XX}; other characters as they are. */
    private static String safeName(String name) {
        boolean plain = true;
        for (int i = 0; i < name.length() && plain; i++) {
            char c = name.charAt(i);
            plain = c >= 0x20 && c < 0x7F;
        }
        if (plain) {
            return name;
        }
        StringBuilder safe = new StringBuilder(name.length() * 3);
        for (byte b : name.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if (c >= 0x20 && c < 0x7F) {
                safe.append((char) c);
            } else {
                safe.append('%').append(HEX[c >> 4]).append(HEX[c & 0xF]);
            }
        }
        return safe.toString();
    }
}
