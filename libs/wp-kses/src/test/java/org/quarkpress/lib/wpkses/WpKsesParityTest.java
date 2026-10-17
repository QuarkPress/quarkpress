package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.wporacle.DeltaShrinker;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares lib-wp-kses with WordPress 7.1.3 (testing/wp-oracle, -Pwp-parity): every reference example (and that
 * WordPress still gives its recorded value), the ruling and edge cases, the XSS corpus in three settings, and
 * fixed-seed generated inputs for every function, in bare and WordPress mode.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class WpKsesParityTest {

    static final Path RULINGS = Path.of("src/test/resources/wp-parity-cases.json");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final int MIN_COMPARISONS = 15_000;
    static final int SHRUNK_CASES = 3;
    static final int SHRINK_ROUNDS = 12;

    /**
     * Minimum comparisons (skip-listed ones included) per input family, so that a generator or corpus that
     * shrinks cannot hide behind the total.
     */
    static final Map<String, Integer> MIN_PER_FAMILY = Map.of(
            "example", 500,
            "xss", 300,
            "gen:kses", 9_000,
            "gen:safecss", 1_500,
            "gen:bad_protocol", 1_500,
            "gen:strip_all_tags", 800,
            "gen:normalize_entities", 800,
            "gen:one_attr", 800);

    private record ParityCase(String id, ObjectNode input, JsonNode recorded, boolean generated) {}

    @Test
    void matchesWordPress() throws Exception {
        Path wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), KsesOracle.WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(wordpress);

        List<ParityCase> cases = new ArrayList<>();
        cases.addAll(exampleCases());
        cases.addAll(rulingCases());
        cases.addAll(corpusCases());
        cases.addAll(generatedCases());
        Map<String, JsonNode> results = oracle.run(KsesOracle.DRIVER, batch(cases));

        ParityRun run = new ParityRun("lib-wp-kses", ParitySkipList.load(SKIPS), FAILURES);
        Map<String, ParityCase> generated = new LinkedHashMap<>();
        Map<String, Integer> perFamily = new TreeMap<>();
        for (ParityCase c : cases) {
            JsonNode wordpressResult = results.get(c.id());
            perFamily.merge(family(c), 1, Integer::sum);
            if (c.generated()) {
                run.generated(c.id(), c.input().get("text").asText());
                generated.put(c.id(), c);
            }
            compare(run, c, wordpressResult);
            if (c.recorded() != null) {
                run.compare(c.id(), "recorded", c.recorded(), wordpressValue(wordpressResult));
            }
        }
        for (String id : run.failedGeneratedCases().stream().limit(SHRUNK_CASES).toList()) {
            ParityCase c = generated.get(id);
            run.shrunk(
                    id,
                    DeltaShrinker.shrink(
                            c.input().get("text").asText(), texts -> stillDiffer(oracle, c, texts), SHRINK_ROUNDS));
        }
        System.out.println("lib-wp-kses parity: " + cases.size() + " cases, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        System.out.println("lib-wp-kses parity families: " + perFamily);
        MIN_PER_FAMILY.forEach((family, minimum) -> assertThat(perFamily.getOrDefault(family, 0))
                .as("comparisons of " + family)
                .isGreaterThanOrEqualTo(minimum));
        run.assertParity(MIN_COMPARISONS);
    }

    /** "example", "xss", "ruling"/"edge", or "gen:" plus the function for generated cases. */
    private static String family(ParityCase c) {
        if (c.generated()) {
            return "gen:" + c.input().get("fn").asText();
        }
        String id = c.id();
        int end = id.indexOf('/') >= 0 ? id.indexOf('/') : id.indexOf('-');
        return end < 0 ? id : id.substring(0, end);
    }

    /** WordPress's value; a PHP failure becomes "fatal: <class>" ("fatal: timeout" when it never finished). */
    static JsonNode wordpressValue(JsonNode result) {
        return result.has("fatal")
                ? TextNode.valueOf("fatal: " + result.get("fatal").asText())
                : result.get("out");
    }

    static void compare(ParityRun run, ParityCase c, JsonNode result) {
        String fn = c.input().get("fn").asText();
        boolean timeout = result.has("fatal") && result.get("fatal").asText().equals("timeout");
        JsonNode quarkpress;
        try {
            quarkpress = Examples.run(c.input());
        } catch (RuntimeException e) {
            quarkpress = TextNode.valueOf("exception: " + e);
        }
        JsonNode wordpress = wordpressValue(result);
        String aspect = fn;
        if (timeout) {
            aspect = fn + ":timeout";
        } else if (c.generated()
                && wordpress.isTextual()
                && quarkpress.isTextual()
                && onlyDelimitersNeutralized(wordpress.asText(), quarkpress.asText())) {
            aspect = fn + ":delimiters-neutralized";
        }
        run.compare(c.id(), aspect, wordpress, quarkpress);
    }

    /**
     * True when {@code quarkpress} differs from {@code wordpress} only in block delimiters that WordPress leaves
     * live and QuarkPress neutralizes (README difference 11): a {@code <!--} followed by optional whitespace and
     * {@code wp:} or {@code /wp:} written as {@code &lt;!--}, and, only inside such a neutralized comment, its end
     * {@code -->} written as {@code --&gt;}. QuarkPress never neutralizes the end of a comment whose opener it kept,
     * so a lone {@code --&gt;} counts as another change. Any other change makes it false, so the skip entry for
     * this aspect cannot hide one.
     */
    static boolean onlyDelimitersNeutralized(String wordpress, String quarkpress) {
        int w = 0;
        int q = 0;
        boolean neutralized = false;
        boolean insideNeutralizedComment = false;
        while (w < wordpress.length() && q < quarkpress.length()) {
            if (insideNeutralizedComment && wordpress.startsWith("-->", w)) {
                if (quarkpress.startsWith("-->", q)) {
                    q += 3;
                } else if (quarkpress.startsWith("--&gt;", q)) {
                    q += 6;
                } else {
                    return false;
                }
                w += 3;
                insideNeutralizedComment = false;
            } else if (wordpress.charAt(w) == quarkpress.charAt(q)) {
                w++;
                q++;
            } else if (wordpress.startsWith("<!--", w)
                    && quarkpress.startsWith("&lt;!--", q)
                    && startsDelimiterName(wordpress, w + 4)) {
                w += 4;
                q += 7;
                neutralized = true;
                insideNeutralizedComment = true;
            } else {
                return false;
            }
        }
        return neutralized && w == wordpress.length() && q == quarkpress.length();
    }

    private static boolean startsDelimiterName(String s, int at) {
        int i = at;
        while (i < s.length() && " \t\n\r\f".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        return s.startsWith("wp:", i) || s.startsWith("/wp:", i);
    }

    private static List<Boolean> stillDiffer(PhpOracle oracle, ParityCase template, List<String> texts) {
        try {
            List<ParityCase> probes = new ArrayList<>();
            for (int i = 0; i < texts.size(); i++) {
                ObjectNode input = template.input().deepCopy();
                input.put("text", texts.get(i));
                probes.add(new ParityCase("shrink-" + i, input, null, false));
            }
            Map<String, JsonNode> results = oracle.run(KsesOracle.DRIVER, batch(probes));
            List<Boolean> verdicts = new ArrayList<>();
            for (ParityCase probe : probes) {
                ParityRun check =
                        new ParityRun("shrink", ParitySkipList.empty(), Path.of("target/wp-parity-shrink.json"));
                compare(check, probe, results.get(probe.id()));
                verdicts.add(!check.mismatches().isEmpty());
            }
            return verdicts;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static List<PhpOracle.Case> batch(List<ParityCase> cases) {
        List<PhpOracle.Case> batch = new ArrayList<>(cases.size());
        for (ParityCase c : cases) {
            batch.add(new PhpOracle.Case(c.id(), c.input()));
        }
        return batch;
    }

    // ---- inputs ----

    /** Every reference example; WordPress must also still return the value recorded in the file. */
    private static List<ParityCase> exampleCases() {
        List<ParityCase> cases = new ArrayList<>();
        for (String file : Examples.FILES) {
            String prefix = "example/" + file.substring(0, file.length() - ".json".length()) + "/";
            for (JsonNode example : Examples.load(file)) {
                ObjectNode input = ((ObjectNode) example).deepCopy();
                JsonNode recorded = input.remove("expected");
                input.remove("id");
                cases.add(new ParityCase(prefix + example.get("id").asText(), input, recorded, false));
            }
        }
        return cases;
    }

    /**
     * The README's deliberate differences, each pinned by a case (wp-parity-cases.json plus the large ones built
     * here), and edge cases of the attribute decoder and of NUL handling.
     */
    private static List<ParityCase> rulingCases() throws IOException {
        List<ParityCase> cases = new ArrayList<>();
        for (JsonNode c : OracleJson.MAPPER.readTree(RULINGS.toFile())) {
            ObjectNode input = ((ObjectNode) c).deepCopy();
            input.remove("id");
            cases.add(new ParityCase(c.get("id").asText(), input, null, false));
        }
        cases.add(css(
                "ruling-3-css-deep-nesting", "width: calc(" + "(".repeat(20_000) + "1px" + ")".repeat(20_000) + ")"));
        cases.add(css("ruling-3-css-long-calc", "color:red; width: calc(" + "a".repeat(2_000_000) + ")"));
        StringBuilder urls = new StringBuilder("background-image:");
        StringBuilder gradients = new StringBuilder("background:");
        for (int i = 0; i <= SafeCss.EXACT_REMOVAL_LIMIT + 4; i++) {
            urls.append(" url(a").append(i).append(".png)");
            gradients.append(" linear-gradient(a").append(i).append(')');
        }
        cases.add(css("ruling-12-css-many-urls", urls + " uurl(a0.png)rl(a0.png)"));
        cases.add(css("ruling-12-css-many-gradients", gradients + " llinear-gradient(a0)inear-gradient(a0)"));
        int depth = 1025;
        cases.add(kses(
                "ruling-10-nesting-beyond-parser-limit",
                "wp",
                "post",
                "<!-- wp:group -->".repeat(depth) + "x" + "<!-- /wp:group -->".repeat(depth),
                false));
        return cases;
    }

    private static ParityCase css(String id, String text) {
        ObjectNode input =
                OracleJson.MAPPER.createObjectNode().put("fn", "safecss").put("text", text);
        return new ParityCase(id, input, null, false);
    }

    private static List<ParityCase> corpusCases() throws IOException {
        List<ParityCase> cases = new ArrayList<>();
        List<String> payloads = XssCorpusTest.corpus();
        for (int i = 0; i < payloads.size(); i++) {
            cases.add(kses("xss/" + i + "/bare-post", "bare", "post", payloads.get(i), false));
            cases.add(kses("xss/" + i + "/wp-post", "wp", "post", payloads.get(i), false));
            cases.add(kses("xss/" + i + "/wp-data", "wp", "data", payloads.get(i), false));
        }
        return cases;
    }

    private static ParityCase kses(String id, String mode, String context, String text, boolean generated) {
        ObjectNode input = OracleJson.MAPPER
                .createObjectNode()
                .put("fn", "kses")
                .put("text", text)
                .put("mode", mode)
                .put("context", context);
        return new ParityCase(id, input, null, generated);
    }

    private static List<ParityCase> generatedCases() {
        List<ParityCase> cases = new ArrayList<>();
        List<String> soup = GeneratedCases.sample(KsesArbitraries.soup(), "kses-soup-20261017", 2_500);
        for (int i = 0; i < soup.size(); i++) {
            cases.add(kses("gen/soup-" + i + "/bare", "bare", "post", soup.get(i), true));
            cases.add(kses("gen/soup-" + i + "/wp", "wp", "post", soup.get(i), true));
        }
        List<String> tags = GeneratedCases.sample(KsesArbitraries.tags(), "kses-tags-20261017", 2_000);
        for (int i = 0; i < tags.size(); i++) {
            cases.add(kses("gen/tags-" + i + "/post", "wp", "post", tags.get(i), true));
            cases.add(kses("gen/tags-" + i + "/data", "wp", "data", tags.get(i), true));
        }
        cases.addAll(simple("gen/css-", "safecss", KsesArbitraries.css(), "kses-css-20261017", 1_500));
        cases.addAll(simple("gen/url-", "bad_protocol", KsesArbitraries.urls(), "kses-url-20261017", 1_500));
        cases.addAll(simple("gen/strip-", "strip_all_tags", KsesArbitraries.soup(), "kses-strip-20261017", 800));
        cases.addAll(simple("gen/entities-", "normalize_entities", KsesArbitraries.soup(), "kses-ne-20261017", 800));
        List<String> attributes = GeneratedCases.sample(KsesArbitraries.tags(), "kses-one-20261017", 800);
        for (int i = 0; i < attributes.size(); i++) {
            String tag = attributes.get(i);
            int space = tag.indexOf(' ');
            String token = space < 0 ? "title=\"x\"" : tag.substring(space + 1, Math.max(space + 1, tag.length() - 2));
            ObjectNode input = OracleJson.MAPPER
                    .createObjectNode()
                    .put("fn", "one_attr")
                    .put("text", token)
                    .put("element", tag.substring(1, space < 0 ? tag.indexOf('>') : space));
            cases.add(new ParityCase("gen/one-" + i, input, null, true));
        }
        return cases;
    }

    private static List<ParityCase> simple(String prefix, String fn, Gen<String> gen, String seed, int count) {
        List<ParityCase> cases = new ArrayList<>();
        List<String> texts = GeneratedCases.sample(gen, seed, count);
        for (int i = 0; i < texts.size(); i++) {
            ObjectNode input =
                    OracleJson.MAPPER.createObjectNode().put("fn", fn).put("text", texts.get(i));
            if (fn.equals("strip_all_tags")) {
                input.put("remove_breaks", i % 2 == 0);
            }
            if (fn.equals("normalize_entities")) {
                input.put("xml", i % 3 == 0);
            }
            cases.add(new ParityCase(prefix + i, input, null, true));
        }
        return cases;
    }
}
