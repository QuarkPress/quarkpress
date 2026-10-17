package org.quarkpress.lib.wxr;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

/** A WXR stream with one huge value, produced on the fly so tests can read hundreds of megabytes without storing them. */
final class GeneratedWxr extends InputStream {

    private static final String HEAD =
            TestWxr.doc("@@").substring(0, TestWxr.doc("@@").indexOf("@@"));
    private static final String TAIL = "</channel>\n</rss>\n";

    private final byte[] mib = new byte[1 << 20];
    private final long chars;
    private final boolean cdata;
    private long written;
    private int state;
    private byte[] chunk = new byte[0];
    private int at;

    private GeneratedWxr(long chars, boolean cdata) {
        this.chars = chars;
        this.cdata = cdata;
        Arrays.fill(mib, (byte) 'a');
    }

    /** One item whose content holds {@code chars} chars, as CDATA or as plain text. */
    static GeneratedWxr oneHugeValue(long chars, boolean cdata) {
        return new GeneratedWxr(chars, cdata);
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private byte[] nextChunk() {
        switch (state) {
            case 0:
                state = 1;
                return utf8(HEAD + "<item><content:encoded>" + (cdata ? "<![CDATA[" : ""));
            case 1:
                if (written < chars) {
                    int n = (int) Math.min(mib.length, chars - written);
                    written += n;
                    return n == mib.length ? mib : Arrays.copyOf(mib, n);
                }
                state = 2;
                return utf8((cdata ? "]]>" : "") + "</content:encoded></item>" + TAIL);
            default:
                return null;
        }
    }

    @Override
    public int read() {
        byte[] one = new byte[1];
        return read(one, 0, 1) < 0 ? -1 : one[0] & 0xFF;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        while (at == chunk.length) {
            byte[] next = nextChunk();
            if (next == null) {
                return -1;
            }
            chunk = next;
            at = 0;
        }
        int n = Math.min(length, chunk.length - at);
        System.arraycopy(chunk, at, buffer, offset, n);
        at += n;
        return n;
    }
}
