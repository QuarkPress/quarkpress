package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.random.RandomGenerator;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

/**
 * The lenient reader on damaged copies of generated documents (nasty text, split CDATA, character references) and on
 * random bytes. {@code RecoveryFuzzTest} does the same to the reference exports; this adds what only the writer
 * produces and input that is not XML at all.
 */
class LenientFuzzTest {

    private static final byte[][] NOISE = {
        "&".getBytes(StandardCharsets.UTF_8),
        "<".getBytes(StandardCharsets.UTF_8),
        ">".getBytes(StandardCharsets.UTF_8),
        "]]>".getBytes(StandardCharsets.UTF_8),
        "<![CDATA[".getBytes(StandardCharsets.UTF_8),
        "<!--".getBytes(StandardCharsets.UTF_8),
        "<!DOCTYPE x [".getBytes(StandardCharsets.UTF_8),
        "\"".getBytes(StandardCharsets.UTF_8),
        "</item>".getBytes(StandardCharsets.UTF_8),
        "<wp:comment>".getBytes(StandardCharsets.UTF_8),
        {0},
        {1},
        {(byte) 0xFF},
        {(byte) 0xC3},
        {(byte) 0xE2, (byte) 0x82}
    };

    /** A generated document and a damaged copy of it. */
    private record Damaged(WxrArbitraries.Document doc, byte[] original, byte[] bytes, boolean cut) {}

    @Test
    void mutatedDocumentsReadToTheEndAndAgreeWithStrict() {
        Gen<Damaged> mutated = random -> {
            WxrArbitraries.Document doc = WxrArbitraries.documents(true).next(random);
            byte[] original = doc.write();
            return new Damaged(doc, original, mutate(original, random), false);
        };
        Map<String, Integer> seen = new TreeMap<>();
        Properties.check("lenient reader on mutated WXR", 1_000, mutated, d -> {
            TestWxr.Result lenient = TestWxr.read(d.bytes, WxrReadOptions.LENIENT);
            assertThat(lenient.problems().size()).isLessThanOrEqualTo(ProblemLog.MAX_STORED + 1);
            if (Arrays.equals(d.bytes, d.original)) {
                assertThat(lenient.problems()).isEmpty();
                return;
            }
            // Strict either throws a WxrException or reads the same entries as lenient.
            try {
                TestWxr.Result strict = TestWxr.read(d.bytes, WxrReadOptions.STRICT);
                assertThat(strict.problems()).isEmpty();
                // lenient may still note damage after the end of the channel, which strict never reads
                assertThat(lenient.entries()).isEqualTo(strict.entries());
                seen.merge("both accept", 1, Integer::sum);
            } catch (WxrException e) {
                assertThat(lenient.problems())
                        .as("strict refused: %s", e.getMessage())
                        .isNotEmpty();
                seen.merge("strict refuses", 1, Integer::sum);
            }
        });
        assertThat(seen).containsKeys("strict refuses");
        assertThat(seen.get("strict refuses")).isGreaterThan(50);
    }

    @Test
    void cutDocumentsDeliverTheirCompleteEntriesAndSayTheyWereCut() {
        Gen<Damaged> cut = random -> {
            WxrArbitraries.Document doc = WxrArbitraries.documents(true).next(random);
            byte[] original = doc.write();
            int end = indexOf(original, "</channel>");
            return new Damaged(doc, original, Arrays.copyOf(original, random.nextInt(end)), true);
        };
        Properties.check("lenient reader on cut WXR", 1_000, cut, d -> {
            List<WxrEntry> expected = d.doc.sanitized().entries();
            TestWxr.Result r = TestWxr.read(d.bytes, WxrReadOptions.LENIENT);
            List<WxrEntry> got = r.entries().stream().map(Canonical::strip).toList();
            assertThat(got.size()).isLessThanOrEqualTo(expected.size());
            // every entry that ends before the cut is delivered unchanged; at most one partial entry follows
            int complete = completeBefore(d.original, d.bytes.length);
            assertThat(got.size()).isGreaterThanOrEqualTo(complete);
            assertThat(got.subList(0, complete)).isEqualTo(expected.subList(0, complete));
            assertThat(got.size()).isLessThanOrEqualTo(complete + 1);
            if (got.size() > complete) {
                assertThat(got.get(complete).getClass())
                        .isEqualTo(expected.get(complete).getClass());
            }
            assertThat(r.kinds())
                    .containsAnyOf(WxrProblem.Kind.TRUNCATED, WxrProblem.Kind.MALFORMED, WxrProblem.Kind.NOT_WXR);
        });
    }

    @Test
    void randomBytesAreReportedNotThrown() {
        Properties.check("lenient reader on random bytes", 500, Gens.bytes(0, 2_000), bytes -> {
            TestWxr.Result r = TestWxr.read(bytes, WxrReadOptions.LENIENT.withGzipAutoDetect(false));
            assertThat(r.entries()).isEmpty();
            assertThat(r.problems()).isNotEmpty();
            assertThat(r.problems().size()).isLessThanOrEqualTo(ProblemLog.MAX_STORED + 1);
        });
    }

    private static final List<String> ENTRY_ENDS =
            List.of("</wp:author>", "</wp:category>", "</wp:tag>", "</wp:term>", "</item>");

    /** How many entries of the written document end at or before byte {@code at}. */
    private static int completeBefore(byte[] original, int at) {
        String text = new String(original, StandardCharsets.ISO_8859_1);
        int count = 0;
        for (String end : ENTRY_ENDS) {
            for (int i = text.indexOf(end); i >= 0 && i + end.length() <= at; i = text.indexOf(end, i + 1)) {
                count++;
            }
        }
        return count;
    }

    private static int indexOf(byte[] haystack, String needle) {
        String text = new String(haystack, StandardCharsets.ISO_8859_1);
        return text.indexOf(needle);
    }

    /** One to four edits after the first 64 bytes (the XML declaration stays intact). */
    static byte[] mutate(byte[] doc, RandomGenerator random) {
        byte[] out = doc;
        for (int ops = 1 + random.nextInt(4); ops > 0; ops--) {
            int keep = Math.min(64, out.length);
            int at = keep + random.nextInt(Math.max(1, out.length - keep + 1));
            at = Math.min(at, out.length);
            switch (random.nextInt(5)) {
                case 0 -> out = Arrays.copyOf(out, at);
                case 1 -> out = splice(out, at, 0, NOISE[random.nextInt(NOISE.length)]);
                case 2 -> out = splice(out, at, Math.min(out.length - at, random.nextInt(40)), new byte[0]);
                case 3 -> {
                    int n = Math.min(out.length - at, random.nextInt(200));
                    out = splice(out, at, 0, Arrays.copyOfRange(out, at, at + n));
                }
                default -> {
                    if (at < out.length) {
                        out = out.clone();
                        out[at] = (byte) random.nextInt(256);
                    }
                }
            }
        }
        return out;
    }

    private static byte[] splice(byte[] in, int at, int remove, byte[] insert) {
        byte[] out = new byte[in.length - remove + insert.length];
        System.arraycopy(in, 0, out, 0, at);
        System.arraycopy(insert, 0, out, at, insert.length);
        System.arraycopy(in, at + remove, out, at + insert.length, in.length - at - remove);
        return out;
    }
}
