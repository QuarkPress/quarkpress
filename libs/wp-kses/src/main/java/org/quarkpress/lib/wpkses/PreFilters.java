package org.quarkpress.lib.wpkses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.quarkpress.lib.wpblocks.Block;
import org.quarkpress.lib.wpblocks.BlockParseException;
import org.quarkpress.lib.wpblocks.WpBlocks;

/** WordPress's default pre_kses filters: the less-than filter and the block-attribute filter. */
final class PreFilters {

    private static final String COMMENT_OPEN = "<!--";
    private static final String BLOCK_PREFIX = "wp:";
    /** Not computed yet, for the lazily found last attribute terminator. */
    private static final int UNKNOWN = -2;

    private PreFilters() {}

    /**
     * wp_pre_kses_less_than: each '<' with the text after it, up to the next '<' (not included), the next '>'
     * (included) or the end, is HTML-escaped when it contains no '>'.
     */
    static String lessThan(String s) {
        int lt = s.indexOf('<');
        if (lt < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length() + 16);
        int copied = 0;
        while (lt >= 0) {
            int end = lt + 1;
            while (end < s.length() && s.charAt(end) != '<' && s.charAt(end) != '>') {
                end++;
            }
            boolean closed = end < s.length() && s.charAt(end) == '>';
            if (closed) {
                end++;
            }
            out.append(s, copied, lt);
            String span = s.substring(lt, end);
            out.append(closed ? span : HtmlEscaper.escape(span));
            copied = end;
            lt = s.indexOf('<', end);
        }
        return out.append(s, copied, s.length()).toString();
    }

    /**
     * wp_pre_kses_block_attributes (filter_block_content): "--->" comment ends repaired, blocks parsed, every
     * attribute key and string value sanitized with {@code nested} (the same call without this filter), and
     * the blocks serialized again.
     *
     * <p>When the markup exceeds the block parser's limits (nesting deeper than 1024, more than a million blocks, or
     * open blocks whose recovery would repeat too much text), every block delimiter is neutralized instead, so no
     * attribute escapes the filter, and kses then sanitizes the content as ordinary text. WordPress's own output
     * grows quadratically there (README, difference #10).
     *
     * <p>Block text never contains a delimiter after a normal parse. It does in two cases that WordPress leaves
     * unfiltered: the text of the outermost block left open at the end, which repeats the delimiters of the blocks
     * open inside it, and the classic content after a closer that closes nothing. Those delimiters are neutralized
     * too, so no unfiltered attribute reaches a renderer (README, difference #11). Closers are neutralized in block
     * text, where a live one would close the wrong block, but kept in classic content, where WordPress keeps them.
     */
    static String blockAttributes(String content, KsesRun nested) {
        String text = content.contains(COMMENT_OPEN) && content.contains("--->")
                ? repairTripleDashComments(content)
                : content;
        List<Block> blocks;
        try {
            blocks = WpBlocks.parse(text);
        } catch (BlockParseException e) {
            return neutralizeBlockDelimiters(text, true);
        }
        List<Block> filtered = new ArrayList<>(blocks.size());
        for (Block block : blocks) {
            filtered.add(filterBlock(block, nested));
        }
        return WpBlocks.serializeCanonical(filtered);
    }

    /**
     * Each block delimiter comment gets its '<' written as "&lt;", so it is text and no block parser reads it as a
     * delimiter. Other comments are unchanged. A delimiter is what the block parser accepts: "<!--", whitespace, an
     * optional '/', "wp:", a block name, whitespace, then "-->", "/-->", or attribute JSON that some '}' followed by
     * whitespace and "-->" or "/-->" can end; "-->" may have extra dashes, which kses would remove. With
     * {@code closers} false, a plain closer (a leading '/', no attribute JSON, "-->") is left alone: it carries no
     * attributes.
     */
    static String neutralizeBlockDelimiters(String s, boolean closers) {
        StringBuilder out = null;
        int copied = 0;
        int lastTerminator = UNKNOWN;
        int open = s.indexOf(COMMENT_OPEN);
        while (open >= 0) {
            int pos = open + COMMENT_OPEN.length();
            int afterName = blockNameEnd(s, pos);
            if (afterName >= 0) {
                int next = skipBlockSpace(s, afterName);
                boolean delimiter = false;
                if (next > afterName) {
                    if (isCommentEnd(s, next)) {
                        delimiter = closers || !startsWithSlash(s, pos);
                    } else if (next < s.length() && s.charAt(next) == '/' && isCommentEnd(s, next + 1)) {
                        delimiter = true;
                    } else if (next < s.length() && s.charAt(next) == '{') {
                        if (lastTerminator == UNKNOWN) {
                            lastTerminator = lastAttributeTerminator(s);
                        }
                        delimiter = lastTerminator > next;
                    }
                }
                if (delimiter) {
                    if (out == null) {
                        out = new StringBuilder(s.length() + 64);
                    }
                    out.append(s, copied, open).append("&lt;");
                    copied = open + 1;
                }
            }
            open = s.indexOf(COMMENT_OPEN, pos);
        }
        return out == null ? s : out.append(s, copied, s.length()).toString();
    }

    /**
     * Two or more '-' and then '>' at {@code i}. The block parser accepts exactly "-->", but kses rebuilds a comment
     * that ends in more dashes into one that ends in "-->", so those count too.
     */
    private static boolean isCommentEnd(String s, int i) {
        int j = i;
        while (j < s.length() && s.charAt(j) == '-') {
            j++;
        }
        return j - i >= 2 && j < s.length() && s.charAt(j) == '>';
    }

    /**
     * Whether a comment that kses rebuilt, {@code "<!--" + text + "-->"}, starts like a block delimiter: whitespace,
     * an optional '/', "wp:", a block name and whitespace. Conservative: what follows is not checked, since the
     * attribute JSON could end in text that comes later.
     */
    static boolean startsLikeBlockDelimiter(String comment) {
        int afterName = blockNameEnd(comment, COMMENT_OPEN.length());
        return afterName >= 0 && skipBlockSpace(comment, afterName) > afterName;
    }

    /**
     * Whether {@code comment} is, on its own, exactly one block delimiter as the block parser reads it: it starts like
     * one, and "-->", "/-->" or the attribute JSON's first terminator ('}', whitespace, an optional '/', "-->") ends
     * it at its last character. Delimiters written by the block serializer always are, because it escapes "--" in
     * attribute JSON.
     */
    static boolean isCompleteBlockDelimiter(String comment) {
        int afterName = blockNameEnd(comment, COMMENT_OPEN.length());
        if (afterName < 0) {
            return false;
        }
        int next = skipBlockSpace(comment, afterName);
        if (next == afterName) {
            return false;
        }
        int n = comment.length();
        if (next < n && comment.charAt(next) == '{') {
            for (int i = comment.indexOf('}', next); i >= 0; i = comment.indexOf('}', i + 1)) {
                int after = skipBlockSpace(comment, i + 1);
                if (after > i + 1) {
                    int end = endOfExactClose(comment, after);
                    if (end >= 0) {
                        return end == n;
                    }
                }
            }
            return false;
        }
        return endOfExactClose(comment, next) == n;
    }

    /** The index after "-->" or "/-->" at {@code i}, or -1. */
    private static int endOfExactClose(String s, int i) {
        if (s.startsWith("-->", i)) {
            return i + 3;
        }
        return s.startsWith("/-->", i) ? i + 4 : -1;
    }

    /** Whether the whitespace after "<!--" at {@code pos} is followed by '/'. */
    private static boolean startsWithSlash(String s, int pos) {
        int i = skipBlockSpace(s, pos);
        return i < s.length() && s.charAt(i) == '/';
    }

    /** After "<!--" at {@code pos}: whitespace, an optional '/', "wp:" and a name; the index after it, or -1. */
    private static int blockNameEnd(String s, int pos) {
        int i = skipBlockSpace(s, pos);
        if (i == pos) {
            return -1;
        }
        if (i < s.length() && s.charAt(i) == '/') {
            i++;
        }
        if (!s.startsWith(BLOCK_PREFIX, i)) {
            return -1;
        }
        i = nameSegmentEnd(s, i + BLOCK_PREFIX.length());
        if (i >= 0 && i < s.length() && s.charAt(i) == '/') {
            i = nameSegmentEnd(s, i + 1);
        }
        return i;
    }

    /** A lowercase ASCII letter followed by lowercase letters, digits, '_' or '-'; the index after it, or -1. */
    private static int nameSegmentEnd(String s, int i) {
        if (i >= s.length() || s.charAt(i) < 'a' || s.charAt(i) > 'z') {
            return -1;
        }
        i++;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-')) {
                break;
            }
            i++;
        }
        return i;
    }

    /** The index of the last '}' followed by whitespace, an optional '/' and a comment end, or -1. */
    private static int lastAttributeTerminator(String s) {
        for (int i = s.lastIndexOf('}'); i >= 0; i = s.lastIndexOf('}', i - 1)) {
            int after = skipBlockSpace(s, i + 1);
            if (after > i + 1 && (isCommentEnd(s, after) || (s.startsWith("/", after) && isCommentEnd(s, after + 1)))) {
                return i;
            }
        }
        return -1;
    }

    private static int skipBlockSpace(String s, int i) {
        while (i < s.length() && isBlockSpace(s.charAt(i))) {
            i++;
        }
        return i;
    }

    /** The whitespace the block parser accepts in a delimiter: space, tab, LF, VT, FF and CR. */
    private static boolean isBlockSpace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\013' || c == '\f' || c == '\r';
    }

    /**
     * Each "<!--" + shortest text without a line feed + "--->" becomes "<!--" + that text without trailing '-' +
     * "-->".
     */
    static String repairTripleDashComments(String s) {
        Finder close = new Finder(s, "--->");
        Finder lineFeed = new Finder(s, "\n");
        StringBuilder out = new StringBuilder(s.length());
        int copied = 0;
        int open = s.indexOf(COMMENT_OPEN);
        while (open >= 0) {
            int end = close.next(open + 4);
            if (end < 0) {
                break;
            }
            int lf = lineFeed.next(open + 4);
            if (lf >= 0 && lf < end) {
                open = s.indexOf(COMMENT_OPEN, open + 1);
                continue;
            }
            int innerEnd = end;
            while (innerEnd > open + 4 && s.charAt(innerEnd - 1) == '-') {
                innerEnd--;
            }
            out.append(s, copied, open)
                    .append(COMMENT_OPEN)
                    .append(s, open + 4, innerEnd)
                    .append("-->");
            copied = end + 4;
            open = s.indexOf(COMMENT_OPEN, copied);
        }
        return out.append(s, copied, s.length()).toString();
    }

    private static Block filterBlock(Block block, KsesRun nested) {
        if (block.isFreeform()) {
            String html = block.innerHtml();
            String safe = neutralizeBlockDelimiters(html, false);
            return safe == html ? block : Block.freeform(safe);
        }
        boolean templatePart = "core/template-part".equals(block.name());
        Block.Builder builder = Block.builder(block.name());
        if (block.attributesValid()) {
            builder.attributes((ObjectNode) filterValue(block.attributes(), nested, templatePart));
        }
        int index = 0;
        for (String chunk : block.innerContent()) {
            if (chunk == null) {
                builder.inner(filterBlock(block.innerBlocks().get(index++), nested));
            } else {
                builder.text(neutralizeBlockDelimiters(chunk, true));
            }
        }
        return builder.build();
    }

    /** filter_block_kses_value: keys and strings sanitized at every depth; other values unchanged. */
    private static JsonNode filterValue(JsonNode value, KsesRun nested, boolean templatePart) {
        if (value.isTextual()) {
            return TextNode.valueOf(nested.kses(value.textValue()));
        }
        if (value.isArray()) {
            ArrayNode out = JsonNodeFactory.instance.arrayNode(value.size());
            for (JsonNode element : value) {
                out.add(filterValue(element, nested, templatePart));
            }
            return out;
        }
        if (value.isObject()) {
            ObjectNode out = ((ObjectNode) value).deepCopy();
            List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(value.properties());
            for (Map.Entry<String, JsonNode> entry : entries) {
                String key = entry.getKey();
                String filteredKey = nested.kses(key);
                JsonNode filteredValue = filterValue(entry.getValue(), nested, templatePart);
                if (templatePart) {
                    filteredValue = templatePartTagName(filteredValue, filteredKey, nested.allowlist());
                }
                if (!filteredKey.equals(key)) {
                    out.remove(key);
                }
                out.set(filteredKey, filteredValue);
            }
            return out;
        }
        return value;
    }

    /**
     * filter_block_core_template_part_attributes: a non-empty tagName must name an allowed element, else "".
     * WordPress fails with a TypeError for a non-empty array or object; QuarkPress writes "" (README, #8).
     */
    private static JsonNode templatePartTagName(JsonNode value, String key, Allowlist allowlist) {
        if (!key.equals("tagName") || phpEmpty(value)) {
            return value;
        }
        return value.isTextual() && allowlist.allows(value.textValue()) ? value : TextNode.valueOf("");
    }

    /** PHP's empty() for a decoded JSON value. */
    private static boolean phpEmpty(JsonNode value) {
        if (value.isNull()) {
            return true;
        }
        if (value.isBoolean()) {
            return !value.booleanValue();
        }
        if (value.isNumber()) {
            return value.doubleValue() == 0.0;
        }
        if (value.isTextual()) {
            return TextUtils.phpEmpty(value.textValue());
        }
        return value.isContainerNode() && value.isEmpty();
    }
}
