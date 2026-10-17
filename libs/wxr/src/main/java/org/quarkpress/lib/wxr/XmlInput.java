package org.quarkpress.lib.wxr;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Arrays;
import java.util.zip.GZIPInputStream;

/**
 * Turns the input bytes into characters: optional gzip, then a byte-order mark (UTF-8, UTF-16 or UTF-32), else
 * UTF-16 or UTF-32 recognised by its unmarked first characters, else the encoding named in the XML declaration, else
 * UTF-8. Invalid sequences go to {@link DecodingReader}.
 */
final class XmlInput {

    static final int DECLARATION_SNIFF = 1024;
    private static final int BUFFER = 65_536;

    private XmlInput() {}

    static Reader decode(InputStream in, WxrReadOptions options, ProblemLog log) throws IOException {
        BufferedInputStream bytes = new BufferedInputStream(in, BUFFER);
        if (options.gzipAutoDetect() && isGzip(bytes)) {
            bytes = new BufferedInputStream(new GZIPInputStream(bytes, BUFFER), BUFFER);
        }
        Charset charset = charset(bytes, log);
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        return new DecodingReader(bytes, decoder, log);
    }

    private static boolean isGzip(BufferedInputStream in) throws IOException {
        in.mark(2);
        int first = in.read();
        int second = in.read();
        in.reset();
        return first == 0x1F && second == 0x8B;
    }

    private static Charset charset(BufferedInputStream in, ProblemLog log) throws IOException {
        in.mark(4);
        int b0 = in.read();
        int b1 = in.read();
        int b2 = in.read();
        int b3 = in.read();
        in.reset();
        if (b0 == 0xEF && b1 == 0xBB && b2 == 0xBF) {
            in.skipNBytes(3);
            return StandardCharsets.UTF_8;
        }
        if (b0 == 0xFF && b1 == 0xFE && b2 == 0x00 && b3 == 0x00) {
            in.skipNBytes(4);
            return Charset.forName("UTF-32LE");
        }
        if (b0 == 0x00 && b1 == 0x00 && b2 == 0xFE && b3 == 0xFF) {
            in.skipNBytes(4);
            return Charset.forName("UTF-32BE");
        }
        if (b0 == 0xFE && b1 == 0xFF) {
            in.skipNBytes(2);
            return StandardCharsets.UTF_16BE;
        }
        if (b0 == 0xFF && b1 == 0xFE) {
            in.skipNBytes(2);
            return StandardCharsets.UTF_16LE;
        }
        if (b0 == 0x3C && b1 == 0x00 && b2 == 0x00 && b3 == 0x00) {
            return Charset.forName("UTF-32LE");
        }
        if (b0 == 0x00 && b1 == 0x00 && b2 == 0x00 && b3 == 0x3C) {
            return Charset.forName("UTF-32BE");
        }
        if (b0 == 0x3C && b1 == 0x00 && b2 == 0x3F && b3 == 0x00) {
            return StandardCharsets.UTF_16LE;
        }
        if (b0 == 0x00 && b1 == 0x3C && b2 == 0x00 && b3 == 0x3F) {
            return StandardCharsets.UTF_16BE;
        }
        in.mark(DECLARATION_SNIFF);
        byte[] head = in.readNBytes(DECLARATION_SNIFF);
        in.reset();
        String text = new String(head, StandardCharsets.ISO_8859_1);
        int end = text.indexOf("?>");
        if (!text.startsWith("<?xml") || end < 0 || text.length() < 6 || !isBlank(text.charAt(5))) {
            return StandardCharsets.UTF_8;
        }
        String name = declaredEncoding(text.substring(0, end));
        if (name == null) {
            return StandardCharsets.UTF_8;
        }
        try {
            Charset declared = Charset.forName(name);
            if (!Arrays.equals("<?xml".getBytes(declared), "<?xml".getBytes(StandardCharsets.US_ASCII))) {
                log.repair(
                        WxrProblem.Kind.UNSUPPORTED_ENCODING,
                        1,
                        1,
                        "declared " + name + " but the bytes are single-byte ASCII; read as UTF-8");
                return StandardCharsets.UTF_8;
            }
            return declared;
        } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
            log.repair(WxrProblem.Kind.UNSUPPORTED_ENCODING, 1, 1, "unknown encoding '" + name + "'; read as UTF-8");
            return StandardCharsets.UTF_8;
        }
    }

    /** The value of {@code encoding="..."} (either quote) in an XML declaration, or null. */
    static String declaredEncoding(String declaration) {
        int from = 0;
        while (true) {
            int at = declaration.indexOf("encoding", from);
            if (at < 0) {
                return null;
            }
            from = at + 1;
            int i = skipBlanks(declaration, at + "encoding".length());
            if (i >= declaration.length() || declaration.charAt(i) != '=') {
                continue;
            }
            i = skipBlanks(declaration, i + 1);
            if (i >= declaration.length()) {
                return null;
            }
            char quote = declaration.charAt(i);
            if (quote != '"' && quote != '\'') {
                continue;
            }
            int close = declaration.indexOf(quote, i + 1);
            if (close > i + 1) {
                return declaration.substring(i + 1, close);
            }
        }
    }

    private static int skipBlanks(String s, int i) {
        while (i < s.length() && isBlank(s.charAt(i))) {
            i++;
        }
        return i;
    }

    private static boolean isBlank(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
