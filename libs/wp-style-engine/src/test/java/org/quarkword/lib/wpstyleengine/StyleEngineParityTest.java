package org.quarkword.lib.wpstyleengine;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import org.quarkword.testing.wporacle.OracleJson;
import org.quarkword.testing.wporacle.ParityRun;
import org.quarkword.testing.wporacle.ParitySkipList;
import org.quarkword.testing.wporacle.PhpOracle;
import org.quarkword.testing.wporacle.WordPressRelease;
import org.quarkword.testing.wporacle.WordPressSources;

/**
 * Compares this library with WordPress 7.1.3's style engine (testing/wp-oracle) on every case of the shared case
 * file, on large programmatic inputs and on generated inputs. A recorded output that differs from WordPress's answer
 * is reported as the aspect "recorded". Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class StyleEngineParityTest {

    static final Path DRIVER = Path.of("src/test/php/style-engine-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/compat.php",
        "wp-includes/formatting.php",
        "wp-includes/kses.php",
        "wp-includes/functions.php",
        "wp-includes/class-wp-token-map.php",
        "wp-includes/html-api/html5-named-character-references.php",
        "wp-includes/html-api/class-wp-html-decoder.php",
        "wp-includes/style-engine.php",
        "wp-includes/style-engine/class-wp-style-engine.php",
        "wp-includes/style-engine/class-wp-style-engine-css-declarations.php",
        "wp-includes/style-engine/class-wp-style-engine-css-rule.php",
        "wp-includes/style-engine/class-wp-style-engine-css-rules-store.php",
        "wp-includes/style-engine/class-wp-style-engine-processor.php"
    };
    /**
     * The green run's 16,948 comparisons, rounded down to a multiple of 100: below the count of a run that lost the
     * cases of any one generated fn (the smallest have 500).
     */
    static final int MIN_COMPARISONS = 16_900;

    static final int SHRUNK_CASES = 3;
    static final int SHRINK_ROUNDS = 12;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    /** One case for the oracle; {@code out} is the recorded output or {@code null}. */
    record ParityCase(String id, String fn, JsonNode in, ObjectNode args, JsonNode out) {}

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

        ParityRun run = new ParityRun("lib-wp-style-engine", ParitySkipList.load(SKIPS), FAILURES);
        List<String> throwsSeen = new ArrayList<>();
        Map<String, ParityCase> generated = new LinkedHashMap<>();
        for (ParityCase c : cases) {
            JsonNode wp = results.get(c.id());
            assertThat(wp).as("WordPress's answer for %s", c.id()).isNotNull();
            if (c.id().startsWith(GENERATED)) {
                run.generated(c.id(), c.in().toString());
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
                    id,
                    DeltaShrinker.shrink(
                            shrinkText(c.in()), candidates -> stillDiffer(oracle, c, candidates), SHRINK_ROUNDS));
        }
        System.out.println("lib-wp-style-engine parity: " + cases.size() + " cases, " + run.comparisons()
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

    /** The text the shrinker cuts: a text input itself, any other input as its JSON text. */
    private static String shrinkText(JsonNode in) {
        return in.isTextual() ? in.asText() : in.toString();
    }

    /** The input a shrinking candidate stands for, or {@code null} when a cut JSON text no longer parses. */
    private static JsonNode shrinkInput(ParityCase c, String candidate) {
        if (c.in().isTextual()) {
            return NODES.textNode(candidate);
        }
        try {
            return OracleJson.MAPPER.readTree(candidate);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    /** One oracle run for a shrinking round: which candidates, with the case's fn and args, still differ. */
    private static List<Boolean> stillDiffer(PhpOracle oracle, ParityCase c, List<String> candidates) {
        try {
            List<PhpOracle.Case> batch = new ArrayList<>();
            List<ParityCase> shrunk = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                JsonNode in = shrinkInput(c, candidates.get(i));
                // a cut through a surrogate pair cannot travel as JSON to PHP; such a candidate does not count
                ParityCase candidate = in == null || hasUnpairedSurrogate(candidates.get(i))
                        ? null
                        : new ParityCase(c.id(), c.fn(), in, c.args(), null);
                shrunk.add(candidate);
                if (candidate != null) {
                    batch.add(new PhpOracle.Case("shrink-" + i, payload(candidate)));
                }
            }
            Map<String, JsonNode> results = oracle.run(DRIVER, batch);
            List<Boolean> verdicts = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                JsonNode wp = results.get("shrink-" + i);
                ParityCase candidate = shrunk.get(i);
                if (wp == null || candidate == null) {
                    verdicts.add(false);
                    continue;
                }
                ParityRun probe =
                        new ParityRun("shrink", ParitySkipList.empty(), Path.of("target/wp-parity-shrink.json"));
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
        in.set("in", c.in());
        in.set("args", c.args());
        return in;
    }

    /** Inputs too large for the case file; each task adds its own. */
    private static List<ParityCase> programmaticCases() {
        List<ParityCase> cases = new ArrayList<>();
        // Task 1: the shapes of HostileInputTest.helpersAreLinear and kebabBacktrackingShapes
        cases.add(kebab("t1-kebab-1mb", "a".repeat(1_000_000)));
        cases.add(kebab("t1-kebab-words-1mb", "a ".repeat(500_000)));
        cases.add(kebab("t1-kebab-acronym-1mb", "A".repeat(1_000_000) + "1"));
        cases.add(kebab("t1-kebab-misc-upper-1mb", "\u0101".repeat(500_000) + "A".repeat(500_000) + "1"));
        cases.add(kebab("t1-kebab-ordinal-1mb", "1".repeat(1_000_000) + "st"));
        cases.add(kebab("t1-kebab-lower-digit-1mb", "a".repeat(1_000_000) + "1"));
        cases.add(kebab("t1-kebab-camel-1mb", "aB".repeat(500_000)));
        // the largest shapes WordPress still converts: one more letter or digit and PCRE's limits give "" (D1)
        cases.add(kebab("t1-kebab-acronym-8191", "A".repeat(8_191) + "1"));
        cases.add(kebab("t1-kebab-misc-upper-4095", "\u0101".repeat(4_095) + "A".repeat(4_095) + "1"));
        cases.add(kebab("t1-kebab-ordinal-999993", "1".repeat(999_993) + "st"));
        cases.add(kebab("t1-kebab-lower-digit-8191", "a".repeat(8_191) + "1"));
        cases.add(kebab("t1-kebab-upper-8191", "A".repeat(8_191)));
        cases.add(kebab("t1-kebab-lower-misc-8191", "a".repeat(8_190) + "\u0101"));
        // T1-R: PCRE gives up on a plain digit run of 499,998 digits, and keeps the words before the failing one (D1)
        cases.add(kebab("t1-kebab-digits-1mb", "1".repeat(1_000_000)));
        cases.add(kebab("t1-kebab-digits-499997", "1".repeat(499_997)));
        cases.add(kebab("t1-kebab-prefix-1mb", "fooBar " + "A".repeat(1_000_000) + "1"));
        // T1-R: Unicode 16 and 17 digits and word characters (KebabCaseLimitsTest), the same on every JDK
        cases.add(kebab("t1-kebab-u16-digit", "a\ud803\udd40b"));
        cases.add(kebab("t1-kebab-u16-digits-px", "\ud803\udd40\ud803\udd49px"));
        cases.add(kebab("t1-kebab-u16-digit-1e5f1", "x\ud839\uddf1y"));
        cases.add(kebab("t1-kebab-u16-digit-16d70", "x\ud81b\udd70Y"));
        cases.add(kebab("t1-kebab-u17-digit", "a\ud807\udde0b"));
        cases.add(kebab("t1-kebab-u16-ordinal-letter", "1st\ud803\udd50"));
        cases.add(kebab("t1-kebab-u17-ordinal-letter", "1st\ud806\udf60"));
        cases.add(kebab("t1-kebab-u17-ordinal-letter-10940", "1st\ud802\udd40"));
        cases.add(kebab("t1-kebab-u16-ordinal-mark", "4th\ud803\udd69"));
        cases.add(kebab("t1-kebab-u17-ordinal-digit", "2nd\ud807\udde0"));
        cases.add(kebab("t1-kebab-u16-ordinal-digit", "3rd\ud818\udd30"));
        // Task 2: the declarations of HostileInputTest.declarationsAndRulesAreLinear
        ObjectNode many = NODES.objectNode();
        for (int i = 0; i < 10_000; i++) {
            many.put("--p" + i, "1");
        }
        cases.add(declarations("t2-decl-10k", many));
        cases.add(declarations("t2-decl-1mb", NODES.objectNode().put("color", "a".repeat(1_000_000))));
        cases.add(declarations("t2-decl-semicolons", NODES.objectNode().put("color", "red;".repeat(250_000))));
        cases.add(declarations("t2-decl-only-semicolons", NODES.objectNode().put("color", ";".repeat(1_000_000))));
        // Task 4: the get_styles shapes of HostileInputTest.getStylesIsLinear
        String letters = "a".repeat(1_000_000);
        cases.add(getStyles("t4-text-1mb", HostileInputTest.t4Styles("color", "text", NODES.textNode(letters))));
        cases.add(getStyles(
                "t4-slug-1mb",
                HostileInputTest.t4Styles("color", "text", NODES.textNode("var:preset|color|" + letters))));
        cases.add(getStyles(
                "t4-pipes-1mb",
                HostileInputTest.t4Styles(
                        "color", "text", NODES.textNode("var:preset|color|" + "|".repeat(1_000_000) + "x"))));
        cases.add(getStyles("t4-sides-10k", HostileInputTest.t4Sides()));
        // the longest slug whose var() WordPress's safecss still keeps; one more letter and it is dropped (D5)
        cases.add(getStyles(
                "t4-slug-8167",
                HostileInputTest.t4Styles("color", "text", NODES.textNode("var:preset|color|" + "a".repeat(8_167)))));
        // Task 3: the stylesheet shapes of HostileInputTest.processorIsLinear
        ObjectNode optimize = NODES.objectNode().put("optimize", true);
        cases.add(t3Rules(
                "t3-opt-same-10k", i -> ".r" + i, i -> NODES.objectNode().put("color", "red"), optimize));
        cases.add(t3Rules(
                "t3-opt-distinct-10k", i -> ".r" + i, i -> NODES.objectNode().put("--v", "" + i), optimize));
        cases.add(t3Rules("t3-merge-10k", i -> ".a", i -> NODES.objectNode().put("--p" + i, "1"), NODES.objectNode()));
        cases.add(t3Rules(
                "t3-pretty-10k",
                i -> ".r" + i,
                i -> NODES.objectNode().put("color", "red"),
                NODES.objectNode().put("prettify", true)));
        // Task 5: the second shape of HostileInputTest.contextsAreLinear
        ObjectNode context = NODES.objectNode().put("context", "block-supports");
        ArrayNode calls = context.putArray("calls");
        for (int i = 0; i < 10_000; i++) {
            calls.addArray()
                    .add(HostileInputTest.t5FontSize(i))
                    .add(NODES.objectNode().put("selector", ".r" + i));
        }
        cases.add(new ParityCase("t5-context-10k", "styles_then_context", context, NODES.objectNode(), null));
        return cases;
    }

    /** A stylesheet_from_rules case of 10,000 entries. */
    private static ParityCase t3Rules(
            String id, IntFunction<String> selector, IntFunction<ObjectNode> declarations, ObjectNode args) {
        ArrayNode in = NODES.arrayNode();
        for (int i = 0; i < 10_000; i++) {
            in.addObject().put("selector", selector.apply(i)).set("declarations", declarations.apply(i));
        }
        return new ParityCase(id, "stylesheet_from_rules", in, args, null);
    }

    private static ParityCase getStyles(String id, JsonNode in) {
        return new ParityCase(id, "get_styles", in, NODES.objectNode(), null);
    }

    private static ParityCase declarations(String id, ObjectNode init) {
        ObjectNode in = NODES.objectNode();
        in.set("init", init);
        return new ParityCase(id, "declarations", in, NODES.objectNode(), null);
    }

    private static ParityCase kebab(String id, String in) {
        return new ParityCase(id, "kebab", NODES.textNode(in), NODES.objectNode(), null);
    }

    /** The id prefix of generated cases: {@code gen/<fn>-<i>}. */
    static final String GENERATED = "gen/";

    /**
     * Generated inputs: {@code count} values of {@code inputs} for {@code fn}, seeded {@code lib-wp-style-engine-<fn>}.
     */
    private static void generate(List<ParityCase> cases, String fn, Gen<JsonNode> inputs, int count) {
        List<JsonNode> values = GeneratedCases.sample(inputs, "lib-wp-style-engine-" + fn, count);
        for (int i = 0; i < values.size(); i++) {
            cases.add(new ParityCase(GENERATED + fn + "-" + i, fn, values.get(i), NODES.objectNode(), null));
        }
    }

    /**
     * Generated inputs with generated arguments: {@code pairs} gives {"in", "args"}, drawn from one seeded sample, so an
     * input can depend on its arguments (StyleEngineArbitraries keeps the D7 shape out under a strict optimize).
     */
    private static void generatePairs(List<ParityCase> cases, String fn, Gen<ObjectNode> pairs, int count) {
        List<ObjectNode> values = GeneratedCases.sample(pairs, "lib-wp-style-engine-" + fn, count);
        for (int i = 0; i < values.size(); i++) {
            ObjectNode c = values.get(i);
            cases.add(new ParityCase(GENERATED + fn + "-" + i, fn, c.get("in"), (ObjectNode) c.get("args"), null));
        }
    }

    /** Task 6: the permanent generators (StyleEngineArbitraries); the counts are the plan's table. */
    private static List<ParityCase> generatedCases() {
        List<ParityCase> cases = new ArrayList<>();
        Gen<JsonNode> styles = StyleEngineArbitraries.blockStyles();
        Gen<ObjectNode> options = StyleEngineArbitraries.styleOptions();
        generatePairs(
                cases,
                "get_styles",
                r -> {
                    ObjectNode c = NODES.objectNode();
                    c.set("in", styles.next(r));
                    ObjectNode o = options.next(r);
                    c.set(
                            "args",
                            o.isEmpty()
                                    ? NODES.objectNode()
                                    : NODES.objectNode().set("options", o));
                    return c;
                },
                4_000);
        Gen<ObjectNode> optimizeOnly = StyleEngineArbitraries.rulesArgs().map(a -> {
            a.remove("prettify");
            return a;
        });
        generatePairs(
                cases,
                "styles_then_context",
                r -> {
                    ObjectNode c = NODES.objectNode();
                    ObjectNode a = optimizeOnly.next(r);
                    c.set(
                            "in",
                            StyleEngineArbitraries.contextCalls(StyleEngineArbitraries.strictOptimize(a))
                                    .next(r));
                    c.set("args", a);
                    return c;
                },
                500);
        generatePairs(cases, "stylesheet_from_rules", StyleEngineArbitraries.stylesheetFromRulesCases(), 3_000);
        generate(cases, "processor", StyleEngineArbitraries.processorInputs(), 1_000);
        generatePairs(cases, "stylesheet_from_context", StyleEngineArbitraries.stylesheetFromContextCases(), 500);
        generate(cases, "declarations", StyleEngineArbitraries.declarationOps(), 1_500);
        generate(cases, "rule", StyleEngineArbitraries.ruleSpecs(), 1_000);
        generate(cases, "store", StyleEngineArbitraries.storeOps(), 500);
        generate(cases, "kebab", StyleEngineArbitraries.kebabInputs().map(NODES::textNode), 2_000);
        generate(
                cases,
                "sanitize_key",
                StyleEngineArbitraries.sanitizeKeyInputs().map(NODES::textNode),
                500);
        // the R2 audit: lib-wp-kses's safecss on the declarations the style engine produces
        generate(cases, "safecss", StyleEngineArbitraries.safecssInputs().map(NODES::textNode), 1_500);
        return cases;
    }
}
