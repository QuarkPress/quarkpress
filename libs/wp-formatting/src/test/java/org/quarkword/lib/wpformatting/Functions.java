package org.quarkword.lib.wpformatting;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** QuarkWord's answer for a case, in the JSON shape the php driver returns for the same {@code fn}. */
final class Functions {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private Functions() {}

    static JsonNode ours(String fn, String in, JsonNode args) {
        return switch (fn) {
            // Task 1
            case "html_split" -> texts(Formatting.htmlSplit(in));
            case "replace_in_html_tags" -> NODES.textNode(Formatting.replaceInHtmlTags(in, pairs(args.get("pairs"))));
            // Task 2
            case "autop" -> NODES.textNode(Formatting.autop(in, args.path("br").asBoolean(true)));
            case "autop_newline_helper" -> NODES.textNode(Autop.preserveNewlines(in));
            // Task 3
            case "texturize" -> NODES.textNode(texturize(args).apply(in));
            // Task 4
            case "convert_chars" -> NODES.textNode(Formatting.convertChars(in));
            case "capital_p" ->
                NODES.textNode(Formatting.capitalPDangit(
                        in, CapitalPMode.forFilter(args.get("filter").asText())));
            case "title_chain" -> NODES.textNode(titleChain(in));
            case "content_chain" -> NODES.textNode(contentChain(in));
            // Task 5
            case "smilies" -> NODES.textNode(smilies(args).convert(in));
            // Task 6
            case "trim_words" -> NODES.textNode(trimWords(in, args));
            case "html_excerpt" ->
                NODES.textNode(Excerpts.htmlExcerpt(in, args.get("count").asInt(), textOrNull(args, "more")));
            case "remove_footnotes" -> NODES.textNode(Excerpts.removeFootnotes(in));
            case "excerpt_chain" ->
                NODES.textNode(excerptChain(
                        args.path("generated").asBoolean(false)
                                ? generatedExcerpt(in, args.path("length").asInt())
                                : in));
            // Task 7
            case "force_balance" -> NODES.textNode(Formatting.forceBalanceTags(in));
            case "balance_tags" ->
                NODES.textNode(Formatting.balanceTags(
                        in,
                        args.path("force").asBoolean(false),
                        args.path("option").asBoolean(false)));
            // Task 8
            case "trim_excerpt" -> NODES.textNode(trimExcerpt(in, args));
            default -> throw new IllegalArgumentException("unknown fn " + fn);
        };
    }

    /** Every {@code fn} that {@link #ours} dispatches; each task adds its own. */
    static final Set<String> FNS = Set.of(
            // Task 1
            "html_split",
            "replace_in_html_tags",
            // Task 2
            "autop",
            "autop_newline_helper",
            // Task 3
            "texturize",
            // Task 4
            "convert_chars",
            "capital_p",
            "title_chain",
            "content_chain",
            // Task 5
            "smilies",
            // Task 6
            "trim_words",
            "html_excerpt",
            "remove_footnotes",
            "excerpt_chain",
            // Task 7
            "force_balance",
            "balance_tags",
            // Task 8
            "trim_excerpt");

    /** The shortcodes WordPress core registers; a texturize case gives its own list in {@code shortcodes}. */
    static final List<String> CORE_SHORTCODES =
            List.of("wp_caption", "caption", "gallery", "playlist", "audio", "video", "embed");

    /**
     * The texturizer a texturize case describes, as the driver sets WordPress up: {@code shortcodes} (the registry,
     * default {@link #CORE_SHORTCODES}), {@code cockney} (the whole cockney list), {@code noTexturizeTags} and
     * {@code noTexturizeShortcodes} (each replaces its list).
     */
    static Texturize texturize(JsonNode args) {
        Texturize.Builder builder = Texturize.builder()
                .registeredShortcodes(args.has("shortcodes") ? strings(args.get("shortcodes")) : CORE_SHORTCODES);
        if (args.has("cockney")) {
            builder.strings(TexturizeStrings.english().withCockney(pairs(args.get("cockney"))));
        }
        if (args.has("noTexturizeTags")) {
            builder.noTexturizeTags(strings(args.get("noTexturizeTags")));
        }
        if (args.has("noTexturizeShortcodes")) {
            builder.noTexturizeShortcodes(strings(args.get("noTexturizeShortcodes")));
        }
        return builder.build();
    }

    /** {@code the_title}: texturize, convert_chars and PHP trim at 10, then capital_P_dangit (simple) at 11. */
    static String titleChain(String in) {
        String text = Texturize.wordPress().apply(in);
        text = Formatting.phpTrim(Formatting.convertChars(text));
        return Formatting.capitalPDangit(text, CapitalPMode.SIMPLE);
    }

    /** {@code the_content} without shortcodes: wpautop and shortcode_unautop at 10, then capital_P_dangit at 11. */
    static String contentChain(String in) {
        return Formatting.capitalPDangit(Formatting.autop(in), CapitalPMode.JUDICIOUS);
    }

    /**
     * The converter a smilies case describes: {@code useSmilies} (default true) and {@code siteUrl} (default
     * {@code https://example.com}), whose smiley images WordPress serves from {@code /wp-includes/images/smilies/}.
     */
    static Smilies smilies(JsonNode args) {
        String siteUrl = args.path("siteUrl").asText("https://example.com");
        return Smilies.builder()
                .enabled(args.path("useSmilies").asBoolean(true))
                .imageBaseUrl(siteUrl + "/wp-includes/images/smilies/")
                .build();
    }

    /**
     * wp_trim_words as the driver calls it: {@code num} (default 55), {@code more} (absent means WordPress's default),
     * {@code type} (the locale's word-count type, default {@code words}) and {@code charset} (blog_charset, default
     * {@code UTF-8}).
     */
    static String trimWords(String in, JsonNode args) {
        return Excerpts.trimWords(
                in,
                args.path("num").asInt(Excerpts.DEFAULT_WORDS),
                textOrNull(args, "more"),
                WordCountType.fromWordPress(args.path("type").asText("words")),
                isUtf8Charset(args.path("charset").asText("UTF-8")));
    }

    /** WordPress's test of blog_charset: {@code utf8} or {@code utf-8} in any ASCII case, a final line feed allowed. */
    static boolean isUtf8Charset(String charset) {
        String name = charset.endsWith("\n") ? charset.substring(0, charset.length() - 1) : charset;
        return name.equalsIgnoreCase("utf8") || name.equalsIgnoreCase("utf-8");
    }

    /** The smiley converter of the excerpt chains: smilies on, images below {@code https://example.com}. */
    static final Smilies EXCERPT_SMILIES = Smilies.wordPress("https://example.com/wp-includes/images/smilies/");

    /**
     * {@code the_excerpt} without shortcodes, wp_replace_insecure_home_url and wp_filter_content_tags: wptexturize,
     * convert_smilies, convert_chars, wpautop and shortcode_unautop at 10.
     */
    static String excerptChain(String in) {
        String text = EXCERPT_SMILIES.convert(Texturize.wordPress().apply(in));
        return Formatting.autop(Formatting.convertChars(text));
    }

    /**
     * The excerpt wp_trim_excerpt generates from a post's content with {@code length} words, where the content filter
     * is the_content without shortcodes and blocks: wptexturize and wpautop at 10, capital_P_dangit at 11,
     * convert_smilies at 20.
     */
    static String generatedExcerpt(String content, int length) {
        ExcerptOptions options = ExcerptOptions.builder()
                .length(length)
                .contentFilter(text -> EXCERPT_SMILIES.convert(Formatting.capitalPDangit(
                        Formatting.autop(Texturize.wordPress().apply(text)), CapitalPMode.JUDICIOUS)))
                .build();
        return Excerpts.trimExcerpt("", content, options);
    }

    /**
     * wp_trim_excerpt as the driver calls it: the case's input is the post's content, {@code raw} the given excerpt
     * (default empty), {@code length} the excerpt_length (default 55), {@code more} the excerpt_more (absent means the
     * default), {@code type} and {@code charset} as for {@link #trimWords}; no shortcodes are registered, blocks are
     * left in, and the content filter is the_content without shortcodes (as in {@link #generatedExcerpt}).
     */
    static String trimExcerpt(String content, JsonNode args) {
        ExcerptOptions options = ExcerptOptions.builder()
                .length(args.path("length").asInt(Excerpts.DEFAULT_WORDS))
                .more(textOrNull(args, "more"))
                .wordCountType(WordCountType.fromWordPress(args.path("type").asText("words")))
                .utf8Charset(isUtf8Charset(args.path("charset").asText("UTF-8")))
                .contentFilter(text -> EXCERPT_SMILIES.convert(Formatting.capitalPDangit(
                        Formatting.autop(Texturize.wordPress().apply(text)), CapitalPMode.JUDICIOUS)))
                .build();
        return Excerpts.trimExcerpt(args.path("raw").asText(""), content, options);
    }

    private static String textOrNull(JsonNode args, String name) {
        JsonNode value = args.get(name);
        return value == null || value.isNull() ? null : value.asText();
    }

    static List<String> strings(JsonNode array) {
        List<String> items = new ArrayList<>();
        array.forEach(item -> items.add(item.asText()));
        return items;
    }

    static Map<String, String> pairs(JsonNode object) {
        Map<String, String> pairs = new LinkedHashMap<>();
        for (Iterator<Map.Entry<String, JsonNode>> it = object.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> e = it.next();
            pairs.put(e.getKey(), e.getValue().asText());
        }
        return pairs;
    }

    private static ArrayNode texts(Iterable<String> items) {
        ArrayNode array = NODES.arrayNode();
        items.forEach(array::add);
        return array;
    }
}
