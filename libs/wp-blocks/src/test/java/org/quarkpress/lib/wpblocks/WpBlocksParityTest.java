package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.DeltaShrinker;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares this library with WordPress 7.1.3's parse_blocks, serialize_blocks, has_blocks and has_block
 * (testing/wp-oracle). Active only with {@code -Pwp-parity}; with {@code -Pconformance} as well, the Gutenberg
 * fixtures are compared too.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class WpBlocksParityTest {

    static final Path DRIVER = Path.of("src/test/php/blocks-driver.php");
    static final Path CASES = Path.of("src/test/resources/wp-parity-cases.json");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path CORPUS = Path.of("src/test/resources/wp");
    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/class-wp-block-parser.php",
        "wp-includes/class-wp-block-parser-block.php",
        "wp-includes/class-wp-block-parser-frame.php",
        "wp-includes/blocks.php"
    };
    static final List<String> FIXED_NAMES = List.of("core/paragraph", "paragraph", "core/a", "a", "acme/hero", "quote");
    static final int MIN_COMPARISONS = 20_000;
    static final int SOUP = 2_000;
    static final int VARIANTS = 2_000;
    static final int SHRUNK_CASES = 3;
    static final int SHRINK_ROUNDS = 12;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private record DocCase(String id, String doc, boolean generated) {}

    @Test
    void matchesWordPress() throws Exception {
        Path wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(wordpress);

        List<DocCase> cases = new ArrayList<>();
        cases.addAll(handWrittenCases());
        cases.addAll(programmaticCases());
        cases.addAll(corpusCases());
        cases.addAll(gutenbergCases());
        cases.addAll(generatedCases());

        List<PhpOracle.Case> batch = new ArrayList<>();
        for (DocCase c : cases) {
            batch.add(new PhpOracle.Case(c.id(), payload(c.doc())));
        }
        Map<String, JsonNode> results = oracle.run(DRIVER, batch);

        ParityRun run = new ParityRun("lib-wp-blocks", ParitySkipList.load(SKIPS), FAILURES);
        Map<String, String> generatedDocs = new LinkedHashMap<>();
        for (DocCase c : cases) {
            if (c.generated()) {
                run.generated(c.id(), c.doc());
                generatedDocs.put(c.id(), c.doc());
            }
            compare(run, c.id(), c.doc(), results.get(c.id()));
        }
        for (String id : run.failedGeneratedCases().stream().limit(SHRUNK_CASES).toList()) {
            run.shrunk(
                    id,
                    DeltaShrinker.shrink(
                            generatedDocs.get(id), candidates -> stillDiffer(oracle, candidates), SHRINK_ROUNDS));
        }
        long gutenberg =
                cases.stream().filter(c -> c.id().startsWith("gutenberg/")).count();
        System.out.println("lib-wp-blocks parity: " + cases.size() + " documents (" + gutenberg
                + " Gutenberg fixtures), " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
    }

    static void compare(ParityRun run, String id, String doc, JsonNode wp) {
        List<Block> blocks = WpBlocks.parse(doc);
        run.compare(id, "tree", normalise(wp.get("parsed")), normalise(tree(blocks)));
        run.compare(id, "serialize", wp.get("serialized").asText(), WpBlocks.serializeCanonical(blocks));
        run.compare(id, "has_blocks", wp.get("has_blocks").booleanValue(), WpBlocks.hasBlocks(doc));
        for (String name : names(doc)) {
            run.compare(
                    id,
                    "has_block:" + name,
                    wp.get("has_block").get(name).booleanValue(),
                    WpBlocks.hasBlock(doc, name));
        }
    }

    /** One oracle run for a shrinking round: which candidates still differ from WordPress. */
    private static List<Boolean> stillDiffer(PhpOracle oracle, List<String> candidates) {
        try {
            List<PhpOracle.Case> batch = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                batch.add(new PhpOracle.Case("shrink-" + i, payload(candidates.get(i))));
            }
            Map<String, JsonNode> results = oracle.run(DRIVER, batch);
            List<Boolean> verdicts = new ArrayList<>();
            for (int i = 0; i < candidates.size(); i++) {
                ParityRun probe =
                        new ParityRun("shrink", ParitySkipList.empty(), Path.of("target/wp-parity-shrink.json"));
                compare(probe, "shrink-" + i, candidates.get(i), results.get("shrink-" + i));
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

    // ---- trees ----

    static ArrayNode tree(List<Block> blocks) {
        ArrayNode array = NODES.arrayNode();
        for (Block b : blocks) {
            array.add(tree(b));
        }
        return array;
    }

    /** The shape of WordPress's parsed block array. */
    static ObjectNode tree(Block b) {
        ObjectNode o = NODES.objectNode();
        if (b.name() == null) {
            o.putNull("blockName");
        } else {
            o.put("blockName", b.name());
        }
        if (b.attributesValid()) {
            o.set("attrs", markNonFinite(b.attributes()));
        } else {
            o.putNull("attrs");
        }
        o.set("innerBlocks", tree(b.innerBlocks()));
        o.put("innerHTML", b.innerHtml());
        ArrayNode content = o.putArray("innerContent");
        for (String chunk : b.innerContent()) {
            if (chunk == null) {
                content.addNull();
            } else {
                content.add(chunk);
            }
        }
        return o;
    }

    /** JSON cannot carry INF or NAN; the driver sends them as {"$nonfinite": ...}, and so does this side. */
    static JsonNode markNonFinite(JsonNode node) {
        if (node.isFloatingPointNumber() && !Double.isFinite(node.doubleValue())) {
            double d = node.doubleValue();
            return NODES.objectNode().put("$nonfinite", Double.isNaN(d) ? "NAN" : d > 0 ? "INF" : "-INF");
        }
        if (node.isObject()) {
            ObjectNode copy = NODES.objectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                copy.set(e.getKey(), markNonFinite(e.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = NODES.arrayNode();
            node.forEach(element -> copy.add(markNonFinite(element)));
            return copy;
        }
        return node;
    }

    /** WordPress decodes {} and [] alike (D13), so every empty container becomes {} on both sides. */
    static JsonNode normalise(JsonNode node) {
        if (node.isContainerNode() && node.isEmpty()) {
            return NODES.objectNode();
        }
        if (node.isObject()) {
            ObjectNode copy = NODES.objectNode();
            for (Map.Entry<String, JsonNode> e : node.properties()) {
                copy.set(e.getKey(), normalise(e.getValue()));
            }
            return copy;
        }
        if (node.isArray()) {
            ArrayNode copy = NODES.arrayNode();
            node.forEach(element -> copy.add(normalise(element)));
            return copy;
        }
        return node;
    }

    // ---- inputs ----

    private static ObjectNode payload(String doc) {
        ObjectNode in = NODES.objectNode();
        in.put("doc", doc);
        ArrayNode names = in.putArray("names");
        names(doc).forEach(names::add);
        return in;
    }

    /** The fixed names plus every block name in the document, qualified and short. */
    static List<String> names(String doc) {
        Set<String> names = new LinkedHashSet<>(FIXED_NAMES);
        collectNames(WpBlocks.parse(doc), names);
        return List.copyOf(names);
    }

    private static void collectNames(List<Block> blocks, Set<String> names) {
        for (Block b : blocks) {
            if (b.name() != null) {
                names.add(b.name());
                names.add(WpBlocks.stripCoreNamespace(b.name()));
            }
            collectNames(b.innerBlocks(), names);
        }
    }

    private static List<DocCase> handWrittenCases() throws IOException {
        List<DocCase> cases = new ArrayList<>();
        for (JsonNode c : OracleJson.MAPPER.readTree(CASES.toFile())) {
            cases.add(new DocCase(c.get("id").asText(), c.get("doc").asText(), false));
        }
        return cases;
    }

    /** Audit inputs too large for the case file. */
    private static List<DocCase> programmaticCases() {
        return List.of(
                new DocCase("audit-D8-depth-511", attributesNested(511), false),
                new DocCase("audit-D8-depth-512", attributesNested(512), false),
                new DocCase("audit-D8-depth-600", attributesNested(600), false),
                new DocCase("audit-D9-long-number", "<!-- wp:a {\"n\":0." + "0".repeat(1000) + "1} /-->", false),
                new DocCase("audit-D19-open-70", "<!-- wp:a -->".repeat(70), false),
                new DocCase(
                        "audit-D19-nested-1000",
                        "<!-- wp:a -->".repeat(1000) + "x" + "<!-- /wp:a -->".repeat(1000),
                        false),
                new DocCase(
                        "audit-D20-backtrack",
                        "<!-- wp:a {\"s\":\"" + "}a".repeat(1_000_000) + "\"} /--><!-- wp:b /-->",
                        false));
    }

    /** A void block whose attributes nest {@code containers} deep: one object holding nested arrays. */
    private static String attributesNested(int containers) {
        return "<!-- wp:a {\"x\":" + "[".repeat(containers - 1) + "]".repeat(containers - 1) + "} /-->";
    }

    private static List<DocCase> corpusCases() throws IOException {
        try (Stream<Path> files = Files.list(CORPUS)) {
            List<DocCase> cases = new ArrayList<>();
            for (Path p :
                    files.filter(f -> f.toString().endsWith(".html")).sorted().toList()) {
                cases.add(new DocCase("corpus/" + p.getFileName(), Files.readString(p, StandardCharsets.UTF_8), false));
            }
            return cases;
        }
    }

    /** Gutenberg's fixture files, when the conformance profile has fetched them; then there must be some. */
    private static List<DocCase> gutenbergCases() throws IOException, InterruptedException {
        Path dir = GutenbergFixtures.ensure();
        if (dir == null) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            List<DocCase> cases = new ArrayList<>();
            for (Path p : files.filter(f -> f.getFileName().toString().endsWith(".html"))
                    .sorted()
                    .toList()) {
                cases.add(new DocCase(
                        "gutenberg/" + p.getFileName(), Files.readString(p, StandardCharsets.UTF_8), false));
            }
            assertThat(cases.size())
                    .as("Gutenberg fixtures in %s", dir)
                    .isGreaterThanOrEqualTo(GutenbergConformanceTest.MIN_FIXTURES);
            return cases;
        }
    }

    private static List<DocCase> generatedCases() {
        List<DocCase> cases = new ArrayList<>();
        List<String> soup = GeneratedCases.sample(BlockMarkupArbitraries.commentSoup(), "20261014", SOUP);
        for (int i = 0; i < soup.size(); i++) {
            cases.add(new DocCase("gen/soup-" + i, soup.get(i), true));
        }
        List<String> variants = GeneratedCases.sample(BlockMarkupArbitraries.delimiterVariants(), "20261015", VARIANTS);
        for (int i = 0; i < variants.size(); i++) {
            cases.add(new DocCase("gen/variants-" + i, variants.get(i), true));
        }
        return cases;
    }
}
