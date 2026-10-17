package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.StringReader;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.CRC32;
import java.util.zip.ZipException;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** The reader against hostile or damaged input: entities, parser limits, broken compressed streams. */
class WxrReaderHardeningTest {

    private static final String MARKER = "SECRET-FILE-CONTENT";

    // ---- XXE ----

    private static String xxe(Path secret) {
        return "<?xml version=\"1.0\"?>\n<!DOCTYPE rss [<!ENTITY xxe SYSTEM \"" + secret.toUri() + "\">"
                + "<!ENTITY lol \"lol\"><!ENTITY lol2 \"&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;\">]>\n"
                + TestWxr.doc("<item><title>&xxe;</title><description>&lol2;</description></item>")
                        .substring("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n".length());
    }

    @Test
    void strictRefusesADoctype(@TempDir Path dir) throws IOException {
        Path secret = Files.writeString(dir.resolve("secret.txt"), MARKER);
        assertThatThrownBy(() -> TestWxr.read(xxe(secret), WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.DOCTYPE));
    }

    @Test
    void lenientSkipsTheDoctypeAndExpandsNoEntity(@TempDir Path dir) throws IOException {
        Path secret = Files.writeString(dir.resolve("secret.txt"), MARKER);
        TestWxr.Result r = TestWxr.read(xxe(secret), WxrReadOptions.LENIENT);
        WxrItem item = r.items().get(0);
        assertThat(item.title()).isEqualTo("&xxe;");
        assertThat(item.description()).isEqualTo("&lol2;");
        assertThat(r.kinds())
                .containsExactly(
                        WxrProblem.Kind.DOCTYPE, WxrProblem.Kind.UNDEFINED_ENTITY, WxrProblem.Kind.UNDEFINED_ENTITY);
    }

    @Test
    void theParserItselfNeverReadsDtdsOrExpandsDeclaredEntities(@TempDir Path dir) throws Exception {
        // Without the pre-filter in front: the declarations are not read, so a reference to them is an error.
        Path secret = Files.writeString(dir.resolve("secret.txt"), MARKER);
        String[] docs = {
            "<!DOCTYPE r [<!ENTITY x SYSTEM \"" + secret.toUri() + "\">]><r>&x;</r>",
            "<!DOCTYPE r [<!ENTITY x \"inner\">]><r>&x;</r>",
        };
        for (String doc : docs) {
            XMLStreamReader xml = StaxFactory.create().createXMLStreamReader(new StringReader(doc));
            StringBuilder text = new StringBuilder();
            assertThatThrownBy(() -> {
                        while (xml.hasNext()) {
                            if (xml.next() == XMLStreamReader.CHARACTERS) {
                                text.append(xml.getText());
                            }
                        }
                    })
                    .isInstanceOf(XMLStreamException.class)
                    .hasMessageContaining("\"x\"");
            assertThat(text.toString()).doesNotContain(MARKER).doesNotContain("inner");
        }
        // An external DTD is not read: the entity it declares stays undeclared.
        Path dtd = Files.writeString(dir.resolve("e.dtd"), "<!ENTITY e \"EXPANDED\">");
        XMLStreamReader withDtd = StaxFactory.create()
                .createXMLStreamReader(new StringReader("<!DOCTYPE r SYSTEM \"" + dtd.toUri() + "\"><r>&e;</r>"));
        StringBuilder seen = new StringBuilder();
        assertThatThrownBy(() -> {
                    while (withDtd.hasNext()) {
                        if (withDtd.next() == XMLStreamReader.CHARACTERS) {
                            seen.append(withDtd.getText());
                        }
                    }
                })
                .isInstanceOf(XMLStreamException.class)
                .hasMessageContaining("\"e\"");
        assertThat(seen.toString()).doesNotContain("EXPANDED");
    }

    @Test
    void noDtdOrEntityIsEverFetched() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            byte[] body = "<!ENTITY e \"FETCHED\">".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        try {
            String base = "http://" + server.getAddress().getHostString() + ":"
                    + server.getAddress().getPort();
            String doctype = "<!DOCTYPE rss SYSTEM \"" + base + "/a.dtd\" [<!ENTITY % p SYSTEM \"" + base
                    + "/p.dtd\"> %p; <!ENTITY x SYSTEM \"" + base + "/x.txt\">]>\n";
            // the parser alone
            XMLStreamReader xml =
                    StaxFactory.create().createXMLStreamReader(new StringReader(doctype + "<rss><r>z</r></rss>"));
            while (xml.hasNext()) {
                xml.next();
            }
            // the reader, strict and lenient
            String doc = TestWxr.doc("<item><title>&x;&e;</title></item>").replace("<rss ", doctype + "<rss ");
            assertThatThrownBy(() -> TestWxr.read(doc, WxrReadOptions.STRICT)).isInstanceOf(WxrException.class);
            TestWxr.Result r = TestWxr.read(doc, WxrReadOptions.LENIENT);
            assertThat(r.items().get(0).title()).isEqualTo("&x;&e;");
            assertThat(requests).hasValue(0);
        } finally {
            server.stop(0);
        }
    }

    // ---- JDK parser limits ----

    @Test
    void moreThan100kReferencesInOneValue() {
        // The JDK caps the predefined references of a document at 100,000 by default; real exports exceed that.
        String xml = TestWxr.doc("<item><content:encoded>" + "&lt;".repeat(150_000) + "</content:encoded></item>");
        assertThat(TestWxr.read(xml, WxrReadOptions.STRICT).items().get(0).content())
                .isEqualTo("<".repeat(150_000));
    }

    @Test
    void moreThan100kReferencesAcrossADocument() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            body.append("<item><title>").append("&amp;".repeat(40_000)).append("</title></item>");
        }
        TestWxr.Result r = TestWxr.read(TestWxr.doc(body.toString()), WxrReadOptions.STRICT);
        assertThat(r.items()).hasSize(4).allSatisfy(i -> assertThat(i.title()).isEqualTo("&".repeat(40_000)));
    }

    @Test
    void repairedLessThanFloodsRead() {
        // Each bare '<' becomes a reference in the pre-filter: 120,000 of them in one value.
        String text = "a < b ".repeat(120_000);
        TestWxr.Result r = TestWxr.read(
                TestWxr.doc("<item><content:encoded>" + text + "</content:encoded></item>"), WxrReadOptions.LENIENT);
        assertThat(r.items().get(0).content()).isEqualTo(text);
        assertThat(r.problems()).hasSize(ProblemLog.MAX_STORED + 1);
        assertThat(r.problems().get(0).kind()).isEqualTo(WxrProblem.Kind.BARE_LESS_THAN);
    }

    @Test
    void namesLongerThanTheJdkDefaultRead() {
        // The JDK refuses names over 1000 chars by default; names are held to the per-value limit instead.
        String name = "n".repeat(5000);
        String xml = TestWxr.doc("<item><" + name + ">v</" + name + "><title>T</title></item>");
        WxrItem item = TestWxr.read(xml, WxrReadOptions.STRICT).items().get(0);
        assertThat(item.rawFields()).containsEntry(name, "v");
        assertThat(item.title()).isEqualTo("T");
    }

    @Test
    void namesOverTheValueLimitAreRefusedInStrictMode() {
        String name = "n".repeat(5000);
        String xml = TestWxr.doc("<item><" + name + ">v</" + name + "></item>");
        assertThatThrownBy(() -> TestWxr.read(xml, WxrReadOptions.STRICT.withMaxCharsPerValue(1500)))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.VALUE_TOO_LARGE));
    }

    // ---- damaged streams ----

    private static final int ITEMS = 2000;

    private static byte[] manyItems() {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < ITEMS; i++) {
            body.append("<item><title>item-")
                    .append(i)
                    .append("</title><content:encoded><![CDATA[")
                    .append("body ".repeat(40))
                    .append("]]></content:encoded></item>\n");
        }
        return TestWxr.doc(body.toString()).getBytes(StandardCharsets.UTF_8);
    }

    /** gzip of {@code data} in stored (uncompressed) deflate blocks, so cuts and damage land where intended. */
    private static byte[] storedGzip(byte[] data, int blockSize, boolean last) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {0x1F, (byte) 0x8B, 8, 0, 0, 0, 0, 0, 0, (byte) 0xFF});
        for (int at = 0; at < data.length; at += blockSize) {
            int n = Math.min(blockSize, data.length - at);
            boolean fin = last && at + n == data.length;
            out.write(fin ? 1 : 0);
            out.write(n & 0xFF);
            out.write(n >>> 8);
            out.write(~n & 0xFF);
            out.write((~n >>> 8) & 0xFF);
            out.write(data, at, n);
        }
        if (last) {
            CRC32 crc = new CRC32();
            crc.update(data);
            writeInt(out, (int) crc.getValue());
            writeInt(out, data.length);
        }
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int v) {
        for (int i = 0; i < 4; i++) {
            out.write(v >>> (8 * i));
        }
    }

    /** The titles are item-0, item-1, ... with none missing, and fewer than all of them. */
    private static void assertCompletePrefix(List<WxrItem> items) {
        assertThat(items).isNotEmpty().hasSizeLessThan(ITEMS);
        for (int i = 0; i < items.size(); i++) {
            assertThat(items.get(i).title()).isEqualTo("item-" + i);
            assertThat(items.get(i).content()).isEqualTo("body ".repeat(40));
        }
    }

    @Test
    void theStoredGzipHelperRoundTrips() {
        byte[] data = manyItems();
        assertThat(TestWxr.read(storedGzip(data, 8192, true), WxrReadOptions.STRICT)
                        .items())
                .hasSize(ITEMS);
    }

    @Test
    void truncatedGzipIsTruncated() {
        byte[] full = storedGzip(manyItems(), 8192, true);
        byte[] cut = Arrays.copyOf(full, full.length / 2);
        assertThatThrownBy(() -> TestWxr.read(cut, WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(WxrException.class, e -> {
                    assertThat(e.kind()).isEqualTo(WxrProblem.Kind.TRUNCATED);
                    assertThat(e.getCause()).isInstanceOf(EOFException.class);
                });
        TestWxr.Result r = TestWxr.read(cut, WxrReadOptions.LENIENT);
        assertCompletePrefix(r.items());
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.TRUNCATED);
    }

    @Test
    void corruptGzipIsMalformed() {
        // java.util.zip drops the output of the inflate call that meets the damage (up to one 64 KiB buffer), so the
        // input is large enough for earlier buffers to hold complete items.
        byte[] data = manyItems();
        byte[] head = storedGzip(Arrays.copyOf(data, data.length / 2), 8192, false);
        byte[] bad = Arrays.copyOf(head, head.length + 5);
        // a stored block whose length check fails
        bad[head.length + 1] = 0x10;
        assertThatThrownBy(() -> TestWxr.read(bad, WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(WxrException.class, e -> {
                    assertThat(e.kind()).isEqualTo(WxrProblem.Kind.MALFORMED);
                    assertThat(e.getCause()).isInstanceOf(ZipException.class);
                });
        TestWxr.Result r = TestWxr.read(bad, WxrReadOptions.LENIENT);
        assertCompletePrefix(r.items());
        // What the damage left at the end is read as the end of the input, which can need a repair of its own.
        assertThat(r.kinds()).last().isEqualTo(WxrProblem.Kind.MALFORMED);
    }

    @Test
    void aDamagedGzipTrailerAfterACompleteDocumentIsReported() {
        byte[] gz = storedGzip(TestWxr.resource("example-b.xml"), 8192, true);
        gz[gz.length - 8] ^= 1; // CRC
        assertThatThrownBy(() -> TestWxr.read(gz, WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(WxrException.class, e -> {
                    assertThat(e.kind()).isEqualTo(WxrProblem.Kind.MALFORMED);
                    assertThat(e.getCause()).isInstanceOf(ZipException.class);
                });
        TestWxr.Result r = TestWxr.read(gz, WxrReadOptions.LENIENT);
        assertThat(r.items())
                .singleElement()
                .satisfies(i -> assertThat(i.title()).isEqualTo("Minimal"));
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.MALFORMED);
    }

    @Test
    void aBrokenGzipHeaderReadsNothing() {
        byte[] badMethod = {0x1F, (byte) 0x8B, 7, 0, 0, 0, 0, 0, 0, (byte) 0xFF, 1, 2, 3};
        assertThatThrownBy(() -> TestWxr.read(badMethod, WxrReadOptions.STRICT))
                .isInstanceOfSatisfying(
                        WxrException.class, e -> assertThat(e.kind()).isEqualTo(WxrProblem.Kind.MALFORMED));
        TestWxr.Result r = TestWxr.read(badMethod, WxrReadOptions.LENIENT);
        assertThat(r.entries()).isEmpty();
        assertThat(r.kinds()).containsExactly(WxrProblem.Kind.MALFORMED);

        byte[] shortHeader = {0x1F, (byte) 0x8B, 8, 0};
        TestWxr.Result s = TestWxr.read(shortHeader, WxrReadOptions.LENIENT);
        assertThat(s.entries()).isEmpty();
        assertThat(s.kinds()).containsExactly(WxrProblem.Kind.TRUNCATED);
    }

    /** A stream of the first {@code n} bytes of {@code data} that then fails; it claims more is available. */
    private static InputStream failingAfter(byte[] data, int n) {
        return new InputStream() {
            private final InputStream in = new ByteArrayInputStream(data, 0, n);

            @Override
            public int read() throws IOException {
                int b = in.read();
                if (b < 0) {
                    throw new IOException("connection reset");
                }
                return b;
            }

            @Override
            public int read(byte[] b, int off, int len) throws IOException {
                int r = in.read(b, off, Math.min(len, 1000));
                if (r < 0) {
                    throw new IOException("connection reset");
                }
                return r;
            }

            @Override
            public int available() {
                return 1 << 20;
            }
        };
    }

    /** Reads until the stream fails; the failure must be an UncheckedIOException carrying the stream's exception. */
    private static List<WxrItem> readUntilIoError(InputStream in, WxrReadOptions options) {
        List<WxrItem> items = new ArrayList<>();
        try (WxrReader reader = WxrReader.open(in, options)) {
            assertThatThrownBy(() -> {
                        while (reader.hasNext()) {
                            items.add((WxrItem) reader.next());
                        }
                    })
                    .isInstanceOf(UncheckedIOException.class)
                    .cause()
                    .isInstanceOf(IOException.class)
                    .hasMessage("connection reset");
            assertThat(reader.problems()).isEmpty();
        }
        return items;
    }

    /** How many items end within the bytes a stream delivers before failing (single-byte reads lose nothing). */
    private static int completeItems(InputStream decodedPrefix) {
        ByteArrayOutputStream got = new ByteArrayOutputStream();
        try {
            for (int b; (b = decodedPrefix.read()) >= 0; ) {
                got.write(b);
            }
        } catch (IOException expected) {
            // the end of what was delivered
        }
        String text = got.toString(StandardCharsets.UTF_8);
        int count = 0;
        for (int at = text.indexOf("</item>"); at >= 0; at = text.indexOf("</item>", at + 1)) {
            count++;
        }
        return count;
    }

    @Test
    void anIoErrorOfTheStreamIsAnIoErrorInBothModes() {
        // A failing disk or connection is not a damaged file: no problem is recorded, the I/O error is thrown.
        byte[] data = manyItems();
        int expected = completeItems(failingAfter(data, data.length / 2));
        for (WxrReadOptions options : new WxrReadOptions[] {WxrReadOptions.STRICT, WxrReadOptions.LENIENT}) {
            List<WxrItem> items = readUntilIoError(failingAfter(data, data.length / 2), options);
            assertCompletePrefix(items);
            assertThat(items).hasSize(expected);
        }
    }

    @Test
    void anIoErrorUnderGzipDeliversEveryCompleteEntryFirst() throws IOException {
        // The stream claims more is available, which must not make a buffer read on and lose what it holds.
        byte[] gz = storedGzip(manyItems(), 8192, true);
        int cut = gz.length * 3 / 4;
        int expected = completeItems(new java.util.zip.GZIPInputStream(failingAfter(gz, cut)));
        for (WxrReadOptions options : new WxrReadOptions[] {WxrReadOptions.STRICT, WxrReadOptions.LENIENT}) {
            List<WxrItem> items = readUntilIoError(failingAfter(gz, cut), options);
            assertCompletePrefix(items);
            assertThat(items).hasSize(expected);
        }
    }

    @Test
    void anIoErrorInTheGzipHeaderIsAnIoError() {
        byte[] gz = storedGzip(manyItems(), 8192, true);
        assertThat(readUntilIoError(failingAfter(gz, 5), WxrReadOptions.LENIENT))
                .isEmpty();
    }

    @Test
    void theCutIsWhereTheRemainderWasReadAsTheEnd() throws IOException {
        // "<r>ab<" then a read error: the '<' at column 6 is processed as if the input ended there.
        Reader failing = new Reader() {
            private boolean sent;

            @Override
            public int read(char[] buffer, int offset, int length) throws IOException {
                if (sent) {
                    throw new IOException("gone");
                }
                sent = true;
                "<r>ab<".getChars(0, 6, buffer, offset);
                return 6;
            }

            @Override
            public void close() {}
        };
        RepairingReader repairing = new RepairingReader(failing, new ProblemLog(true));
        char[] buffer = new char[100];
        StringBuilder out = new StringBuilder();
        assertThatThrownBy(() -> {
                    for (int n; (n = repairing.read(buffer, 0, buffer.length)) >= 0; ) {
                        out.append(buffer, 0, n);
                    }
                })
                .isInstanceOf(IOException.class)
                .hasMessage("gone");
        assertThat(out.toString()).isEqualTo("<r>ab&lt;");
        assertThat(repairing.beforeCut(1, 5)).isTrue();
        assertThat(repairing.beforeCut(1, 6)).isFalse();
        assertThat(repairing.beforeCut(2, 1)).isFalse();
        assertThat(repairing.atEof()).isFalse();
    }

    @Test
    void closeEndsTheDecompressorAndClosesTheStream() {
        boolean[] closed = new boolean[1];
        InputStream in = new ByteArrayInputStream(storedGzip(manyItems(), 8192, true)) {
            @Override
            public void close() {
                closed[0] = true;
            }
        };
        WxrReader reader = WxrReader.open(in, WxrReadOptions.STRICT);
        assertThat(reader.next()).isInstanceOf(WxrItem.class);
        reader.close();
        assertThat(closed[0]).isTrue();
        assertThat(reader.hasNext()).isFalse();
    }

    // ---- item terms ----

    @Test
    void itemTermAttributesAreKeptAsWritten() {
        // Absent stays null, empty stays empty; the importer's own taxonomy for an empty domain is "" too.
        String xml = TestWxr.doc("<item><category nicename=\"a\">A</category>"
                + "<category domain=\"\" nicename=\"b\">B</category><category>C</category></item>");
        List<WxrItemTerm> terms =
                TestWxr.read(xml, WxrReadOptions.STRICT).items().get(0).terms();
        assertThat(terms)
                .containsExactly(
                        new WxrItemTerm(null, "a", "A"),
                        new WxrItemTerm("", "b", "B"),
                        new WxrItemTerm(null, null, "C"));
        assertThat(terms.get(1).taxonomy()).isEmpty();
    }
}
