package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

/**
 * Lenient reads of cut and damaged copies of the example exports: they complete without an exception, deliver the
 * entries before the damage unchanged, flag a changed last entry, and record a problem whenever the damage is one a
 * reader can always see.
 */
class RecoveryFuzzTest {

    private static final List<String> SOURCES = List.of("example-a.xml", "example-b.xml", "example-c.xml");

    private static final List<String> ENTITY_ENDS =
            List.of("</wp:author>", "</wp:category>", "</wp:tag>", "</wp:term>", "</item>");

    /** Markup and references dropped into a random place; whether a reader notices depends on where. */
    private static final List<String> GARBAGE = List.of(
            "<",
            "&",
            ">",
            "]]>",
            "</x>",
            "<x>",
            "<x",
            "\"",
            "'",
            "<!--",
            "-->",
            "<![CDATA[",
            "<?",
            "?>",
            "<!DOCTYPE x>",
            "&#0;",
            "&bogus;",
            "&#x110000;",
            "</item>",
            "<item>",
            "</channel>",
            "<wp:postmeta>",
            "=",
            "<a b=c>");

    private record Source(String name, byte[] bytes, String latin1, List<WxrEntry> entries) {}

    /** A changed copy: {@code at} is the first byte that differs; {@code visible}: a reader must always notice. */
    private record Damaged(Source source, String how, byte[] bytes, int at, boolean truncation, boolean visible) {
        @Override
        public String toString() {
            return source.name + " " + how + " at " + at;
        }
    }

    private static Source load(String name) {
        byte[] bytes = TestWxr.resource(name);
        TestWxr.Result clean = TestWxr.read(bytes, WxrReadOptions.LENIENT);
        assertThat(clean.problems()).as(name).isEmpty();
        return new Source(name, bytes, new String(bytes, StandardCharsets.ISO_8859_1), clean.entries());
    }

    private static Gen<Damaged> damage(List<Source> sources) {
        Gen<Source> source = Gens.elements(sources);
        return source.flatMap(s -> {
            int length = s.bytes.length;
            // cuts end before the '>' of </channel>, so that the document is always unfinished
            int lastCut = s.latin1.indexOf("</channel>") + "</channel".length();
            Gen<Damaged> cut =
                    Gens.ints(0, lastCut).map(at -> new Damaged(s, "cut", slice(s.bytes, at), at, true, true));
            Gen<Damaged> control = Gens.ints(0, length)
                    .flatMap(at -> Gens.elements(1, 2, 8, 11, 12, 14, 27, 31)
                            .map(b -> new Damaged(
                                    s,
                                    "control " + b,
                                    insert(s.bytes, at, new byte[] {(byte) (int) b}),
                                    at,
                                    false,
                                    true)));
            Gen<Damaged> badByte = Gens.ints(0, length)
                    .flatMap(at -> Gens.elements(0x80, 0xC0, 0xF8, 0xFF)
                            .map(b -> new Damaged(
                                    s,
                                    "byte " + b,
                                    insert(s.bytes, at, new byte[] {(byte) (int) b}),
                                    at,
                                    false,
                                    true)));
            Gen<Damaged> garbage = Gens.ints(0, length)
                    .flatMap(at -> Gens.elements(GARBAGE)
                            .map(g -> new Damaged(
                                    s,
                                    "garbage " + g,
                                    insert(s.bytes, at, g.getBytes(StandardCharsets.UTF_8)),
                                    at,
                                    false,
                                    false)));
            Gen<Damaged> delete = Gens.ints(0, length - 1)
                    .flatMap(at -> Gens.ints(1, 40)
                            .map(n -> new Damaged(
                                    s,
                                    "delete " + n,
                                    remove(s.bytes, at, Math.min(n, length - at)),
                                    at,
                                    false,
                                    false)));
            Gen<Damaged> repeat = Gens.ints(0, length - 1)
                    .flatMap(at -> Gens.ints(1, 200)
                            .map(n -> new Damaged(
                                    s,
                                    "repeat " + n,
                                    insert(s.bytes, at, slice(s.bytes, at, Math.min(length, at + n))),
                                    at,
                                    false,
                                    false)));
            return Gens.frequency(
                    Gens.weighted(4, cut),
                    Gens.weighted(1, control),
                    Gens.weighted(1, badByte),
                    Gens.weighted(3, garbage),
                    Gens.weighted(2, delete),
                    Gens.weighted(1, repeat));
        });
    }

    @Test
    void damagedExportsReadToTheEndWithTheirDamageReported() {
        List<Source> sources = SOURCES.stream().map(RecoveryFuzzTest::load).toList();
        Map<String, Integer> seen = new TreeMap<>();
        Properties.check("wxr recovery of damaged exports", 2_000, damage(sources), d -> {
            TestWxr.Result r = TestWxr.read(d.bytes, WxrReadOptions.LENIENT);
            List<WxrEntry> original = d.source.entries;
            List<WxrEntry> got = r.entries();
            boolean broken =
                    r.kinds().contains(WxrProblem.Kind.TRUNCATED) || r.kinds().contains(WxrProblem.Kind.MALFORMED);

            int before = completeBefore(d.source.latin1, d.at);
            assertThat(got.size()).as("entries before the damage").isGreaterThanOrEqualTo(before);
            assertThat(got.subList(0, before)).as("entries before the damage").isEqualTo(original.subList(0, before));

            if (d.truncation) {
                assertThat(got.size()).isLessThanOrEqualTo(original.size());
                int last = got.size() - 1;
                assertThat(got.subList(0, Math.max(0, last))).isEqualTo(original.subList(0, Math.max(0, last)));
                if (last >= 0 && !got.get(last).equals(original.get(last))) {
                    assertThat(broken).as("a changed last entry is flagged").isTrue();
                }
                assertThat(r.kinds())
                        .as("a cut file")
                        .containsAnyOf(WxrProblem.Kind.TRUNCATED, WxrProblem.Kind.MALFORMED, WxrProblem.Kind.NOT_WXR);
            }
            if (d.visible) {
                assertThat(r.problems()).as("problems of a changed file").isNotEmpty();
            }
            for (WxrProblem.Kind kind : r.kinds()) {
                seen.merge(kind.name(), 1, Integer::sum);
            }
            if (d.truncation && !got.isEmpty() && !got.getLast().equals(original.get(got.size() - 1))) {
                seen.merge("partial", 1, Integer::sum);
            }
        });
        // the generator reaches every kind of recovery this test is about
        assertThat(seen)
                .as("outcomes")
                .containsKeys("TRUNCATED", "MALFORMED", "NOT_WXR", "partial", "ILLEGAL_CHARACTER", "INVALID_ENCODING");
        assertThat(seen.get("partial")).as("cuts that left a partial entry").isGreaterThan(50);
        assertThat(seen.get("MALFORMED"))
                .as("reads that stopped at broken markup")
                .isGreaterThan(50);
    }

    /** How many entries of the original end at or before byte {@code at}. */
    private static int completeBefore(String latin1, int at) {
        int count = 0;
        for (String end : ENTITY_ENDS) {
            int i = latin1.indexOf(end);
            while (i >= 0 && i + end.length() <= at) {
                count++;
                i = latin1.indexOf(end, i + end.length());
            }
        }
        return count;
    }

    private static byte[] slice(byte[] bytes, int to) {
        return slice(bytes, 0, to);
    }

    private static byte[] slice(byte[] bytes, int from, int to) {
        byte[] out = new byte[to - from];
        System.arraycopy(bytes, from, out, 0, out.length);
        return out;
    }

    private static byte[] insert(byte[] bytes, int at, byte[] extra) {
        byte[] out = new byte[bytes.length + extra.length];
        System.arraycopy(bytes, 0, out, 0, at);
        System.arraycopy(extra, 0, out, at, extra.length);
        System.arraycopy(bytes, at, out, at + extra.length, bytes.length - at);
        return out;
    }

    private static byte[] remove(byte[] bytes, int at, int n) {
        byte[] out = new byte[bytes.length - n];
        System.arraycopy(bytes, 0, out, 0, at);
        System.arraycopy(bytes, at + n, out, at, bytes.length - at - n);
        return out;
    }
}
