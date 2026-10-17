package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

/**
 * What a hostile file cannot do. XXE, external DTDs, plain entity chains, IOException handling and heap use for many
 * items are pinned in {@code WxrReaderHardeningTest} and {@code StreamingMemoryTest}; this class adds the rest.
 */
class HostileInputTest {

    private static final Duration LIMIT = Duration.ofSeconds(20);
    private static final String HEAD =
            TestWxr.doc("@@").substring(0, TestWxr.doc("@@").indexOf("@@"));

    private static String item(String title) {
        return TestWxr.doc("<item><title>" + title + "</title><wp:post_id>1</wp:post_id></item>");
    }

    @Test
    void billionLaughsIsHarmless() {
        StringBuilder dtd = new StringBuilder("<!DOCTYPE rss [<!ENTITY lol0 \"lol\">");
        for (int i = 1; i < 10; i++) {
            dtd.append("<!ENTITY lol")
                    .append(i)
                    .append(" \"")
                    .append(("&lol" + (i - 1) + ";").repeat(10))
                    .append("\">");
        }
        dtd.append("]>\n");
        String xml = "<?xml version=\"1.0\"?>\n" + dtd + item("&lol9;").substring(item("").indexOf("<rss"));
        TestWxr.Result r = assertTimeoutPreemptively(LIMIT, () -> TestWxr.read(xml, WxrReadOptions.LENIENT));
        assertThat(r.items().get(0).title()).isEqualTo("&lol9;");
    }

    @Test
    void gzipBombStopsAtTheValueLimit() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write((HEAD + "<item><content:encoded><![CDATA[").getBytes(StandardCharsets.UTF_8));
            byte[] mib = new byte[1 << 20];
            Arrays.fill(mib, (byte) 'a');
            for (int i = 0; i < 64; i++) {
                out.write(mib);
            }
            out.write("]]></content:encoded></item></channel></rss>".getBytes(StandardCharsets.UTF_8));
        }
        byte[] bomb = gz.toByteArray();
        assertThat(bomb.length).isLessThan(1 << 20);
        assertTimeoutPreemptively(
                LIMIT,
                () -> assertThatThrownBy(() -> TestWxr.read(bomb, WxrReadOptions.LENIENT.withMaxCharsPerValue(1 << 20)))
                        .isInstanceOfSatisfying(
                                WxrException.class,
                                e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.VALUE_TOO_LARGE)));
    }

    @Test
    void preFilterIsLinearThroughTheReader() {
        // the filter alone is timed in RepairingReaderTest; these are the patterns that matter end to end
        List<String> titles = List.of(
                "& ".repeat(2_000_000),
                "<".repeat(4_000_000),
                "&#99999999;".repeat(400_000),
                ("&" + "a".repeat(40) + ";").repeat(100_000),
                "\u0001".repeat(4_000_000));
        // what the repaired title reads as: '&' and '<' stay text, a huge reference and a control char become U+FFFD
        List<String> expected = List.of(
                "& ".repeat(2_000_000),
                "<".repeat(4_000_000),
                "\uFFFD".repeat(400_000),
                ("&" + "a".repeat(40) + ";").repeat(100_000),
                "\uFFFD".repeat(4_000_000));
        WxrReadOptions options = WxrReadOptions.LENIENT.withMaxCharsPerValue(Integer.MAX_VALUE);
        for (String title : titles) {
            TestWxr.Result r = assertTimeoutPreemptively(LIMIT, () -> TestWxr.read(item(title), options));
            assertThat(r.items()).hasSize(1);
            assertThat(r.items().get(0).title()).isEqualTo(expected.get(titles.indexOf(title)));
            assertThat(r.problems()).hasSize(ProblemLog.MAX_STORED + 1);
        }
    }

    @Test
    void unterminatedSectionsEndAsTruncation() {
        for (String tail : List.of(
                "<item><content:encoded><![CDATA[" + "x".repeat(4_000_000),
                "<item><!--" + "x".repeat(4_000_000),
                "<item><title>" + "]".repeat(4_000_000))) {
            TestWxr.Result r =
                    assertTimeoutPreemptively(LIMIT, () -> TestWxr.read(HEAD + tail, WxrReadOptions.LENIENT));
            assertThat(r.kinds()).contains(WxrProblem.Kind.TRUNCATED);
        }
    }

    @Test
    void echoedInputIsClippedInProblemsAndMessages() {
        String huge = "9".repeat(5_000_000);
        // version: invalid and newer
        TestWxr.Result invalid = TestWxr.read(
                "<?xml version=\"1.0\"?><rss version=\"2.0\" " + TestWxr.NAMESPACES + "><channel><wp:wxr_version>x"
                        + huge + "</wp:wxr_version><item/></channel></rss>",
                WxrReadOptions.LENIENT.withMaxCharsPerValue(Integer.MAX_VALUE));
        assertClipped(invalid.problems(), WxrProblem.Kind.VERSION_INVALID);
        TestWxr.Result newer = TestWxr.read(
                "<?xml version=\"1.0\"?><rss version=\"2.0\" " + TestWxr.NAMESPACES + "><channel><wp:wxr_version>"
                        + huge + ".1</wp:wxr_version><item/></channel></rss>",
                WxrReadOptions.LENIENT.withMaxCharsPerValue(Integer.MAX_VALUE));
        assertClipped(newer.problems(), WxrProblem.Kind.VERSION_NEWER);
        // strict: the exception message
        assertThatThrownBy(() -> TestWxr.read(
                        "<?xml version=\"1.0\"?><rss version=\"2.0\" " + TestWxr.NAMESPACES
                                + "><channel><wp:wxr_version>x" + huge + "</wp:wxr_version><item/></channel></rss>",
                        WxrReadOptions.STRICT.withMaxCharsPerValue(Integer.MAX_VALUE)))
                .isInstanceOfSatisfying(
                        WxrException.class,
                        e -> assertThat(e.getMessage()).hasSizeLessThan(400).contains("\u2026"));
        // namespace URI of an accepted foreign wp prefix
        String uri = "http://example.com/" + "u".repeat(100_000);
        TestWxr.Result ns = TestWxr.read(
                "<rss version=\"2.0\" xmlns:wp=\"" + uri + "\"><channel><wp:wxr_version>1.2</wp:wxr_version>"
                        + "<item/></channel></rss>",
                WxrReadOptions.LENIENT);
        assertClipped(ns.problems(), WxrProblem.Kind.UNKNOWN_NAMESPACE);
        // the root element's name
        String name = "r".repeat(100_000);
        TestWxr.Result root = TestWxr.read("<" + name + "/>", WxrReadOptions.LENIENT);
        assertClipped(root.problems(), WxrProblem.Kind.NOT_WXR);
        // a declared encoding (entity names are too short to need it: longer than the filter window they are bare
        // ampersands)
        TestWxr.Result encoding = TestWxr.read(
                "<?xml version=\"1.0\" encoding=\"" + "e".repeat(900) + "\"?>" + item("&" + "n".repeat(500) + ";"),
                WxrReadOptions.LENIENT);
        assertClipped(encoding.problems(), WxrProblem.Kind.UNSUPPORTED_ENCODING);
        // short values are echoed whole
        assertThat(Texts.clip("1.x")).isEqualTo("1.x");
        assertThat(Texts.clip("a".repeat(100))).hasSize(100);
        assertThat(Texts.clip("a".repeat(101))).isEqualTo("a".repeat(100) + "\u2026");
        // no lone surrogate is left at the cut
        assertThat(Texts.clip("a".repeat(99) + "\uD83D\uDE00tail")).isEqualTo("a".repeat(99) + "\u2026");
    }

    private static void assertClipped(List<WxrProblem> problems, WxrProblem.Kind kind) {
        List<WxrProblem> of = problems.stream().filter(p -> p.kind() == kind).toList();
        assertThat(of).as(kind.toString()).isNotEmpty();
        assertThat(of).allSatisfy(p -> assertThat(p.detail()).hasSizeLessThan(300));
    }

    @Test
    void theProblemListIsBoundedAndTheCountIsNot() {
        // ProblemLog stores 1000 problems plus one TOO_MANY_PROBLEMS marker; further ones are only counted
        // (also asserted by TruncationTest.problemsAreCappedAndCounted).
        ProblemLog log = new ProblemLog(true);
        for (int i = 0; i < 100_000; i++) {
            log.note(WxrProblem.Kind.BARE_AMPERSAND, 1, i, "x");
        }
        assertThat(log.problems()).hasSize(ProblemLog.MAX_STORED + 1);
        assertThat(log.count()).isEqualTo(100_000);
    }

    private static String declarations(int count) {
        StringBuilder b = new StringBuilder(count * 12);
        for (int i = 0; i < count; i++) {
            b.append(" xmlns:p").append(i).append("=\"u\"");
        }
        return b.toString();
    }

    @Test
    void manyNamespaceDeclarationsInOneTagAreCapped() {
        for (int count : new int[] {100_000, 200_000, 400_000}) {
            String decls = declarations(count);
            // on the root: the document reads as usual
            String root = "<?xml version=\"1.0\"?><rss version=\"2.0\" " + TestWxr.NAMESPACES + decls
                    + "><channel><wp:wxr_version>1.2</wp:wxr_version><item><title>T</title></item></channel></rss>";
            TestWxr.Result r = assertTimeoutPreemptively(LIMIT, () -> TestWxr.read(root, WxrReadOptions.LENIENT));
            assertThat(r.items()).hasSize(1);
            assertThat(r.items().get(0).title()).isEqualTo("T");
            assertThat(r.kinds()).containsExactly(WxrProblem.Kind.VALUE_TOO_LARGE);
            // on an item
            String onItem = TestWxr.doc("<item" + decls + "><title>T</title></item><item><title>U</title></item>");
            TestWxr.Result s = assertTimeoutPreemptively(LIMIT, () -> TestWxr.read(onItem, WxrReadOptions.LENIENT));
            assertThat(s.items()).extracting(WxrItem::title).containsExactly("T", "U");
            assertThat(s.kinds()).containsExactly(WxrProblem.Kind.VALUE_TOO_LARGE);
            // strict refuses
            assertTimeoutPreemptively(
                    LIMIT,
                    () -> assertThatThrownBy(() -> TestWxr.read(onItem, WxrReadOptions.STRICT))
                            .isInstanceOfSatisfying(
                                    WxrException.class,
                                    e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.VALUE_TOO_LARGE)));
        }
    }

    @Test
    void tagsAtTheAttributeCapAreUntouched() {
        // the element name does not count; exactly MAX_ATTRIBUTES attributes pass, one more is dropped
        String ok = TestWxr.doc("<item" + declarations(RepairingReader.MAX_ATTRIBUTES) + "><title>T</title></item>");
        assertThat(TestWxr.read(ok, WxrReadOptions.STRICT).items()).hasSize(1);
        StringBuilder plain = new StringBuilder("<item");
        for (int i = 0; i <= RepairingReader.MAX_ATTRIBUTES; i++) {
            plain.append(" a").append(i).append("='v'");
        }
        TestWxr.Result r = TestWxr.read(TestWxr.doc(plain + "><title>T</title></item>"), WxrReadOptions.LENIENT);
        assertThat(r.problems()).hasSize(1);
        assertThat(r.items().get(0).title()).isEqualTo("T");
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.VALUE_TOO_LARGE);
    }
}
