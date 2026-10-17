package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import org.junit.jupiter.api.Test;

class XmlInputTest {

    private static String decode(byte[] bytes, WxrReadOptions options, ProblemLog log) throws IOException {
        try (Reader reader = XmlInput.decode(new ByteArrayInputStream(bytes), options, log)) {
            StringBuilder out = new StringBuilder();
            char[] buffer = new char[7]; // small on purpose: exercises refills across multi-byte sequences
            int n;
            while ((n = reader.read(buffer, 0, buffer.length)) >= 0) {
                out.append(buffer, 0, n);
            }
            return out.toString();
        }
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void utf8IsTheDefault() throws IOException {
        ProblemLog log = new ProblemLog(true);
        assertThat(decode(utf8("<r>Café ✓ \uD83D\uDE00</r>"), WxrReadOptions.LENIENT, log))
                .isEqualTo("<r>Café ✓ \uD83D\uDE00</r>");
        assertThat(log.problems()).isEmpty();
    }

    @Test
    void utf8ByteOrderMarkIsSkipped() throws IOException {
        // R70: Windows editors add EF BB BF; harness case bom.xml starts with it.
        byte[] bytes = new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF, '<', 'r', '/', '>'};
        assertThat(decode(bytes, WxrReadOptions.STRICT, new ProblemLog(false))).isEqualTo("<r/>");
    }

    @Test
    void utf16ByteOrderMarksSelectUtf16() throws IOException {
        byte[] le = "\uFEFF<r/>".getBytes(StandardCharsets.UTF_16LE);
        assertThat(decode(le, WxrReadOptions.STRICT, new ProblemLog(false))).isEqualTo("<r/>");
        byte[] be = "\uFEFF<r/>".getBytes(StandardCharsets.UTF_16BE);
        assertThat(decode(be, WxrReadOptions.STRICT, new ProblemLog(false))).isEqualTo("<r/>");
    }

    @Test
    void declaredEncodingIsHonoured() throws IOException {
        // R4/R71: old sites declare ISO-8859-1 and mean it.
        byte[] head = utf8("<?xml version=\"1.0\" encoding='ISO-8859-1' ?>\n<r>Caf");
        byte[] bytes = new byte[head.length + 5];
        System.arraycopy(head, 0, bytes, 0, head.length);
        bytes[head.length] = (byte) 0xE9;
        System.arraycopy(utf8("</r>"), 0, bytes, head.length + 1, 4);
        assertThat(decode(bytes, WxrReadOptions.STRICT, new ProblemLog(false))).endsWith("<r>Café</r>");
    }

    @Test
    void invalidUtf8BecomesReplacementCharacterInLenientMode() throws IOException {
        // Harness case invalid-utf8.xml: a Latin-1 byte E9 in a file declared UTF-8.
        byte[] bytes = {'<', 'r', '>', '\n', 'C', 'a', 'f', (byte) 0xE9, '<', '/', 'r', '>'};
        ProblemLog log = new ProblemLog(true);
        assertThat(decode(bytes, WxrReadOptions.LENIENT, log)).isEqualTo("<r>\nCaf\uFFFD</r>");
        assertThat(log.problems()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(WxrProblem.Kind.INVALID_ENCODING);
            assertThat(p.line()).isEqualTo(2);
            assertThat(p.column()).isEqualTo(4);
        });
    }

    @Test
    void invalidUtf8IsRefusedInStrictModeAfterTheCharsBeforeIt() throws IOException {
        byte[] bytes = {'<', 'r', '>', (byte) 0xE9, '<', '/', 'r', '>'};
        ProblemLog log = new ProblemLog(false);
        try (Reader reader = XmlInput.decode(new ByteArrayInputStream(bytes), WxrReadOptions.STRICT, log)) {
            char[] buffer = new char[16];
            assertThat(new String(buffer, 0, reader.read(buffer, 0, buffer.length)))
                    .isEqualTo("<r>");
            assertThatThrownBy(() -> reader.read(buffer, 0, buffer.length))
                    .isInstanceOfSatisfying(XmlInput.Refusal.class, e -> {
                        assertThat(e.refusal().kind()).isEqualTo(WxrProblem.Kind.INVALID_ENCODING);
                        assertThat(e.refusal().column()).isEqualTo(4);
                    });
            assertThatThrownBy(() -> reader.read(buffer, 0, buffer.length)).isInstanceOf(XmlInput.Refusal.class);
        }
        assertThatThrownBy(() -> decode(new byte[] {(byte) 0xE9, 'a'}, WxrReadOptions.STRICT, log))
                .isInstanceOf(XmlInput.Refusal.class);
    }

    @Test
    void unknownEncodingFallsBackToUtf8InLenientModeOnly() throws IOException {
        byte[] bytes = utf8("<?xml version=\"1.0\" encoding=\"x-nonexistent-42\"?><r/>");
        ProblemLog log = new ProblemLog(true);
        assertThat(decode(bytes, WxrReadOptions.LENIENT, log)).endsWith("<r/>");
        assertThat(log.problems()).extracting(WxrProblem::kind).containsExactly(WxrProblem.Kind.UNSUPPORTED_ENCODING);
        assertThatThrownBy(() -> decode(bytes, WxrReadOptions.STRICT, new ProblemLog(false)))
                .isInstanceOf(WxrException.class);
    }

    @Test
    void gzipIsDetectedUnlessDisabled() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            out.write(utf8("<r>zipped</r>"));
        }
        assertThat(decode(gz.toByteArray(), WxrReadOptions.LENIENT, new ProblemLog(true)))
                .isEqualTo("<r>zipped</r>");
        ProblemLog log = new ProblemLog(true);
        assertThat(decode(gz.toByteArray(), WxrReadOptions.LENIENT.withGzipAutoDetect(false), log))
                .doesNotContain("zipped");
        assertThat(log.problems()).isNotEmpty();
    }

    @Test
    void problemsAreCappedAndCounted() {
        ProblemLog log = new ProblemLog(true);
        for (int i = 0; i < 5_000; i++) {
            log.note(WxrProblem.Kind.BARE_AMPERSAND, 1, i + 1, "x");
        }
        assertThat(log.count()).isEqualTo(5_000);
        assertThat(log.problems()).hasSize(ProblemLog.MAX_STORED + 1);
        assertThat(log.problems().get(ProblemLog.MAX_STORED).kind()).isEqualTo(WxrProblem.Kind.TOO_MANY_PROBLEMS);
    }

    @Test
    void optionsValidateAndCopy() {
        WxrReadOptions o = WxrReadOptions.LENIENT.withMaxCharsPerValue(10).withMaxItems(3);
        assertThat(o.maxCharsPerValue()).isEqualTo(10);
        assertThat(o.maxItems()).isEqualTo(3);
        assertThat(o.lenient()).isTrue();
        assertThat(WxrReadOptions.LENIENT.maxCharsPerValue()).isEqualTo(WxrReadOptions.DEFAULT_MAX_CHARS_PER_VALUE);
        assertThat(WxrReadOptions.STRICT.acceptAnyWpNamespace()).isFalse();
        assertThat(WxrReadOptions.LENIENT.acceptAnyWpNamespace()).isTrue();
        assertThatThrownBy(() -> WxrReadOptions.LENIENT.withMaxCharsPerValue(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WxrReadOptions.LENIENT.withMaxItems(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void utf16WithoutByteOrderMarkIsRecognisedByItsFirstCharacters() throws IOException {
        assertThat(decode(
                        "<?xml version=\"1.0\"?><r>\u00e9</r>".getBytes(StandardCharsets.UTF_16LE),
                        WxrReadOptions.STRICT,
                        new ProblemLog(false)))
                .isEqualTo("<?xml version=\"1.0\"?><r>\u00e9</r>");
        assertThat(decode(
                        "<?xml version=\"1.0\"?><r>\u00e9</r>".getBytes(StandardCharsets.UTF_16BE),
                        WxrReadOptions.STRICT,
                        new ProblemLog(false)))
                .isEqualTo("<?xml version=\"1.0\"?><r>\u00e9</r>");
    }

    @Test
    void declarationScannerAcceptsBothQuotesAndSpacesAndRejectsMismatches() {
        assertThat(XmlInput.declaredEncoding("<?xml version=\"1.0\" encoding = 'UTF-8' "))
                .isEqualTo("UTF-8");
        assertThat(XmlInput.declaredEncoding("<?xml encoding=\"ISO-8859-1\"")).isEqualTo("ISO-8859-1");
        assertThat(XmlInput.declaredEncoding("<?xml encoding=\"UTF-8'")).isNull();
        assertThat(XmlInput.declaredEncoding("<?xml encoding=UTF-8")).isNull();
        assertThat(XmlInput.declaredEncoding("<?xml encoding=\"\"")).isNull();
        assertThat(XmlInput.declaredEncoding("<?xml encoding=\"a b\"")).isEqualTo("a b");
        assertThat(XmlInput.declaredEncoding("<?xml version=\"1.0\"")).isNull();
    }

    @Test
    void gzipIsDecompressedAsAStreamAndCorruptDataFailsAsIoException() throws IOException {
        ByteArrayOutputStream gz = new ByteArrayOutputStream();
        try (GZIPOutputStream out = new GZIPOutputStream(gz)) {
            byte[] block = utf8("<p>" + "a".repeat(1000) + "</p>");
            for (int i = 0; i < 2_000; i++) {
                out.write(block);
            }
        }
        try (Reader reader = XmlInput.decode(
                new ByteArrayInputStream(gz.toByteArray()), WxrReadOptions.LENIENT, new ProblemLog(true))) {
            char[] buffer = new char[10];
            assertThat(reader.read(buffer, 0, 10)).isEqualTo(10);
            assertThat(new String(buffer)).isEqualTo("<p>aaaaaaa");
        }
        byte[] cut = Arrays.copyOf(gz.toByteArray(), 40);
        assertThatThrownBy(() -> decode(cut, WxrReadOptions.LENIENT, new ProblemLog(true)))
                .isInstanceOf(IOException.class);
    }

    @Test
    void utf32ByteOrderMarksAndUnmarkedFormsAreRecognised() throws IOException {
        String text = "<?xml version=\"1.0\"?><r>\u00e9\uD83D\uDE00</r>";
        byte[][] inputs = {
            ("\uFEFF" + text).getBytes(java.nio.charset.Charset.forName("UTF-32LE")),
            ("\uFEFF" + text).getBytes(java.nio.charset.Charset.forName("UTF-32BE")),
            text.getBytes(java.nio.charset.Charset.forName("UTF-32LE")),
            text.getBytes(java.nio.charset.Charset.forName("UTF-32BE"))
        };
        for (byte[] bytes : inputs) {
            String out = decode(bytes, WxrReadOptions.STRICT, new ProblemLog(false));
            assertThat(out).isEqualTo(text).doesNotContain("\u0000");
        }
    }

    @Test
    void wideEncodingsDeclaredOverAsciiBytesAreNotHonoured() throws IOException {
        for (String name : new String[] {"UTF-16", "UTF-16LE", "UTF-32"}) {
            byte[] bytes = utf8("<?xml version=\"1.0\" encoding=\"" + name + "\"?><r>ok</r>");
            ProblemLog log = new ProblemLog(true);
            assertThat(decode(bytes, WxrReadOptions.LENIENT, log)).endsWith("<r>ok</r>");
            assertThat(log.problems()).singleElement().satisfies(p -> {
                assertThat(p.kind()).isEqualTo(WxrProblem.Kind.UNSUPPORTED_ENCODING);
                assertThat(p.detail()).contains("single-byte ASCII");
            });
            assertThatThrownBy(() -> decode(bytes, WxrReadOptions.STRICT, new ProblemLog(false)))
                    .isInstanceOfSatisfying(
                            WxrException.class,
                            e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.UNSUPPORTED_ENCODING));
        }
    }

    @Test
    void illegalEncodingNamesAndStylesheetInstructionsAreHandled() throws IOException {
        ProblemLog log = new ProblemLog(true);
        decode(utf8("<?xml version=\"1.0\" encoding=\"a b\"?><r/>"), WxrReadOptions.LENIENT, log);
        assertThat(log.problems()).extracting(WxrProblem::kind).containsExactly(WxrProblem.Kind.UNSUPPORTED_ENCODING);
        ProblemLog quiet = new ProblemLog(true);
        decode(utf8("<?xml-stylesheet encoding=\"x-nonexistent-42\"?><r/>"), WxrReadOptions.LENIENT, quiet);
        assertThat(quiet.problems()).isEmpty();
    }

    @Test
    void aDecompressorThatFailsDuringDetectionIsClosed() throws IOException {
        // a valid gzip header followed by data that is not deflate: the encoding sniff fails inside the inflater
        byte[] bytes = {0x1F, (byte) 0x8B, 8, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, (byte) 0xFF, 1, 2, 3, 4};
        boolean[] closed = {false};
        ByteArrayInputStream in = new ByteArrayInputStream(bytes) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        assertThatThrownBy(() -> XmlInput.decode(in, WxrReadOptions.LENIENT, new ProblemLog(true)))
                .isInstanceOf(IOException.class);
        assertThat(closed[0])
                .as("closing the decompressor ends its inflater and closes the stream")
                .isTrue();
    }

    @Test
    void theDeclaredEncodingIsFoundBehindLeadingWhitespace() throws IOException {
        byte[] latin1 = "\r\n \t<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><r>\u00e9</r>"
                .getBytes(StandardCharsets.ISO_8859_1);
        ProblemLog log = new ProblemLog(true);
        assertThat(decode(latin1, WxrReadOptions.LENIENT, log))
                .isEqualTo("\r\n \t<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><r>\u00e9</r>");
        assertThat(log.problems()).as("reported by the pre-filter, not here").isEmpty();
    }

    @Test
    void optionsAreEqualByValue() {
        WxrReadOptions a = WxrReadOptions.LENIENT.withMaxItems(3);
        WxrReadOptions b = WxrReadOptions.LENIENT.withMaxItems(3);
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(WxrReadOptions.LENIENT);
        assertThat(WxrReadOptions.LENIENT.withMaxItems(Long.MAX_VALUE)).isEqualTo(WxrReadOptions.LENIENT);
        assertThat(WxrReadOptions.STRICT).isNotEqualTo(WxrReadOptions.LENIENT);
        assertThat(WxrReadOptions.LENIENT.withGzipAutoDetect(false)).isNotEqualTo(WxrReadOptions.LENIENT);
        assertThat(WxrReadOptions.LENIENT.withAcceptAnyWpNamespace(false)).isNotEqualTo(WxrReadOptions.LENIENT);
        assertThat(WxrReadOptions.LENIENT.withMaxCharsPerValue(9)).isNotEqualTo(WxrReadOptions.LENIENT);
        assertThat(WxrReadOptions.LENIENT).isNotEqualTo(null).isNotEqualTo("LENIENT");
    }
}
