package org.quarkpress.restwp.runtime.oembed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Optional;
import org.quarkpress.lib.wpkses.Allowlist;
import org.quarkpress.lib.wpkses.Kses;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.TextFields;

/**
 * A provider answer's {@code html} as WordPress builds and post-processes it: built by type (a photo as a linked
 * image, a video or rich embed as the provider's string, a link as a titled link), the title put into every iframe,
 * the untrusted filter that keeps only one iframe (the last one closed right after its tag) and a blockquote only
 * when it opens the sanitized HTML (with a secret, hidden and sandboxed), and the stripping of newlines outside
 * {@code pre} elements.
 */
public final class OEmbedHtml {

    /** What an untrusted provider's HTML may keep before only its blockquote and iframe are picked out. */
    private static final Allowlist UNTRUSTED = Allowlist.builder()
            .attribute("a", "href")
            .element("blockquote")
            .attribute("iframe", "src")
            .attribute("iframe", "width")
            .attribute("iframe", "height")
            .attribute("iframe", "frameborder")
            .attribute("iframe", "marginwidth")
            .attribute("iframe", "marginheight")
            .attribute("iframe", "scrolling")
            .attribute("iframe", "title")
            .build();

    private static final String HIDDEN = "style=\"position: absolute; visibility: hidden;\"";

    private static final String SANDBOX =
            "class=\"wp-embedded-content\" sandbox=\"allow-scripts\" security=\"restricted\"";

    private OEmbedHtml() {}

    /**
     * The HTML for the provider's data: a {@code photo} (a string {@code url} and numeric {@code width} and
     * {@code height}) as a link to {@code url} around the image, a {@code video} or {@code rich} answer's string
     * {@code html}, a {@code link} as a link to {@code url} with the title as text (only with a title); empty for
     * anything else or missing pieces (WordPress's {@code false}).
     */
    public static Optional<String> build(ObjectNode data, String url) {
        String type = text(data.get("type"));
        JsonNode titleNode = data.get("title");
        String title = PhpValues.isEmpty(titleNode) ? "" : text(titleNode);
        switch (type) {
            case "photo" -> {
                JsonNode photo = data.get("url");
                JsonNode width = data.get("width");
                JsonNode height = data.get("height");
                if (photo == null
                        || !photo.isTextual()
                        || PhpValues.isEmpty(photo)
                        || PhpValues.isEmpty(width)
                        || !PhpValues.isNumeric(width)
                        || PhpValues.isEmpty(height)
                        || !PhpValues.isNumeric(height)) {
                    return Optional.empty();
                }
                return Optional.of("<a href=\"" + escapeUrl(url) + "\"><img src=\"" + escapeUrl(photo.textValue())
                        + "\" alt=\"" + escapeHtml(title) + "\" width=\"" + escapeHtml(PhpValues.toPhpString(width))
                        + "\" height=\"" + escapeHtml(PhpValues.toPhpString(height)) + "\" /></a>");
            }
            case "video", "rich" -> {
                JsonNode html = data.get("html");
                return html != null && html.isTextual() ? Optional.of(html.textValue()) : Optional.empty();
            }
            case "link" -> {
                return title.isEmpty()
                        ? Optional.empty()
                        : Optional.of("<a href=\"" + escapeUrl(url) + "\">" + escapeHtml(title) + "</a>");
            }
            default -> {
                return Optional.empty();
            }
        }
    }

    /**
     * The HTML with a title in every {@code <iframe }: the first iframe's own {@code title} attribute when it has a
     * non-empty one (PHP's test, so {@code "0"} does not count), else {@code title}; an empty title leaves the HTML
     * as it is. The old attribute is removed from the first iframe only, and {@code title="..."} is inserted right
     * after the tag name of every iframe, as WordPress does.
     */
    public static String titled(String html, String title) {
        int first = indexOfIgnoreCase(html, "<iframe ", 0);
        if (first < 0) {
            return html;
        }
        String own = attribute(html, first, "title");
        String text = own != null && !own.isEmpty() && !own.equals("0") ? own : title;
        if (text == null || text.isEmpty()) {
            return html;
        }
        String escaped = escapeHtml(text);
        StringBuilder out = new StringBuilder(html.length() + 32);
        int at = 0;
        int open;
        while ((open = indexOfIgnoreCase(html, "<iframe ", at)) >= 0) {
            int close = html.indexOf('>', open);
            if (close < 0) {
                break;
            }
            String tag = html.substring(open, close);
            if (open == first) {
                tag = removeAttribute(tag, "title");
            }
            out.append(html, at, open);
            out.append(tag, 0, 7)
                    .append(" title=\"")
                    .append(escaped)
                    .append('"')
                    .append(tag, 7, tag.length());
            at = close;
        }
        out.append(html, at, html.length());
        return out.toString();
    }

    /**
     * WordPress's filter for a provider it does not trust: the HTML reduced to {@code a[href]}, {@code blockquote}
     * and {@code iframe} with its sizes, borders, scrolling and title; then only one iframe (the last {@code <iframe}
     * followed by {@code ></iframe>}, nothing inside it, as WordPress's extraction requires) and, when the sanitized
     * HTML opens with {@code <blockquote>} closed before that iframe, that blockquote are kept; with a {@code src},
     * {@code #?secret=} with {@code secret} goes on it and {@code data-secret} right after it and on the blockquote;
     * with a blockquote the iframe is hidden and the blockquote gets the {@code wp-embedded-content} class; and the
     * iframe is sandboxed. Empty when no iframe closes right after its tag (WordPress's {@code false}).
     */
    public static Optional<String> untrusted(String html, String secret) {
        String clean = Kses.wordPress().sanitize(html, UNTRUSTED);
        // the last <iframe whose tag is followed by ></iframe> at once (an iframe with content is no match)
        int iframe = -1;
        int iframeEnd = -1;
        for (int at = lastIndexOfIgnoreCase(clean, "<iframe");
                at >= 0;
                at = lastIndexOfIgnoreCase(clean.substring(0, at), "<iframe")) {
            int end = indexOfIgnoreCase(clean, "></iframe>", at);
            if (end >= 0) {
                iframe = at;
                iframeEnd = end + 1;
                break;
            }
        }
        if (iframe < 0) {
            return Optional.empty();
        }
        String frame = clean.substring(iframe, iframeEnd + "</iframe>".length());
        String quote = "";
        if (regionMatchesIgnoreCase(clean, 0, "<blockquote>")) {
            int blockquoteEnd = indexOfIgnoreCase(clean, "</blockquote>", 0);
            if (blockquoteEnd >= 0 && blockquoteEnd + "</blockquote>".length() <= iframe) {
                quote = clean.substring(0, blockquoteEnd + "</blockquote>".length());
            }
        }
        int src = indexOfIgnoreCase(frame, " src=\"", 0);
        if (src >= 0) {
            int valueEnd = frame.indexOf('"', src + 6);
            if (valueEnd >= 0) {
                String value = frame.substring(src + 6, valueEnd);
                frame = frame.substring(0, src + 6)
                        + escapeUrl(value + "#?secret=" + secret)
                        + "\" data-secret=\"" + secret
                        + frame.substring(valueEnd);
                quote = replaceTag(quote, "<blockquote", "<blockquote data-secret=\"" + secret + "\"");
            }
        }
        if (!quote.isEmpty()) {
            frame = replaceTag(frame, "<iframe", "<iframe " + HIDDEN);
            quote = replaceTag(quote, "<blockquote", "<blockquote class=\"wp-embedded-content\"");
        }
        frame = replaceTag(frame, "<iframe", "<iframe " + SANDBOX);
        return Optional.of(quote + frame);
    }

    /**
     * The HTML without line feeds (and the carriage returns before them), except inside closed {@code pre} elements;
     * an HTML without a line feed is left as it is, a lone carriage return included.
     */
    public static String stripNewlines(String html) {
        if (html.indexOf('\n') < 0) {
            return html;
        }
        StringBuilder out = new StringBuilder(html.length());
        int i = 0;
        while (i < html.length()) {
            if (startsPre(html, i)) {
                int end = indexOfIgnoreCase(html, "</pre>", i);
                if (end >= 0) {
                    int stop = end + "</pre>".length();
                    out.append(html, i, stop);
                    i = stop;
                    continue;
                }
            }
            char c = html.charAt(i);
            if (c == '\r' && i + 1 < html.length() && html.charAt(i + 1) == '\n') {
                i += 2;
                continue;
            }
            if (c != '\n') {
                out.append(c);
            }
            i++;
        }
        return out.toString();
    }

    /** Whether a {@code <pre} tag starts at {@code at}: {@code <pre} followed by {@code >} or white space. */
    private static boolean startsPre(String html, int at) {
        if (!regionMatchesIgnoreCase(html, at, "<pre")) {
            return false;
        }
        int next = at + 4;
        return next >= html.length() || html.charAt(next) == '>' || Character.isWhitespace(html.charAt(next));
    }

    /** The tag text with every {@code name} attribute (any case, quoted or bare) and the space before it removed. */
    private static String removeAttribute(String tag, String name) {
        StringBuilder out = new StringBuilder(tag.length());
        int i = 0;
        while (i < tag.length()) {
            if (Character.isWhitespace(tag.charAt(i)) && attributeAt(tag, i + 1, name)) {
                int end = attributeEnd(tag, i + 1 + name.length());
                i = end;
                continue;
            }
            out.append(tag.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** Whether {@code name=} starts at {@code at} (any case, spaces around the equals sign allowed). */
    private static boolean attributeAt(String tag, int at, String name) {
        if (!regionMatchesIgnoreCase(tag, at, name)) {
            return false;
        }
        int i = at + name.length();
        while (i < tag.length() && Character.isWhitespace(tag.charAt(i))) {
            i++;
        }
        return i < tag.length() && tag.charAt(i) == '=';
    }

    /** The index after an attribute's value, {@code from} being just after its name. */
    private static int attributeEnd(String tag, int from) {
        int i = from;
        while (i < tag.length() && tag.charAt(i) != '=') {
            i++;
        }
        i++;
        while (i < tag.length() && Character.isWhitespace(tag.charAt(i))) {
            i++;
        }
        if (i < tag.length() && (tag.charAt(i) == '"' || tag.charAt(i) == '\'')) {
            int close = tag.indexOf(tag.charAt(i), i + 1);
            return close < 0 ? tag.length() : close + 1;
        }
        while (i < tag.length() && !Character.isWhitespace(tag.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The value of the attribute {@code name} of the tag starting at {@code open}, or null when it has none. */
    private static String attribute(String html, int open, String name) {
        int close = html.indexOf('>', open);
        String tag = html.substring(open, close < 0 ? html.length() : close);
        for (int i = 0; i < tag.length(); i++) {
            if (Character.isWhitespace(tag.charAt(i)) && attributeAt(tag, i + 1, name)) {
                int eq = tag.indexOf('=', i + 1);
                int start = eq + 1;
                while (start < tag.length() && Character.isWhitespace(tag.charAt(start))) {
                    start++;
                }
                if (start < tag.length() && (tag.charAt(start) == '"' || tag.charAt(start) == '\'')) {
                    int end = tag.indexOf(tag.charAt(start), start + 1);
                    return tag.substring(start + 1, end < 0 ? tag.length() : end);
                }
                int end = start;
                while (end < tag.length() && !Character.isWhitespace(tag.charAt(end))) {
                    end++;
                }
                return tag.substring(start, end);
            }
        }
        return null;
    }

    /** Every occurrence of {@code tag} (any case) replaced by {@code replacement}. */
    private static String replaceTag(String html, String tag, String replacement) {
        StringBuilder out = new StringBuilder(html.length() + 64);
        int at = 0;
        int found;
        while ((found = indexOfIgnoreCase(html, tag, at)) >= 0) {
            out.append(html, at, found).append(replacement);
            at = found + tag.length();
        }
        out.append(html, at, html.length());
        return out.toString();
    }

    /** esc_attr and esc_html: the entities for {@code & < > " '}, an existing reference not encoded again. */
    static String escapeHtml(String text) {
        return Kses.specialChars(text).replace("\"", "&quot;").replace("'", "&#039;");
    }

    /** esc_url for display: the sanitized URL with {@code &} and {@code '} as entities. */
    static String escapeUrl(String url) {
        String clean = TextFields.url(url).replace("&amp;", "&").replace("&#038;", "&");
        return clean.replace("&", "&#038;").replace("'", "&#039;");
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? node.textValue() : "";
    }

    static int indexOfIgnoreCase(String text, String part, int from) {
        int last = text.length() - part.length();
        for (int i = Math.max(from, 0); i <= last; i++) {
            if (regionMatchesIgnoreCase(text, i, part)) {
                return i;
            }
        }
        return -1;
    }

    private static int lastIndexOfIgnoreCase(String text, String part) {
        for (int i = text.length() - part.length(); i >= 0; i--) {
            if (regionMatchesIgnoreCase(text, i, part)) {
                return i;
            }
        }
        return -1;
    }

    static boolean regionMatchesIgnoreCase(String text, int at, String part) {
        return text.regionMatches(true, at, part, 0, part.length());
    }
}
