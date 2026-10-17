package org.quarkpress.lib.wpshortcode;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Entry;
import org.quarkpress.lib.wpshortcode.ShortcodeAttributes.Key;
import org.quarkpress.testing.wporacle.DeltaShrinker;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares this library with WordPress 7.1.3's do_shortcode, strip_shortcodes, has_shortcode,
 * get_shortcode_tags_in_content, shortcode_unautop, shortcode_parse_atts, shortcode_atts and the "post" kses
 * allowlist (testing/wp-oracle). Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class ShortcodeParityTest {

    static final Path DRIVER = Path.of("src/test/php/shortcode-driver.php");
    static final Path CASES = Path.of("src/test/resources/wp-parity-cases.json");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/compat.php",
        "wp-includes/formatting.php",
        "wp-includes/kses.php",
        "wp-includes/shortcodes.php",
        "wp-includes/class-wp-token-map.php",
        "wp-includes/html-api/html5-named-character-references.php",
        "wp-includes/html-api/class-wp-html-decoder.php"
    };
    static final List<String> ALL_ASPECTS =
            List.of("apply", "apply_ignore_html", "strip", "tags", "has", "unautop", "atts", "defaults");
    static final int MIN_COMPARISONS = 60_000;
    static final int GENERATED_CONTENT = 2_500;
    static final int GENERATED_ATTRIBUTES = 1_000;
    static final int GENERATED_UNAUTOP = 3_000;
    static final int SHRUNK_CASES = 3;
    static final int SHRINK_ROUNDS = 12;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    record ParityCase(
            String id, String content, String profile, List<String> hooks, List<String> aspects, boolean generated) {}

    private final Map<String, Shortcodes> processors = new HashMap<>();

    @Test
    void matchesWordPress() throws Exception {
        assertThat(Profiles.registry("main").names()).isEqualTo(ShortcodeArbitraries.MAIN_TAGS);
        Path wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(wordpress);

        List<ParityCase> cases = new ArrayList<>();
        cases.addAll(handWrittenCases());
        cases.addAll(programmaticCases());
        cases.addAll(generatedCases());

        List<PhpOracle.Case> batch = new ArrayList<>();
        batch.add(new PhpOracle.Case("allowlist", NODES.objectNode().put("allowlist", true)));
        for (ParityCase c : cases) {
            batch.add(new PhpOracle.Case(c.id(), payload(c, c.content())));
        }
        Map<String, JsonNode> results = oracle.run(DRIVER, batch);

        ParityRun run = new ParityRun("lib-wp-shortcode", ParitySkipList.load(SKIPS), FAILURES);
        compareAllowlist(run, results.get("allowlist"));
        Map<String, ParityCase> generated = new LinkedHashMap<>();
        for (ParityCase c : cases) {
            if (c.generated()) {
                run.generated(c.id(), c.content());
                generated.put(c.id(), c);
            }
            compare(run, c.id(), c, c.content(), results.get(c.id()));
        }
        for (String id : run.failedGeneratedCases().stream().limit(SHRUNK_CASES).toList()) {
            ParityCase c = generated.get(id);
            run.shrunk(
                    id,
                    DeltaShrinker.shrink(c.content(), candidates -> stillDiffer(oracle, c, candidates), SHRINK_ROUNDS));
        }
        System.out.println("lib-wp-shortcode parity: " + cases.size() + " cases, " + run.comparisons()
                + " comparisons, " + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
    }

    private void compare(ParityRun run, String id, ParityCase c, String content, JsonNode wp) {
        Shortcodes shortcodes = processors.computeIfAbsent(
                c.profile() + "|" + c.hooks(),
                k -> Shortcodes.builder(Profiles.registry(c.profile()))
                        .hooks(Profiles.hooks(c.hooks()))
                        .build());
        for (String aspect : c.aspects()) {
            JsonNode value = wp.get(aspect);
            switch (aspect) {
                case "apply" -> run.compare(id, aspect, value.asText(), shortcodes.apply(content));
                case "apply_ignore_html" -> run.compare(id, aspect, value.asText(), shortcodes.apply(content, true));
                case "strip" -> run.compare(id, aspect, value.asText(), shortcodes.strip(content));
                case "tags" -> run.compare(id, aspect, texts(value), shortcodes.tagsIn(content));
                case "has" -> {
                    List<String> tags = Profiles.registry(c.profile()).names();
                    run.compare(id, "has-tags", fieldNames(value), tags);
                    for (String tag : tags) {
                        run.compare(id, "has:" + tag, value.path(tag).asBoolean(false), shortcodes.has(content, tag));
                    }
                }
                case "unautop" -> run.compare(id, aspect, value.asText(), shortcodes.unautop(content));
                case "atts" -> run.compare(id, aspect, triples(value), triples(ShortcodeAttributes.parse(content)));
                case "defaults" -> run.compare(id, aspect, texts(value), defaults(content, c.hooks()));
                default -> throw new IllegalArgumentException("unknown aspect " + aspect);
            }
        }
    }

    private static void compareAllowlist(ParityRun run, JsonNode wp) {
        SortedMap<String, List<String>> ours = PostAllowlist.describe();
        run.compare("allowlist", "elements", fieldNames(wp), new ArrayList<>(ours.keySet()));
        for (Map.Entry<String, List<String>> e : ours.entrySet()) {
            run.compare("allowlist", "element:" + e.getKey(), texts(wp.path(e.getKey())), e.getValue());
        }
    }

    /** shortcode_atts with the driver's defaults and tag "gal", as "key=value" lines in result order. */
    private static List<String> defaults(String content, List<String> hooks) {
        ShortcodeHooks h = Profiles.hooks(hooks);
        Map<String, String> merged =
                ShortcodeAttributes.parse(content).bind(h::attributeDefaults).withDefaults(Profiles.PAIRS, "gal");
        List<String> lines = new ArrayList<>();
        merged.forEach((key, value) -> lines.add(key + "=" + value));
        return lines;
    }

    private static List<List<String>> triples(ShortcodeAttributes attributes) {
        List<List<String>> out = new ArrayList<>();
        for (Entry e : attributes.entries()) {
            out.add(
                    e.key() instanceof Key.Index index
                            ? List.of("i", Long.toString(index.index()), e.value())
                            : List.of("s", ((Key.Name) e.key()).name(), e.value()));
        }
        return out;
    }

    private static List<List<String>> triples(JsonNode wp) {
        List<List<String>> out = new ArrayList<>();
        for (JsonNode triple : wp) {
            out.add(List.of(
                    triple.get(0).asText(),
                    triple.get(1).asText(),
                    triple.get(2).asText()));
        }
        return out;
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        for (JsonNode item : array) {
            out.add(item.asText());
        }
        return out;
    }

    private static List<String> fieldNames(JsonNode node) {
        List<String> out = new ArrayList<>();
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            out.add(names.next());
        }
        return out;
    }

    private static ObjectNode payload(ParityCase c, String content) {
        ObjectNode in = NODES.objectNode();
        in.put("content", content);
        in.put("profile", c.profile());
        ArrayNode hooks = in.putArray("hooks");
        c.hooks().forEach(hooks::add);
        ArrayNode aspects = in.putArray("aspects");
        c.aspects().forEach(aspects::add);
        return in;
    }

    /** One oracle run for a shrinking round: which candidates still differ from WordPress. */
    private List<Boolean> stillDiffer(PhpOracle oracle, ParityCase c, List<String> candidates) {
        try {
            List<PhpOracle.Case> batch = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                // a cut through a surrogate pair cannot travel as JSON to PHP; such a candidate does not count
                if (!Chars.hasUnpairedSurrogate(candidates.get(i))) {
                    batch.add(new PhpOracle.Case("shrink-" + i, payload(c, candidates.get(i))));
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
                compare(probe, "shrink-" + i, c, candidates.get(i), wp);
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

    private static List<ParityCase> handWrittenCases() throws IOException {
        List<ParityCase> cases = new ArrayList<>();
        for (JsonNode c : OracleJson.MAPPER.readTree(CASES.toFile())) {
            cases.add(new ParityCase(
                    c.get("id").asText(),
                    c.get("content").asText(),
                    c.path("profile").asText("main"),
                    c.has("hooks") ? texts(c.get("hooks")) : List.of(),
                    c.has("aspects") ? texts(c.get("aspects")) : ALL_ASPECTS,
                    false));
        }
        return cases;
    }

    /** Inputs too large for the case file. */
    private static List<ParityCase> programmaticCases() {
        List<String> noAttributes = List.of("apply", "apply_ignore_html", "strip", "tags", "has", "unautop");
        List<String> render = List.of("apply", "strip", "tags");
        return List.of(
                new ParityCase(
                        "r15-jit-limit", "[inner " + "/a".repeat(600_000), "main", List.of(), noAttributes, false),
                new ParityCase("r15-open-brackets", "[inner]" + "[a".repeat(600_000), "main", List.of(), render, false),
                new ParityCase(
                        "long-attributes", "[foo " + "a=1 ".repeat(50_000) + "]", "main", List.of(), render, false),
                new ParityCase(
                        "many-closers", "[foo]" + "[/foo".repeat(50_000) + "]", "main", List.of(), render, false));
    }

    private static List<ParityCase> generatedCases() {
        List<ParityCase> cases = new ArrayList<>();
        List<String> contents =
                GeneratedCases.sample(ShortcodeArbitraries.content(), "lib-wp-shortcode-content", GENERATED_CONTENT);
        for (int i = 0; i < contents.size(); i++) {
            List<String> hooks = new ArrayList<>();
            if (i % 5 == 1) {
                hooks.add("pre-inner");
            }
            if (i % 7 == 2) {
                hooks.add("post-wrap");
            }
            if (i % 11 == 3) {
                hooks.add("strip-foo");
            }
            cases.add(
                    new ParityCase("gen/content-" + i, contents.get(i), "main", List.copyOf(hooks), ALL_ASPECTS, true));
        }
        List<String> attributes = GeneratedCases.sample(
                ShortcodeArbitraries.attributeText(), "lib-wp-shortcode-attributes", GENERATED_ATTRIBUTES);
        for (int i = 0; i < attributes.size(); i++) {
            cases.add(new ParityCase(
                    "gen/atts-" + i, attributes.get(i), "main", List.of(), List.of("atts", "defaults"), true));
            cases.add(new ParityCase(
                    "gen/atts-tag-" + i, "[foo" + attributes.get(i) + "]", "main", List.of(), List.of("apply"), true));
        }
        List<String> unautop = GeneratedCases.sample(
                ShortcodeArbitraries.unautopContent(), "lib-wp-shortcode-unautop", GENERATED_UNAUTOP);
        for (int i = 0; i < unautop.size(); i++) {
            cases.add(new ParityCase("gen/unautop-" + i, unautop.get(i), "main", List.of(), List.of("unautop"), true));
        }
        return cases;
    }
}
