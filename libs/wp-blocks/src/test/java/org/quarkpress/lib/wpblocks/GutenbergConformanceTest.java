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
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Compares {@link WpBlocks#parse} with the {@code *.parsed.json} output published with Gutenberg's
 * parser fixtures, asserts the byte-identical round trip on every fixture file, and asserts that the
 * canonical serializer reproduces Gutenberg's {@code *.serialized.html}. Active only with
 * {@code -Pconformance}.
 */
@EnabledIfSystemProperty(named = "gutenberg.fixtures.dir", matches = ".+")
class GutenbergConformanceTest {

    /** Sanity floor: the pinned commit has 374 fixtures; far fewer means a wrong path or empty checkout. */
    static final int MIN_FIXTURES = 300;

    private static Path dir;
    private static Map<String, String> skips;

    @BeforeAll
    static void fetch() throws Exception {
        dir = GutenbergFixtures.ensure();
        assertThat(dir).isNotNull();
        int count = listFixtureNames(dir).size();
        assertThat(count)
                .as("fixtures in %s: found %d, expected at least %d", dir, count, MIN_FIXTURES)
                .isGreaterThanOrEqualTo(MIN_FIXTURES);
        skips = readSkips();
    }

    static Stream<String> fixtureNames() throws Exception {
        Path d = GutenbergFixtures.ensure();
        List<String> names = listFixtureNames(d);
        if (names.size() < MIN_FIXTURES) {
            throw new IllegalStateException(
                    "fixtures in " + d + ": found " + names.size() + ", expected at least " + MIN_FIXTURES);
        }
        return names.stream();
    }

    static List<String> listFixtureNames(Path d) throws IOException {
        try (Stream<Path> files = Files.list(d)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".html") && !n.endsWith(".serialized.html"))
                    .map(n -> n.substring(0, n.length() - ".html".length()))
                    .sorted()
                    .toList();
        }
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureNames")
    void parsedOutputMatchesGutenberg(String name) throws IOException {
        Assumptions.assumeFalse(skips.containsKey(name), () -> "skipped: " + skips.get(name));
        String html = Files.readString(dir.resolve(name + ".html"), StandardCharsets.UTF_8);
        JsonNode expected =
                normalise(AttributeJson.MAPPER.readTree(Files.readString(dir.resolve(name + ".parsed.json"))));
        // Gutenberg's fixture harness trims the input; some fixtures start with whitespace.
        JsonNode actual = toJson(WpBlocks.parse(html.strip()));
        assertThat(actual).as(name).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureNames")
    void everyFixtureRoundTripsByteIdentically(String name) throws IOException {
        for (String suffix : List.of(".html", ".serialized.html")) {
            Path p = dir.resolve(name + suffix);
            if (Files.exists(p)) {
                String doc = Files.readString(p, StandardCharsets.UTF_8);
                assertThat(WpBlocks.serialize(WpBlocks.parse(doc)))
                        .as(name + suffix)
                        .isEqualTo(doc);
            }
        }
    }

    /**
     * Serializes every parsed block canonically (raw delimiter and attribute text ignored) and compares the result
     * with Gutenberg's own serializer output.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtureNames")
    void canonicalSerializationMatchesGutenberg(String name) throws IOException {
        Path p = dir.resolve(name + ".serialized.html");
        Assumptions.assumeTrue(Files.exists(p), () -> "no serialized fixture for " + name);
        String doc = Files.readString(p, StandardCharsets.UTF_8);
        assertThat(WpBlocks.serializeCanonical(WpBlocks.parse(doc)))
                .as(name + ".serialized.html")
                .isEqualTo(doc);
    }

    // ---- helpers ----

    static JsonNode toJson(List<Block> blocks) {
        ArrayNode arr = JsonNodeFactory.instance.arrayNode();
        for (Block b : blocks) {
            arr.add(toJson(b));
        }
        return arr;
    }

    static ObjectNode toJson(Block b) {
        ObjectNode o = JsonNodeFactory.instance.objectNode();
        if (b.name() == null) {
            o.putNull("blockName");
        } else {
            o.put("blockName", b.name());
        }
        o.set("attrs", b.attributes());
        o.set("innerBlocks", toJson(b.innerBlocks()));
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

    /** Older fixtures may say {@code "attrs": null}; the parser we compare with emits {@code {}}. */
    static JsonNode normalise(JsonNode node) {
        if (node.isArray()) {
            for (JsonNode child : node) {
                normalise(child);
            }
        } else if (node instanceof ObjectNode o) {
            if (o.has("attrs") && o.get("attrs").isNull()) {
                o.set("attrs", JsonNodeFactory.instance.objectNode());
            }
            if (o.has("innerBlocks")) {
                normalise(o.get("innerBlocks"));
            }
        }
        return node;
    }

    static Map<String, String> readSkips() {
        try (var in = GutenbergConformanceTest.class.getResourceAsStream("/conformance-skip.txt")) {
            assertThat(in).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.split("\t", 2))
                    .collect(
                            java.util.stream.Collectors.toMap(a -> a[0], a -> a.length > 1 ? a[1] : "no reason given"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
