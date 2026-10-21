package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.lib.wpblocks.WpBlocks;
import org.quarkpress.posts.SaveFilters;
import org.quarkpress.testing.gen.Properties;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares the save path of titles, content and excerpts with WordPress 7.1.3: {@code wp_strip_custom_css_from_blocks},
 * {@code convert_invalid_entities}, and the whole {@link KsesContentSanitizer} against WordPress's {@code
 * title_save_pre}, {@code content_save_pre} and {@code excerpt_save_pre} filters, for a caller with kses and CSS
 * stripping and for one without. Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class SaveParityTest {

    static final String[] WORDPRESS_FILES = {
        "wp-includes/formatting.php",
        "wp-includes/functions.php",
        "wp-includes/blocks.php",
        "wp-includes/class-wp-block-parser.php",
        "wp-includes/class-wp-block-parser-block.php",
        "wp-includes/class-wp-block-parser-frame.php",
        "wp-includes/block-supports/custom-css.php",
        "wp-includes/compat.php",
        "wp-includes/kses.php",
        "wp-includes/class-wp-token-map.php",
        "wp-includes/html-api/class-wp-html-attribute-token.php",
        "wp-includes/html-api/class-wp-html-span.php",
        "wp-includes/html-api/class-wp-html-text-replacement.php",
        "wp-includes/html-api/class-wp-html-decoder.php",
        "wp-includes/html-api/class-wp-html-doctype-info.php",
        "wp-includes/html-api/class-wp-html-unsupported-exception.php",
        "wp-includes/html-api/class-wp-html-tag-processor.php",
        "wp-includes/html-api/html5-named-character-references.php"
    };

    static final Path DRIVER = Path.of("src/test/php/posts-save-driver.php");

    static final Path SKIPS = Path.of("src/test/resources/wp-parity-save-skip.txt");

    static final SaveFilters FILTERED = new SaveFilters(true, true);

    /** Half of the 4810 comparisons of the first green run, rounded down to a multiple of 50. */
    static final int MIN_COMPARISONS = 2400;

    private static final String[] ENTITIES = {
        "&amp;",
        "&lt;",
        "&gt;",
        "&quot;",
        "&nbsp;",
        "&copy;",
        "&AMP;",
        "&bogus;",
        "&;",
        "&",
        "&#150;",
        "&#128;",
        "&#129;",
        "&#159;",
        "&#160;",
        "&#15;",
        "&#150",
        "&#0150;",
        "&#x96;",
        "&#X96;",
        "&#0;",
        "&#xD800;",
        "&#65536;",
        "&#x110000;",
        "&#8364;",
        "&#38;",
        "&#x26;",
        "&amp",
        "&#xe9;",
        "&eacute;",
        "&#1500;",
        "&#199;",
        "&#100;"
    };

    private static final String[] MARKUP = {
        "<b>",
        "</b>",
        "<em>x</em>",
        "<script>alert(1)</script>",
        "<img src=x onerror=alert(1)>",
        "<a href=\"javascript:alert(1)\">l</a>",
        "<a href=\"https://example.org/\" onclick=\"y()\">l</a>",
        "<!-- comment -->",
        "<!--",
        "-->",
        "<",
        "<3",
        "a<b",
        ">",
        "<p class=\"a\" style=\"color:red;position:fixed\">",
        "</p>",
        "<iframe src=x></iframe>",
        "<style>p{}</style>",
        "<div id=\"d\" data-x=\"1\">",
        "</div>",
        "<br/>",
        "<img src=\"a.png\" alt=\"q\">",
        "<span style=\"expression(alert(1))\">",
        "<table><tr><td>c</td></tr></table>",
        "<![CDATA[x]]>",
        "<?php echo 1; ?>",
        "<h1>",
        "<unknown>"
    };

    private static final String[] PLAIN = {
        "Hello", "x", "Tom", "  ", " ", "\t", "\n", "\r\n", "\013", "\0", "\\", "\"", "'", "/", "a--b", "100%", "[x]"
    };

    /** Attribute values that behave as WordPress in every comparison. */
    private static final String[] BLOCK_VALUES = {
        "\"x\"",
        "null",
        "1",
        "1.5",
        "true",
        "false",
        "[]",
        "\"\"",
        "0",
        "-0",
        "-0.0",
        "0.1",
        "1e2",
        "1E+2",
        "5e-324",
        "\"\\u00e9\\ud83d\\ude00\"",
        "\"a\\\\b\\/c\\\"d\"",
        "\"&#150;\"",
        "\"\\u2028\\u0000\"",
        "\"{}\"",
        "[1,[2,\"y\"]]",
        "\"--\"",
        "\"a & b\"",
        "\"&amp; &lt; &bogus;\"",
        "\"x&y=z\""
    };

    /**
     * Values that lib-wp-kses writes differently with its block-attribute filter, because lib-wp-blocks keeps big
     * integers exact, writes {@code {}} and numeric-key objects as objects, and writes a number that overflows to
     * infinity as {@code null} (see the Differences sections of the lib-wp-kses and lib-wp-blocks READMEs). A save of
     * an input holding one is compared under a {@code lib7-} case id.
     */
    private static final String[] KSES_REWRITTEN_VALUES = {
        "{}",
        "12345678901234567890",
        "-9223372036854775808",
        "-9223372036854775809",
        "1e400",
        "-1e400",
        "1" + "0".repeat(400),
        "{\"0\":\"x\",\"1\":\"y\"}",
        "{\"1\":\"x\"}",
        "[1,[2,{\"a\":{}}]]"
    };

    /**
     * Values with a {@code <} that lib-wp-kses's less-than filter takes for the start of a tag, so the block opener is
     * escaped and its closer closes nothing; the delimiters after it are neutralized by lib-wp-kses (its difference
     * 11). A save of an input holding one is compared under a {@code lib11-} case id.
     */
    private static final String[] MARKUP_VALUES = {
        "{\"a\":\"--<b>\"}", "\"<&>\"", "\"<script>alert(1)</script>\"", "\"a < b\"", "\"<b>x</b>\""
    };

    private static final String[] BLOCK_KEYS = {"k", "css", "spacing", "CSS", "label", "x-y", "-1", "", "a b"};

    private static final String[] STYLE_VALUES = {
        "{%s}", "\"css\"", "[\"css\"]", "{}", "[]", "null", "{\"css\":\"a\",\"css\":\"b\"}"
    };

    @Test
    void matchesWordPress() throws Exception {
        Path sources = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(sources);
        ParityRun run = new ParityRun(
                "posts-save", ParitySkipList.load(SKIPS), Path.of("target/wp-parity-posts-save-failures.json"));
        RandomGenerator random = Properties.random(Properties.seedOf("20261020-save-parity"));
        List<String> inputs = new ArrayList<>(fixedInputs());
        List<String> libKinds = new ArrayList<>();
        for (String fixed : inputs) {
            boolean rewritten = fixed.contains("\"typography\":{}")
                    || fixed.contains("\"list\"")
                    || fixed.contains("12345678901234567890")
                    || fixed.contains("1e400");
            libKinds.add(rewritten ? "lib7-" : "");
        }
        for (int i = 0; i < 250; i++) {
            Kind kind = new Kind();
            inputs.add(blockContent(random, random.nextInt(3) == 0, kind));
            libKinds.add(kind.prefix());
        }
        for (int i = 0; i < 350; i++) {
            inputs.add(text(random));
            libKinds.add("");
        }
        List<PhpOracle.Case> cases = new ArrayList<>();
        for (int i = 0; i < inputs.size(); i++) {
            cases.add(new PhpOracle.Case("css-" + i, op("strip_custom_css", inputs.get(i))));
            cases.add(new PhpOracle.Case("entities-" + i, op("convert_invalid_entities", inputs.get(i))));
            for (String field : List.of("title", "content", "excerpt")) {
                for (boolean filtered : List.of(true, false)) {
                    String id = saveId(libKinds.get(i), field, filtered, i);
                    cases.add(new PhpOracle.Case(id, saveOp(field, filtered, inputs.get(i))));
                }
            }
        }
        Map<String, JsonNode> out = oracle.run(DRIVER, cases);
        KsesContentSanitizer sanitizer = new KsesContentSanitizer();
        int libCompared = 0;
        int libDiffered = 0;
        int libEqual = 0;
        for (int i = 0; i < inputs.size(); i++) {
            String input = inputs.get(i);
            run.compare(
                    "css-" + i,
                    "wp_strip_custom_css_from_blocks",
                    value(out, "css-" + i),
                    WpBlocks.stripCustomCss(input));
            run.compare(
                    "entities-" + i,
                    "convert_invalid_entities",
                    value(out, "entities-" + i),
                    PostTextRules.convertInvalidEntities(input));
            for (boolean filtered : List.of(true, false)) {
                SaveFilters filters = filtered ? FILTERED : SaveFilters.NONE;
                String prefix = libKinds.get(i);
                for (String field : List.of("title", "content", "excerpt")) {
                    String id = saveId(prefix, field, filtered, i);
                    String quarkpress = switch (field) {
                        case "title" -> sanitizer.title(input, filters);
                        case "content" -> sanitizer.content(input, filters);
                        default -> sanitizer.excerpt(input, filters);
                    };
                    String wordpress = value(out, id);
                    String aspect = field + "_save_pre";
                    if (!prefix.isEmpty() && filtered) {
                        libCompared++;
                        if (wordpress.equals(quarkpress)) {
                            libEqual++;
                        } else {
                            BlockDifferences.Verdict verdict = BlockDifferences.check(
                                    wordpress,
                                    quarkpress,
                                    true,
                                    input.contains("1e400") || input.contains("0".repeat(400)));
                            if (verdict.accepted()
                                    && (verdict.rewrittenAttributes() || verdict.neutralizedDelimiters())) {
                                libDiffered++;
                                if (verdict.rewrittenAttributes()) {
                                    run.compare(id, aspect + ":lib7", wordpress, quarkpress);
                                }
                                if (verdict.neutralizedDelimiters()) {
                                    run.compare(id, aspect + ":lib11", wordpress, quarkpress);
                                }
                                continue;
                            }
                        }
                    }
                    run.compare(id, aspect, wordpress, quarkpress);
                }
            }
        }
        System.out.println("posts save parity: " + cases.size() + " cases, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences; " + libCompared
                + " filtered saves of inputs with values lib-wp-kses"
                + " rewrites or neutralizes: " + libEqual + " equal (counted above), " + libDiffered
                + " differ only as documented (skip-listed)");
        run.assertParity(MIN_COMPARISONS);
        assertThat(run.comparisons()).isGreaterThanOrEqualTo(MIN_COMPARISONS);
    }

    private static List<String> fixedInputs() {
        String emoji = new String(Character.toChars(0x1F600));
        String accent = new String(Character.toChars(0xE9));
        String han = new String(Character.toChars(0x65E5));
        return List.of(
                "<p>no blocks</p>",
                "<!-- wp:paragraph {\"style\":{\"css\":\"color:red\"}} --><p>x</p><!-- /wp:paragraph -->",
                "<!-- wp:spacer {\"style\":{\"css\":\"x\"}} /-->",
                "<!-- wp:group {\"style\":{\"css\":\"a\",\"color\":{\"text\":\"red\"}},\"x\":1} /-->",
                "<!-- wp:group {\"style\":{\"css\":\"a\"},\"label\":\"a--b<c>&d\\\"e\"} /-->",
                "<!-- wp:group {\"style\":{\"css\":\"a\",\"typography\":{}}} /-->",
                "<!-- wp:group {\"style\":{\"css\":\"a\",\"list\":{\"0\":\"x\",\"1\":\"y\"}}} /-->",
                "<!-- wp:group {\"style\":{\"css\":\"a\"},\"n\":1.0,\"m\":-0.5,\"big\":12345678901234567890} /-->",
                "<!-- wp:paragraph {\"style\":{\"css\":null}} --><p>x</p><!-- /wp:paragraph -->",
                "<!-- wp:paragraph {\"style\":[\"css\"]} /-->",
                "<!-- wp:paragraph {\"style\":{\"css\":\"a\"}}\n--><p>x</p><!-- /wp:paragraph -->",
                "a &#150; b &#128;&#129;&#159; &#160;",
                "  <em>Hello</em> <img src=x>  ",
                "A &amp; B < C",
                "x <3 y",
                "\t\n Title \013\0",
                "Title \0",
                "\0 Title",
                "<script>alert(1)</script>Hi",
                "Tom & Jerry",
                "<p onclick=\"x()\">Hi</p>",
                "<div class=\"a\" style=\"color:red\">x</div>",
                accent + " " + han + " " + emoji + " &#150; <b>" + han + "</b>",
                "<!-- wp:group {\"style\":{\"css\":\"a\"},\"n\":1e400} /-->",
                "<!-- wp:html --><script>alert(1)</script><!-- /wp:html -->",
                "<!-- wp:paragraph {\"content\":\"<script>x</script>\",\"style\":{\"css\":\"a\"}} --><p>x</p>"
                        + "<!-- /wp:paragraph -->");
    }

    /** Block markup: nested, void and closing delimiters with attributes of every shape. */
    private static String blockContent(RandomGenerator random, boolean wide, Kind kind) {
        StringBuilder content = new StringBuilder();
        int blocks = 1 + random.nextInt(4);
        for (int b = 0; b < blocks; b++) {
            StringBuilder members = new StringBuilder();
            if (random.nextInt(4) != 0) {
                members.append("\"style\":");
                String style = pick(random, STYLE_VALUES);
                if (style.equals("{}")) {
                    if (wide) {
                        kind.rewritten();
                    } else {
                        style = "[]";
                    }
                }
                if (style.contains("%s")) {
                    StringBuilder inner = new StringBuilder();
                    if (random.nextInt(4) != 0) {
                        inner.append("\"")
                                .append(pick(random, new String[] {"css", "css", "css", "CSS"}))
                                .append("\":")
                                .append(blockValue(random, wide, kind));
                    }
                    if (random.nextBoolean()) {
                        inner.append(inner.isEmpty() ? "" : ",")
                                .append("\"spacing\":")
                                .append(blockValue(random, wide, kind));
                    }
                    if (inner.isEmpty()) {
                        if (wide) {
                            kind.rewritten();
                        } else {
                            inner.append("\"spacing\":1");
                        }
                    }
                    style = style.formatted(inner);
                }
                members.append(style);
            }
            int more = random.nextInt(3);
            for (int m = 0; m < more; m++) {
                members.append(members.isEmpty() ? "" : ",")
                        .append('"')
                        .append(key(random, wide, kind))
                        .append("\":")
                        .append(blockValue(random, wide, kind));
            }
            String json =
                    members.isEmpty() ? "" : "{" + members + "}" + pick(random, new String[] {" ", " ", "\n", "  "});
            if (random.nextBoolean()) {
                content.append("<!-- wp:group ").append(json).append("/-->");
            } else {
                content.append("<!-- wp:paragraph ")
                        .append(json)
                        .append("--><p>t")
                        .append(b)
                        .append("</p>");
                if (random.nextInt(3) == 0) {
                    content.append(random.nextBoolean() ? pick(random, ENTITIES) : pick(random, MARKUP));
                }
                content.append("<!-- /wp:paragraph -->");
            }
            if (random.nextInt(4) == 0) {
                content.append(pick(random, ENTITIES));
            }
        }
        return content.toString();
    }

    private static String blockValue(RandomGenerator random, boolean wide, Kind kind) {
        if (wide && random.nextBoolean()) {
            if (random.nextBoolean()) {
                kind.rewritten();
                return pick(random, KSES_REWRITTEN_VALUES);
            }
            kind.markup();
            return pick(random, MARKUP_VALUES);
        }
        return pick(random, BLOCK_VALUES);
    }

    /** The key {@code "0"} makes a one-member object a list in PHP, which lib-wp-blocks does not. */
    private static String key(RandomGenerator random, boolean wide, Kind kind) {
        if (wide && random.nextInt(4) == 0) {
            kind.rewritten();
            return "0";
        }
        return pick(random, BLOCK_KEYS);
    }

    /** Which library difference, if any, a generated input actually contains. */
    private static final class Kind {
        private boolean rewritten;
        private boolean markup;

        void rewritten() {
            rewritten = true;
        }

        void markup() {
            markup = true;
        }

        String prefix() {
            return rewritten ? (markup ? "lib7+11-" : "lib7-") : markup ? "lib11-" : "";
        }
    }

    /** Text made of entities, markup, whitespace and Unicode, with edge whitespace more often than not. */
    private static String text(RandomGenerator random) {
        StringBuilder text = new StringBuilder();
        if (random.nextInt(3) == 0) {
            text.append(pick(random, new String[] {" ", "\t\n", "\013", "\0", "\r"}));
        }
        int parts = 1 + random.nextInt(6);
        for (int i = 0; i < parts; i++) {
            switch (random.nextInt(5)) {
                case 0 -> text.append(pick(random, ENTITIES));
                case 1, 2 -> text.append(pick(random, MARKUP));
                case 3 -> text.append(pick(random, PLAIN));
                default ->
                    text.append(new String(Character.toChars(
                            pick(random, new Integer[] {0xE9, 0x65E5, 0x1F600, 0x2028, 0xA0, 0x7F}))));
            }
        }
        if (random.nextInt(3) == 0) {
            text.append(pick(random, new String[] {" ", "\t\n", "\013", "\0", "\r"}));
        }
        return text.toString();
    }

    private static <T> T pick(RandomGenerator random, T[] choices) {
        return choices[random.nextInt(choices.length)];
    }

    private static String saveId(String prefix, String field, boolean filtered, int index) {
        return (filtered ? prefix : "") + field + (filtered ? "-filtered-" : "-plain-") + index;
    }

    private static String value(Map<String, JsonNode> out, String id) {
        return out.get(id).get("value").asText();
    }

    private static ObjectNode op(String name, String text) {
        return OracleJson.MAPPER.createObjectNode().put("op", name).put("text", text);
    }

    private static ObjectNode saveOp(String field, boolean filtered, String text) {
        return op("save_filter", text).put("field", field).put("filtered", filtered);
    }
}
