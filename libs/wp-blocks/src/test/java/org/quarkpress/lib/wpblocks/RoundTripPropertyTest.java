package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class RoundTripPropertyTest {

    @Test
    void everyStringRoundTripsByteIdentically() {
        Properties.check("RoundTripPropertyTest.everyStringRoundTripsByteIdentically", 2000, documents(), doc -> {
            assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
        });
    }

    @Test
    void arbitraryStringsRoundTripByteIdentically() {
        Properties.check(
                "RoundTripPropertyTest.arbitraryStringsRoundTripByteIdentically",
                2000,
                Gens.strings(Gens.unicodeChars(), 0, 255),
                junk -> {
                    assertThat(WpBlocks.serialize(WpBlocks.parse(junk))).isEqualTo(junk);
                });
    }

    /** A document, a position (modulo the length) and the character to write there. */
    private record Mutation(String doc, int at, char c) {}

    @Test
    void mutatedDocumentsRoundTripByteIdentically() {
        Gen<String> documents = documents();
        Gen<Integer> positions = Gens.ints(0, 10_000);
        Gen<Character> chars = fragmentChars();
        Gen<Mutation> mutations =
                random -> new Mutation(documents.next(random), positions.next(random), chars.next(random));
        Properties.check("RoundTripPropertyTest.mutatedDocumentsRoundTripByteIdentically", 2000, mutations, m -> {
            String doc = m.doc();
            if (doc.isEmpty()) {
                return;
            }
            int i = m.at() % doc.length();
            String mutated = doc.substring(0, i) + m.c() + doc.substring(i + 1);
            assertThat(WpBlocks.serialize(WpBlocks.parse(mutated))).isEqualTo(mutated);
        });
    }

    @Test
    void parseOfSerializeIsStructurallyEqual() {
        Properties.check("RoundTripPropertyTest.parseOfSerializeIsStructurallyEqual", 1500, trees(), tree -> {
            String doc = WpBlocks.serialize(tree);
            List<Block> back = WpBlocks.parse(doc);
            assertThat(back).isEqualTo(tree);
            assertThat(WpBlocks.serialize(back)).isEqualTo(doc);
        });
    }

    @Test
    void soupRoundTripsByteIdentically() {
        Properties.check(
                "RoundTripPropertyTest.soupRoundTripsByteIdentically",
                2000,
                BlockMarkupArbitraries.commentSoup(),
                doc -> {
                    assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
                });
    }

    @Test
    void delimiterVariantsRoundTripByteIdentically() {
        Properties.check(
                "RoundTripPropertyTest.delimiterVariantsRoundTripByteIdentically",
                2000,
                BlockMarkupArbitraries.delimiterVariants(),
                doc -> {
                    assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
                });
    }

    @Test
    void invariantsHoldOnEveryParsedBlock() {
        Properties.check(
                "RoundTripPropertyTest.invariantsHoldOnEveryParsedBlock",
                1500,
                documents(),
                doc -> WpBlocks.parse(doc).forEach(RoundTripPropertyTest::checkInvariants));
    }

    @Test
    void invariantsHoldOnSoup() {
        Properties.check(
                "RoundTripPropertyTest.invariantsHoldOnSoup",
                1500,
                BlockMarkupArbitraries.commentSoup(),
                doc -> WpBlocks.parse(doc).forEach(RoundTripPropertyTest::checkInvariants));
    }

    @Test
    void invariantsHoldOnDelimiterVariants() {
        Properties.check(
                "RoundTripPropertyTest.invariantsHoldOnDelimiterVariants",
                1500,
                BlockMarkupArbitraries.delimiterVariants(),
                doc -> WpBlocks.parse(doc).forEach(RoundTripPropertyTest::checkInvariants));
    }

    private static final java.util.regex.Pattern OPENER =
            java.util.regex.Pattern.compile("<!--\\s+wp:[a-z0-9/_-]+(\\s+\\{[^}]*\\})?\\s+-->");
    private static final java.util.regex.Pattern CLOSER =
            java.util.regex.Pattern.compile("<!--\\s+/wp:[a-z0-9/_-]+\\s+-->");

    /** Guards against vacuous generation: variants must actually nest and leave blocks unclosed. */
    @Test
    void delimiterVariantsAreNotVacuous() {
        int tries = 1000;
        int[] hasBlock = {0};
        int[] unclosed = {0};
        Properties.check(
                "RoundTripPropertyTest.delimiterVariantsAreNotVacuous",
                tries,
                BlockMarkupArbitraries.delimiterVariants(),
                doc -> {
                    if (WpBlocks.parse(doc).stream().anyMatch(b -> !b.isFreeform())) {
                        hasBlock[0]++;
                    }
                    long openers = OPENER.matcher(doc).results().count();
                    long closers = CLOSER.matcher(doc).results().count();
                    if (openers > closers) {
                        unclosed[0]++;
                    }
                });
        assertThat(hasBlock[0] * 100.0 / tries).as("%% with a block").isGreaterThanOrEqualTo(30.0);
        assertThat(unclosed[0] * 100.0 / tries).as("%% with an unclosed opener").isGreaterThanOrEqualTo(10.0);
    }

    private static void checkInvariants(Block b) {
        long nulls = b.innerContent().stream().filter(c -> c == null).count();
        assertThat(nulls).isEqualTo(b.innerBlocks().size());
        StringBuilder sb = new StringBuilder();
        b.innerContent().stream().filter(c -> c != null).forEach(sb::append);
        assertThat(sb.toString()).isEqualTo(b.innerHtml());
        if (b.isFreeform()) {
            assertThat(b.attributes().isEmpty()).isTrue();
            assertThat(b.innerBlocks()).isEmpty();
            assertThat(b.innerContent()).hasSize(1);
        }
        b.innerBlocks().forEach(RoundTripPropertyTest::checkInvariants);
    }

    // ---- generators ----

    /** Serialized documents assembled from random trees plus random freeform text and junk. */
    private static Gen<String> documents() {
        return trees().map(WpBlocks::serialize);
    }

    private static Gen<Character> fragmentChars() {
        return Gens.elements('<', '!', '-', '>', ' ', '/', 'w', 'p', ':', '{', '}', '"', '\\', 'a', '\n');
    }

    private static Gen<List<Block>> trees() {
        return Gens.lists(blocks(0), 0, 6).map(RoundTripPropertyTest::mergeFreeform);
    }

    /** Adjacent top-level freeform blocks would merge on re-parse, so merge them in the generator. */
    private static List<Block> mergeFreeform(List<Block> in) {
        java.util.ArrayList<Block> out = new java.util.ArrayList<>();
        for (Block b : in) {
            if (b.isFreeform() && !out.isEmpty() && out.get(out.size() - 1).isFreeform()) {
                Block prev = out.remove(out.size() - 1);
                out.add(Block.freeform(prev.innerHtml() + b.innerHtml()));
            } else {
                out.add(b);
            }
        }
        return out;
    }

    private static Gen<Block> blocks(int depth) {
        Gen<Block> leaf = Gens.oneOf(freeform(), voidBlock(), textBlock());
        if (depth >= 3) {
            return leaf;
        }
        return Gens.frequency(Gens.weighted(3, leaf), Gens.weighted(1, container(depth + 1)));
    }

    private static Gen<Block> freeform() {
        return text().filter(t -> !t.isEmpty()).map(Block::freeform);
    }

    private static Gen<Block> voidBlock() {
        Gen<String> names = names();
        Gen<ObjectNode> attrs = attrs();
        return random -> Block.of(names.next(random), attrs.next(random));
    }

    private static Gen<Block> textBlock() {
        Gen<String> names = names();
        Gen<ObjectNode> attrs = attrs();
        Gen<String> texts = text().filter(t -> !t.isEmpty());
        return random -> Block.builder(names.next(random))
                .attributes(attrs.next(random))
                .text(texts.next(random))
                .build();
    }

    private static Gen<Block> container(int depth) {
        Gen<String> names = names();
        Gen<ObjectNode> attrs = attrs();
        Gen<String> texts = text();
        Gen<List<Block>> children = Gens.lists(blocks(depth), 1, 3);
        return random -> {
            Block.Builder b = Block.builder(names.next(random))
                    .attributes(attrs.next(random))
                    .text(texts.next(random));
            for (Block child : children.next(random)) {
                if (child.isFreeform()) {
                    b.text(child.innerHtml()); // freeform inside a block is just text
                } else {
                    b.inner(child);
                }
            }
            return b.text(texts.next(random)).build();
        };
    }

    private static Gen<String> names() {
        return Gens.elements("paragraph", "core/group", "acme/hero", "a", "my-plugin/x_y-2");
    }

    /** Text that cannot itself form a delimiter: no '!' means no "<!--". */
    private static Gen<String> text() {
        return Gens.strings("abc <>/pdiv\n\t\"'&=-}{:\\é✓", 0, 24);
    }

    /** Attribute objects with every JSON value kind, nested up to two levels below the top object. */
    private static Gen<ObjectNode> attrs() {
        return objectOf(0);
    }

    private static Gen<ObjectNode> objectOf(int depth) {
        Gen<String> keys = Gens.strings("abcxyz_", 1, 5);
        Gen<JsonNode> values = jsonValue(depth);
        Gen<List<String>> keyLists = Gens.lists(keys, 0, 3);
        return random -> {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            for (String key : keyLists.next(random)) {
                if (!n.has(key)) {
                    n.set(key, values.next(random));
                }
            }
            return n;
        };
    }

    private static Gen<JsonNode> jsonValue(int depth) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        Gen<JsonNode> str = Gens.strings("ab -<>&\"\\/{}:é\n", 0, 12).map(f::textNode);
        Gen<JsonNode> ints = Gens.ints(Integer.MIN_VALUE, Integer.MAX_VALUE).map(f::numberNode);
        Gen<JsonNode> longs = Gens.longs(Long.MIN_VALUE, Long.MAX_VALUE)
                .filter(l -> l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)
                .map(f::numberNode);
        Gen<JsonNode> doubles = Gens.doubles(-1e9, 1e9).map(f::numberNode);
        Gen<JsonNode> floats = Gens.doubles(-1e6, 1e6).map(d -> f.numberNode(d.floatValue()));
        Gen<JsonNode> decimals = Gens.ints(-100_000, 100_000)
                .map(i -> f.numberNode(BigDecimal.valueOf(i, 2).setScale(3))); // trailing zero
        Gen<JsonNode> bools = Gens.booleans().map(f::booleanNode);
        Gen<JsonNode> nulls = Gens.constant(f.nullNode());
        Gen<JsonNode> scalar = Gens.oneOf(str, ints, longs, doubles, floats, decimals, bools, nulls);
        if (depth >= 2) {
            return scalar;
        }
        Gen<JsonNode> array = Gens.lists(jsonValue(depth + 1), 0, 3).map(l -> {
            ArrayNode a = f.arrayNode();
            l.forEach(a::add);
            return a;
        });
        Gen<JsonNode> object = objectOf(depth + 1).map(o -> o);
        return Gens.frequency(Gens.weighted(6, scalar), Gens.weighted(1, array), Gens.weighted(1, object));
    }
}
