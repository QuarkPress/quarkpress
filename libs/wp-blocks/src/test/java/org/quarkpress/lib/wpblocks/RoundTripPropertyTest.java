package org.quarkpress.lib.wpblocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.Combinators;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.IntRange;

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

    @Property(tries = 1500)
    void invariantsHoldOnEveryParsedBlock(@ForAll("documents") String doc) {
        WpBlocks.parse(doc).forEach(RoundTripPropertyTest::checkInvariants);
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

    private Arbitrary<ObjectNode> attrs() {
        Arbitrary<String> key =
                Arbitraries.strings().withChars("abcxyz_").ofMinLength(1).ofMaxLength(5);
        Arbitrary<String> strVal =
                Arbitraries.strings().withChars("ab -<>&\"\\/{}:é\n").ofMaxLength(12);
        return Arbitraries.maps(key, strVal).ofMaxSize(3).map(m -> {
            ObjectNode n = JsonNodeFactory.instance.objectNode();
            m.forEach(n::put);
            return n;
        });
    }
}
