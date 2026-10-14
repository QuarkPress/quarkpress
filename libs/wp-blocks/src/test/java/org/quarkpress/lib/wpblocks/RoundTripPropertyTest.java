package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.math.BigDecimal;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.statistics.Statistics;

class RoundTripPropertyTest {

    @Property(tries = 2000)
    void everyStringRoundTripsByteIdentically(@ForAll("documents") String doc) {
        assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
    }

    @Property(tries = 2000)
    void arbitraryStringsRoundTripByteIdentically(@ForAll String junk) {
        assertThat(WpBlocks.serialize(WpBlocks.parse(junk))).isEqualTo(junk);
    }

    @Property(tries = 2000)
    void mutatedDocumentsRoundTripByteIdentically(
            @ForAll("documents") String doc,
            @ForAll @IntRange(min = 0, max = 10_000) int at,
            @ForAll("fragmentChars") char c) {
        if (doc.isEmpty()) {
            return;
        }
        int i = at % doc.length();
        String mutated = doc.substring(0, i) + c + doc.substring(i + 1);
        assertThat(WpBlocks.serialize(WpBlocks.parse(mutated))).isEqualTo(mutated);
    }

    @Property(tries = 1500)
    void parseOfSerializeIsStructurallyEqual(@ForAll("trees") List<Block> tree) {
        String doc = WpBlocks.serialize(tree);
        List<Block> back = WpBlocks.parse(doc);
        assertThat(back).isEqualTo(tree);
        assertThat(WpBlocks.serialize(back)).isEqualTo(doc);
    }

    @Property(tries = 2000)
    void soupRoundTripsByteIdentically(@ForAll("commentSoup") String doc) {
        assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
    }

    @Property(tries = 2000)
    void delimiterVariantsRoundTripByteIdentically(@ForAll("delimiterVariants") String doc) {
        assertThat(WpBlocks.serialize(WpBlocks.parse(doc))).isEqualTo(doc);
    }

    @Property(tries = 1500)
    void invariantsHoldOnEveryParsedBlock(@ForAll("documents") String doc) {
        WpBlocks.parse(doc).forEach(RoundTripPropertyTest::checkInvariants);
    }

    @Property(tries = 1500)
    void invariantsHoldOnSoup(@ForAll("commentSoup") String doc) {
        WpBlocks.parse(doc).forEach(RoundTripPropertyTest::checkInvariants);
    }

    @Property(tries = 1500)
    void invariantsHoldOnDelimiterVariants(@ForAll("delimiterVariants") String doc) {
        WpBlocks.parse(doc).forEach(RoundTripPropertyTest::checkInvariants);
    }

    private static final java.util.regex.Pattern OPENER =
            java.util.regex.Pattern.compile("<!--\\s+wp:[a-z0-9/_-]+(\\s+\\{[^}]*\\})?\\s+-->");
    private static final java.util.regex.Pattern CLOSER =
            java.util.regex.Pattern.compile("<!--\\s+/wp:[a-z0-9/_-]+\\s+-->");

    /** Guards against vacuous generation: variants must actually nest and leave blocks unclosed. */
    @Property(tries = 1000)
    void delimiterVariantsAreNotVacuous(@ForAll("delimiterVariants") String doc) {
        boolean hasBlock = WpBlocks.parse(doc).stream().anyMatch(b -> !b.isFreeform());
        long openers = OPENER.matcher(doc).results().count();
        long closers = CLOSER.matcher(doc).results().count();
        Statistics.label("hasBlock").collect(hasBlock);
        Statistics.label("unclosed").collect(openers > closers);
        Statistics.label("hasBlock").coverage(c -> c.check(true).percentage(p -> p >= 30.0));
        Statistics.label("unclosed").coverage(c -> c.check(true).percentage(p -> p >= 10.0));
    }

    @Provide
    Arbitrary<String> commentSoup() {
        return BlockMarkupArbitraries.commentSoup();
    }

    @Provide
    Arbitrary<String> delimiterVariants() {
        return BlockMarkupArbitraries.delimiterVariants();
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
    @Provide
    Arbitrary<String> documents() {
        return trees().map(WpBlocks::serialize);
    }

    @Provide
    Arbitrary<Character> fragmentChars() {
        return Arbitraries.of('<', '!', '-', '>', ' ', '/', 'w', 'p', ':', '{', '}', '"', '\\', 'a', '\n');
    }

    @Provide
    Arbitrary<List<Block>> trees() {
        return blocks(0).list().ofMinSize(0).ofMaxSize(6).map(RoundTripPropertyTest::mergeFreeform);
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

    private Arbitrary<Block> blocks(int depth) {
        Arbitrary<Block> leaf = Arbitraries.oneOf(freeform(), voidBlock(), textBlock());
        if (depth >= 3) {
            return leaf;
        }
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(3, leaf),
                net.jqwik.api.Tuple.of(1, Arbitraries.lazy(() -> container(depth + 1))));
    }

    private Arbitrary<Block> freeform() {
        return text().filter(t -> !t.isEmpty()).map(Block::freeform);
    }

    private Arbitrary<Block> voidBlock() {
        return Combinators.combine(names(), attrs()).as(Block::of);
    }

    private Arbitrary<Block> textBlock() {
        return Combinators.combine(names(), attrs(), text().filter(t -> !t.isEmpty()))
                .as((n, a, t) -> Block.builder(n).attributes(a).text(t).build());
    }

    private Arbitrary<Block> container(int depth) {
        return Combinators.combine(
                        names(),
                        attrs(),
                        text(),
                        blocks(depth).list().ofMinSize(1).ofMaxSize(3),
                        text())
                .as((n, a, before, inner, after) -> {
                    Block.Builder b = Block.builder(n).attributes(a).text(before);
                    for (Block child : inner) {
                        if (child.isFreeform()) {
                            b.text(child.innerHtml()); // freeform inside a block is just text
                        } else {
                            b.inner(child);
                        }
                    }
                    return b.text(after).build();
                });
    }

    private Arbitrary<String> names() {
        return Arbitraries.of("paragraph", "core/group", "acme/hero", "a", "my-plugin/x_y-2");
    }

    /** Text that cannot itself form a delimiter: no '!' means no "<!--". */
    private Arbitrary<String> text() {
        return Arbitraries.strings().withChars("abc <>/pdiv\n\t\"'&=-}{:\\é✓").ofMaxLength(24);
    }

    /** Attribute objects with every JSON value kind, nested up to two levels below the top object. */
    private Arbitrary<ObjectNode> attrs() {
        return objectOf(0);
    }

    private static Arbitrary<ObjectNode> objectOf(int depth) {
        Arbitrary<String> key =
                Arbitraries.strings().withChars("abcxyz_").ofMinLength(1).ofMaxLength(5);
        return Arbitraries.maps(key, jsonValue(depth)).ofMaxSize(3).map(m -> {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            m.forEach(n::set);
            return n;
        });
    }

    private static Arbitrary<JsonNode> jsonValue(int depth) {
        JsonNodeFactory f = JsonNodeFactory.instance;
        Arbitrary<JsonNode> str = Arbitraries.strings()
                .withChars("ab -<>&\"\\/{}:é\n")
                .ofMaxLength(12)
                .map(f::textNode);
        Arbitrary<JsonNode> ints = Arbitraries.integers().map(f::numberNode);
        Arbitrary<JsonNode> longs = Arbitraries.longs()
                .filter(l -> l < Integer.MIN_VALUE || l > Integer.MAX_VALUE)
                .map(f::numberNode);
        Arbitrary<JsonNode> doubles = Arbitraries.doubles().between(-1e9, 1e9).map(f::numberNode);
        Arbitrary<JsonNode> floats = Arbitraries.floats().between(-1e6f, 1e6f).map(f::numberNode);
        Arbitrary<JsonNode> decimals = Arbitraries.integers()
                .between(-100_000, 100_000)
                .map(i -> f.numberNode(BigDecimal.valueOf(i, 2).setScale(3))); // trailing zero
        Arbitrary<JsonNode> bools = Arbitraries.of(true, false).map(f::booleanNode);
        Arbitrary<JsonNode> nulls = Arbitraries.just(f.nullNode());
        Arbitrary<JsonNode> scalar = Arbitraries.oneOf(str, ints, longs, doubles, floats, decimals, bools, nulls);
        if (depth >= 2) {
            return scalar;
        }
        Arbitrary<JsonNode> array = Arbitraries.lazy(() -> jsonValue(depth + 1))
                .list()
                .ofMaxSize(3)
                .map(l -> {
                    ArrayNode a = f.arrayNode();
                    l.forEach(a::add);
                    return a;
                });
        Arbitrary<JsonNode> object = Arbitraries.lazy(() -> objectOf(depth + 1)).map(o -> o);
        return Arbitraries.frequencyOf(
                net.jqwik.api.Tuple.of(6, scalar), net.jqwik.api.Tuple.of(1, array), net.jqwik.api.Tuple.of(1, object));
    }
}
