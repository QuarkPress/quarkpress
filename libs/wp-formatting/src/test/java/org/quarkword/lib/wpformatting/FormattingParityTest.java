package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkword.testing.gen.Gen;
import org.quarkword.testing.wporacle.DeltaShrinker;
import org.quarkword.testing.wporacle.GeneratedCases;
import org.quarkword.testing.wporacle.ParityRun;
import org.quarkword.testing.wporacle.ParitySkipList;
import org.quarkword.testing.wporacle.PhpOracle;
import org.quarkword.testing.wporacle.WordPressRelease;
import org.quarkword.testing.wporacle.WordPressSources;

/**
 * Compares this library with WordPress 7.1.3's formatting functions (testing/wp-oracle) on every case of the
 * shared case file, on large programmatic inputs and on generated inputs. A recorded output that differs from
 * WordPress's answer is reported as the aspect "recorded". Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class FormattingParityTest {

    static final Path DRIVER = Path.of("src/test/php/formatting-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/compat.php",
        "wp-includes/formatting.php",
        "wp-includes/kses.php",
        "wp-includes/shortcodes.php",
        "wp-includes/functions.php",
        "wp-includes/blocks.php",
        "wp-includes/class-wp-token-map.php",
        "wp-includes/html-api/html5-named-character-references.php",
        "wp-includes/html-api/class-wp-html-decoder.php"
    };
    static final int MIN_COMPARISONS = 19_500;
    static final int SHRUNK_CASES = 3;
    static final int SHRINK_ROUNDS = 12;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** One case for the oracle; {@code out} is the recorded output or {@code null}. */
    record ParityCase(String id, String fn, String in, ObjectNode args, JsonNode out) {}

    @Test
    void matchesWordPress() throws Exception {
        Path wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(wordpress);

        List<ParityCase> cases = new ArrayList<>();
        for (RecordedCase c : RecordedCases.load()) {
            if (c.oracle()) {
                cases.add(new ParityCase(c.id(), c.fn(), c.in(), c.args(), c.out()));
            }
        }
        cases.addAll(programmaticCases());
        cases.addAll(generatedCases());

        List<PhpOracle.Case> batch = new ArrayList<>();
        for (ParityCase c : cases) {
            batch.add(new PhpOracle.Case(c.id(), payload(c)));
        }
        Map<String, JsonNode> results = oracle.run(DRIVER, batch);

        ParityRun run = new ParityRun("lib-wp-formatting", ParitySkipList.load(SKIPS), FAILURES);
        List<String> throwsSeen = new ArrayList<>();
        Map<String, ParityCase> generated = new LinkedHashMap<>();
        for (ParityCase c : cases) {
            JsonNode wp = results.get(c.id());
            assertThat(wp).as("WordPress's answer for %s", c.id()).isNotNull();
            if (c.id().startsWith(GENERATED)) {
                run.generated(c.id(), c.in());
                generated.put(c.id(), c);
            }
            run.compare(c.id(), c.fn(), wp.get("out"), ours(c, throwsSeen));
            if (c.out() != null) {
                run.compare(c.id(), "recorded", wp.get("out"), c.out());
            }
        }
        for (String id : run.failedGeneratedCases().stream().limit(SHRUNK_CASES).toList()) {
            ParityCase c = generated.get(id);
            run.shrunk(
                    id, DeltaShrinker.shrink(c.in(), candidates -> stillDiffer(oracle, c, candidates), SHRINK_ROUNDS));
        }
        System.out.println("lib-wp-formatting parity: " + cases.size() + " cases, " + run.comparisons()
                + " comparisons, " + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
        // a skip entry may excuse a difference from WordPress, never a QuarkWord exception
        assertThat(throwsSeen).as("cases where QuarkWord threw").isEmpty();
    }

    /**
     * QuarkWord's answer; an exception is an answer too, so a generated input that makes us throw is reported as a
     * difference and recorded in {@code throwsSeen}, which the run asserts empty whatever the skip list says.
     */
    private static JsonNode ours(ParityCase c, List<String> throwsSeen) {
        try {
            return Functions.ours(c.fn(), c.in(), c.args());
        } catch (RuntimeException e) {
            throwsSeen.add(c.id() + " (" + c.fn() + "): " + e);
            return NODES.textNode("threw " + e);
        }
    }

    /** One oracle run for a shrinking round: which candidates, with the case's fn and args, still differ. */
    private static List<Boolean> stillDiffer(PhpOracle oracle, ParityCase c, List<String> candidates) {
        try {
            List<PhpOracle.Case> batch = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                // a cut through a surrogate pair cannot travel as JSON to PHP; such a candidate does not count
                if (!hasUnpairedSurrogate(candidates.get(i))) {
                    batch.add(new PhpOracle.Case("shrink-" + i, payload(shrinkCase(c, candidates.get(i)))));
                }
            }
            Map<String, JsonNode> results = oracle.run(DRIVER, batch);
            List<Boolean> verdicts = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                JsonNode wp = results.get("shrink-" + i);
                if (wp == null) {
                    verdicts.add(false);
                    continue;
                }
                ParityRun probe =
                        new ParityRun("shrink", ParitySkipList.empty(), Path.of("target/wp-parity-shrink.json"));
                ParityCase candidate = shrinkCase(c, candidates.get(i));
                probe.compare(candidate.id(), candidate.fn(), wp.get("out"), ours(candidate, new ArrayList<>()));
                verdicts.add(!probe.mismatches().isEmpty());
            }
            return verdicts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static ParityCase shrinkCase(ParityCase c, String in) {
        return new ParityCase(c.id(), c.fn(), in, c.args(), null);
    }

    private static boolean hasUnpairedSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                    i++;
                } else {
                    return true;
                }
            } else if (Character.isLowSurrogate(ch)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectNode payload(ParityCase c) {
        ObjectNode in = NODES.objectNode();
        in.put("fn", c.fn());
        in.put("in", c.in());
        in.set("args", c.args());
        return in;
    }

    /** Inputs too large for the case file; Tasks 2 to 7 add theirs. */
    private static List<ParityCase> programmaticCases() {
        List<ParityCase> cases = new ArrayList<>();
        // Task 2: the readers' large and hostile wpautop inputs
        cases.add(autop("fa-g153", "Line of text\n".repeat(20_000) + "\n" + "Para\n\n".repeat(20_000)));
        cases.add(autop("fa-g154", "a" + " ".repeat(200_000) + "b"));
        cases.add(autop("fa-g155", "< x\n\n".repeat(50_000)));
        cases.add(autop("fa-g156", "<script>".repeat(20_000) + "x\n".repeat(1_000)));
        cases.add(autop("fa-g157", "<blockquote " + "\"a\" ".repeat(50_000)));
        cases.add(autop("fa-g159", "word ".repeat(1_000_000)));
        // the largest <li paragraph WordPress still formats: one more char and its backtrack limit gives ""
        cases.add(autop("fa-li-1mb", "<li>" + "x".repeat(999_999)));
        cases.add(autop("fa-g160", "<li>" + "x".repeat(3_000_000)));
        // repeated "<p><li" hits the same limit much sooner: 27,778 still formats, 27,779 (166,674 chars) gives ""
        cases.add(autop("fa-p-li-27778", "<p><li".repeat(27_778)));
        cases.add(autop("fa-p-li-100k", "<p><li".repeat(100_000)));
        // a script, style, svg or math opener without a closer: 999,992 chars after it still format, 999,993 give ""
        cases.add(autop("fa-script-999992", "<script>" + "x".repeat(999_992)));
        cases.add(autop("fa-script-1mb", "<script>" + "x".repeat(1_000_000)));
        cases.add(autop("fa-style-999992", "<style>" + "x".repeat(999_992)));
        cases.add(autop("fa-style-1mb", "<style>" + "x".repeat(1_000_000)));
        // with blank lines after the opener far less is needed: 111,111 "x\n\n" still format, 111,112 give ""
        cases.add(autop("fa-svg-333k-below", "<svg>" + "x\n\n".repeat(111_111)));
        cases.add(autop("fa-svg-333k", "<svg>" + "x\n\n".repeat(111_112)));
        // Task 3: the readers' hostile wptexturize inputs and larger ones (WordPress completes on all of them)
        cases.add(texturize("ft-g179", "\"a' ".repeat(20_000), false));
        cases.add(texturize("ft-quotes-1mb", "\"a' ".repeat(250_000), false));
        cases.add(texturize("ft-g180a", "<" + "a".repeat(200_000), false));
        cases.add(texturize("ft-g180b", "<!--" + "-".repeat(200_000), false));
        cases.add(texturize("ft-g180c", "[code " + "a".repeat(100_000), true));
        cases.add(texturize("ft-g181", "<pre>".repeat(10_000) + "\"a\"", false));
        cases.add(texturize("ft-dashes", "-".repeat(100_001), false));
        cases.add(texturize("ft-lt-1mb", "a<".repeat(500_000), false));
        cases.add(texturize("ft-singles-1mb", "'".repeat(1_000_000), false));
        cases.add(texturize("ft-doubles-1mb", "\"".repeat(1_000_000), false));
        cases.add(texturize("ft-amps-1mb", "&".repeat(1_000_000), false));
        cases.add(texturize("ft-brackets-1mb", "[".repeat(1_000_000), true));
        cases.add(texturize("ft-single-sentences", "' a' b9' ".repeat(125_000), false));
        cases.add(texturize("ft-double-sentences", "\"a\" 9\" ".repeat(125_000), false));
        cases.add(texturize("ft-mixed", "\"a\" 'b' 9' 10\" -- x 1x2 & ".repeat(30_000), false));
        // a comment with 999,995 hyphens still splits; with 999,996 WordPress's split fails and PHP throws (D5)
        cases.add(texturize("ft-comment-999995", "<!--" + "-".repeat(999_995) + "-->\"a\"", false));
        cases.add(texturize("ft-comment-1m", "<!--" + "-".repeat(1_000_000) + "-->\"a\"", false));
        // a second factor of 999,998 digits before a letter still works; 999,999 make WordPress return "" (D6)
        cases.add(texturize("ft-times-999998", "1x1" + "1".repeat(999_997) + "a", false));
        cases.add(texturize("ft-times-1m", "1x1" + "1".repeat(1_000_000) + "a", false));
        // Task 4: convert_chars and capital_P_dangit on large inputs
        cases.add(convertChars("t4-amps-1mb", "&".repeat(1_000_000)));
        cases.add(convertChars("t4-names-1mb", "&ab;&#1;&&amp;&\u00e9&".repeat(60_000)));
        cases.add(capitalP("t4-wordpress-title", "Wordpress".repeat(120_000), "the_title"));
        cases.add(capitalP("t4-wordpress-content", "Wordpress".repeat(120_000), "the_content"));
        cases.add(capitalP(
                "t4-prefixes-content", " Wordpress(Wordpress>Wordpress&#8220;Wordpress".repeat(30_000), "the_content"));
        cases.add(new ParityCase(
                "t4-title-1mb",
                "title_chain",
                " \"Wordpress\" & it's -- R&D ".repeat(35_000),
                NODES.objectNode(),
                null));
        // Task 5: convert_smilies on large inputs
        cases.add(smilies("t5-spaced-1mb", ":) ".repeat(350_000)));
        cases.add(smilies("t5-colons-1mb", ":".repeat(1_000_000)));
        cases.add(smilies("t5-pres-1mb", "<pre>".repeat(200_000) + ":)"));
        cases.add(smilies("t5-lt-1mb", "<".repeat(1_000_000)));
        cases.add(smilies("t5-tags-1mb", "<b>:)</b>".repeat(110_000)));
        cases.add(smilies("t5-touching-1mb", ":):)".repeat(250_000)));
        cases.add(smilies("t5-mixed-1mb", ":mrgreen: &nbsp;:-|&nbsp;\n<code>:)</code>:???:\u00a0".repeat(20_000)));
        cases.add(
                smilies("t5-all-codes-lines", String.join("\n", Smilies.codes()).repeat(5_000)));
        // Task 6: excerpts on large inputs (the shapes of HostileInputTest.excerptsAreLinear)
        cases.add(trimWords("t6-spaces-1mb", " ".repeat(1_000_000) + "a", 55, "words"));
        cases.add(trimWords("t6-words-1mb", "w ".repeat(500_000), Integer.MAX_VALUE, "words"));
        cases.add(trimWords("t6-unlimited-1mb", "w ".repeat(500_000), -1, "words"));
        cases.add(trimWords(
                "t6-chars-1mb", "a\uD83D\uDE00\u4E2D ".repeat(250_000), 500_000, "characters_including_spaces"));
        cases.add(htmlExcerpt("t6-amps-1mb", "&".repeat(1_000_000), 999_999));
        cases.add(htmlExcerpt("t6-text-1mb", "ab &amp; <b>c</b>\n".repeat(60_000), 700_000));
        String note = "<sup data-fn=\"f\" class=\"fn\"><a href=\"#f\" id=\"f-link\">1</a></sup>";
        cases.add(removeFootnotes("t6-starts-1mb", "<sup data-fn=\"".repeat(100_000)));
        cases.add(removeFootnotes("t6-footnotes-1mb", ("x" + note).repeat(15_000)));
        cases.add(removeFootnotes(
                "t6-near-footnotes-1mb", note.substring(0, note.length() - 1).repeat(15_000)));
        cases.add(new ParityCase(
                "t6-excerpt-chain-1mb",
                "excerpt_chain",
                "It's \"great\" :) & more -- yes\n\n".repeat(30_000),
                NODES.objectNode(),
                null));
        // Task 7: force_balance_tags on large inputs (the shapes of HostileInputTest.balanceIsLinear and
        // balanceAdversarialShapes)
        cases.add(forceBalance("fa-g161", "<b>".repeat(20_000) + "x"));
        cases.add(forceBalance("fa-b-1mb", "<b>".repeat(350_000) + "x"));
        cases.add(forceBalance("t7-lt-1mb", "<".repeat(1_000_000)));
        cases.add(forceBalance("t7-deep-div", "<div>".repeat(100_000) + "x" + "</div>".repeat(100_000)));
        cases.add(forceBalance("t7-deep-upper-div", "<DIV>".repeat(100_000) + "x" + "</div>".repeat(100_000)));
        cases.add(forceBalance("t7-deep-span", "<span>".repeat(100_000) + "x"));
        cases.add(forceBalance("t7-deep-p", "<p>".repeat(100_000) + "x"));
        cases.add(forceBalance("t7-unclosed-mixed", "<i><b>".repeat(50_000) + "x"));
        cases.add(forceBalance("t7-voids", "<br>".repeat(100_000) + "<img src=x/>".repeat(25_000)));
        cases.add(forceBalance("t7-lt-attr", "<a b=\"<i>\">".repeat(100_000)));
        cases.add(forceBalance("t7-comments", "<!-- <b> -->".repeat(100_000)));
        cases.add(forceBalance("t7-stray-closers", "</b>".repeat(300_000)));
        cases.add(forceBalance("t7-stray-deep", "<b>".repeat(50_000) + "</i>".repeat(50_000)));
        cases.add(forceBalance("t7-crossed-deep", "<b><i>".repeat(50_000) + "</b>".repeat(50_000)));
        cases.add(forceBalance("t7-malformed-closers", "</ b></></A-b><//b></b x></b/>".repeat(30_000)));
        cases.add(forceBalance("t7-long-name", "<" + "a".repeat(1_000_000)));
        cases.add(forceBalance("t7-long-attr", "<a " + "x".repeat(1_000_000)));
        cases.add(forceBalance("t7-open-attrs-50k", "<a ".repeat(50_000)));
        cases.add(forceBalance("t7-comment-escapes", "< !--".repeat(200_000) + "<    !--".repeat(100_000)));
        cases.add(forceBalance("t7-digits-1mb", "<3".repeat(500_000)));
        // 17 characters after the hyphen of a name that does not end a tag still balance (t7-custom-tail-17 in the
        // case file); with 18 WordPress's backtrack limit stops the scan there (D11)
        cases.add(forceBalance("t7-custom-tail-18", "<b>x<a-" + "b".repeat(18) + "=<i>y"));
        cases.add(forceBalance("t7-custom-tail-closer", "<b>x</a-" + "b".repeat(40) + "=<i>y<p>z"));
        return cases;
    }

    private static ParityCase forceBalance(String id, String in) {
        return new ParityCase(id, "force_balance", in, NODES.objectNode(), null);
    }

    private static ParityCase autop(String id, String in) {
        return new ParityCase(id, "autop", in, NODES.objectNode(), null);
    }

    /** A texturize case with core's shortcodes, plus {@code code} when {@code codeRegistered}. */
    private static ParityCase texturize(String id, String in, boolean codeRegistered) {
        ObjectNode args = NODES.objectNode();
        if (codeRegistered) {
            var names = NODES.arrayNode();
            Functions.CORE_SHORTCODES.forEach(names::add);
            args.set("shortcodes", names.add("code"));
        }
        return new ParityCase(id, "texturize", in, args, null);
    }

    private static ParityCase convertChars(String id, String in) {
        return new ParityCase(id, "convert_chars", in, NODES.objectNode(), null);
    }

    private static ParityCase capitalP(String id, String in, String filter) {
        ObjectNode args = NODES.objectNode();
        args.put("filter", filter);
        return new ParityCase(id, "capital_p", in, args, null);
    }

    private static ParityCase smilies(String id, String in) {
        ObjectNode args = NODES.objectNode();
        args.put("siteUrl", "https://example.com");
        args.put("useSmilies", true);
        return new ParityCase(id, "smilies", in, args, null);
    }

    private static ParityCase trimWords(String id, String in, int num, String type) {
        ObjectNode args = NODES.objectNode();
        args.put("num", num);
        args.put("type", type);
        return new ParityCase(id, "trim_words", in, args, null);
    }

    private static ParityCase htmlExcerpt(String id, String in, int count) {
        ObjectNode args = NODES.objectNode();
        args.put("count", count);
        return new ParityCase(id, "html_excerpt", in, args, null);
    }

    private static ParityCase removeFootnotes(String id, String in) {
        return new ParityCase(id, "remove_footnotes", in, NODES.objectNode(), null);
    }

    /** The id prefix of generated cases: {@code gen/<fn>-<i>}. */
    static final String GENERATED = "gen/";

    /**
     * Generated inputs (Task 8): {@code count} values of {@code inputs} for {@code fn}, seeded
     * {@code lib-wp-formatting-<fn>}, with the arguments {@code args} gives the {@code i}-th case.
     */
    private static void generate(
            List<ParityCase> cases, String fn, Gen<String> inputs, int count, IntFunction<ObjectNode> args) {
        List<String> values = GeneratedCases.sample(inputs, "lib-wp-formatting-" + fn, count);
        for (int i = 0; i < values.size(); i++) {
            cases.add(new ParityCase(GENERATED + fn + "-" + i, fn, values.get(i), args.apply(i), null));
        }
    }

    /** Generated inputs with generated arguments: input and arguments are drawn from one seeded sample. */
    private static void generate(
            List<ParityCase> cases, String fn, Gen<String> inputs, Gen<ObjectNode> args, int count) {
        Gen<ParityCase> both = random -> new ParityCase("", fn, inputs.next(random), args.next(random), null);
        List<ParityCase> values = GeneratedCases.sample(both, "lib-wp-formatting-" + fn, count);
        for (int i = 0; i < values.size(); i++) {
            ParityCase c = values.get(i);
            cases.add(new ParityCase(GENERATED + fn + "-" + i, fn, c.in(), c.args(), null));
        }
    }

    private static List<ParityCase> generatedCases() {
        List<ParityCase> cases = new ArrayList<>();
        Gen<String> html = FormattingArbitraries.html();
        generate(cases, "autop", html, 3_000, i -> {
            ObjectNode args = NODES.objectNode();
            if (i % 4 == 3) {
                args.put("br", false);
            }
            return args;
        });
        generate(cases, "texturize", html, 3_000, i -> {
            ObjectNode args = NODES.objectNode();
            if (i % 3 == 2) {
                var names = NODES.arrayNode();
                Functions.CORE_SHORTCODES.forEach(names::add);
                args.set("shortcodes", names.add("code"));
            }
            if (i % 7 == 6) {
                var tags = NODES.arrayNode();
                Texturize.DEFAULT_NO_TEXTURIZE_TAGS.forEach(tags::add);
                args.set("noTexturizeTags", tags.add("span"));
            }
            return args;
        });
        // smilies: every 5th case with use_smilies off, every 3rd with a site URL that has a path
        generate(cases, "smilies", html, 1_500, i -> {
            ObjectNode args = NODES.objectNode();
            if (i % 5 == 4) {
                args.put("useSmilies", false);
            }
            if (i % 3 == 1) {
                args.put("siteUrl", "https://example.com/blog/sub");
            }
            return args;
        });
        generate(cases, "convert_chars", html, 1_000, i -> NODES.objectNode());
        List<String> filters = List.of("the_title", "the_content", "document_title");
        generate(cases, "capital_p", html, 1_000, i -> NODES.objectNode().put("filter", filters.get(i % 3)));
        generate(cases, "title_chain", html, 500, i -> NODES.objectNode());
        generate(cases, "content_chain", html, 500, i -> NODES.objectNode());
        generate(cases, "excerpt_chain", html, 1_000, i -> NODES.objectNode());
        generate(cases, "trim_words", FormattingArbitraries.words(), FormattingArbitraries.trimArgs(), 1_500);
        generate(cases, "html_excerpt", FormattingArbitraries.words(), FormattingArbitraries.excerptArgs(), 1_000);
        generate(cases, "remove_footnotes", html, 500, i -> NODES.objectNode());
        generate(cases, "html_split", html, 1_000, i -> NODES.objectNode());
        generate(cases, "replace_in_html_tags", html, 500, i -> {
            ObjectNode pairs = NODES.objectNode();
            if (i % 2 == 0) {
                pairs.put("\n", " <!-- wpnl --> ");
            } else {
                pairs.put("x", "1").put("xy", "2").put("y", "3");
            }
            return NODES.objectNode().set("pairs", pairs);
        });
        generate(cases, "force_balance", html, 1_000, i -> NODES.objectNode());
        // balanceTags: the force flag and the use_balanceTags option cycle through all four combinations
        generate(
                cases,
                "balance_tags",
                html,
                500,
                i -> NODES.objectNode().put("force", i % 2 == 1).put("option", i % 4 >= 2));
        generate(cases, "trim_excerpt", html, FormattingArbitraries.trimExcerptArgs(), 1_000);
        return cases;
    }
}
