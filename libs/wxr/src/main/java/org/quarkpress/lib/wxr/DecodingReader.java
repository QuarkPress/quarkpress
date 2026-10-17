package org.quarkpress.lib.wxr;

import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CoderResult;
import java.util.Objects;

/**
 * Decodes bytes with a reporting decoder: each malformed or unmappable sequence becomes one U+FFFD and an
 * INVALID_ENCODING repair at the line and column where it occurs. In strict mode the sequence is refused instead:
 * the chars decoded before it are returned first, and the next read throws an {@link XmlInput.Refusal} carrying the
 * {@link WxrException}, so that a reader reading ahead still gets everything before the bad bytes.
 */
final class DecodingReader extends Reader {

    private final InputStream in;
    private final CharsetDecoder decoder;
    private final ProblemLog log;
    private final ByteBuffer bytes = ByteBuffer.allocate(8192);
    private final CharBuffer chars = CharBuffer.allocate(8192);
    private boolean eof;
    private boolean flushed;
    private long line = 1;
    private long column = 1;
    /** Whether the last char returned was a carriage return, so that a line feed after it ends no further line. */
    private boolean afterCr;
    /** A strict refusal of bytes met while decoded chars were still to be returned; thrown by the next fill. */
    private XmlInput.Refusal refusal;

    DecodingReader(InputStream in, CharsetDecoder decoder, ProblemLog log) {
        this.in = in;
        this.decoder = decoder;
        this.log = log;
        bytes.flip();
        chars.flip();
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        if (length == 0) {
            return 0;
        }
        if (!chars.hasRemaining() && !fill()) {
            return -1;
        }
        int n = Math.min(length, chars.remaining());
        chars.get(buffer, offset, n);
        for (int i = offset; i < offset + n; i++) {
            advance(buffer[i]);
        }
        return n;
    }

    /**
     * Moves the position past {@code c}. A line feed, a carriage return, and the two together end a line, as in XML's
     * end-of-line handling.
     */
    private void advance(char c) {
        if (c == '\r' || (c == '\n' && !afterCr)) {
            line++;
            column = 1;
        } else if (c != '\n') {
            column++;
        }
        afterCr = c == '\r';
    }

    /** Decodes at least one char into {@code chars}; false at the end of input. */
    private boolean fill() throws IOException {
        if (refusal != null) {
            throw refusal;
        }
        if (flushed) {
            return false;
        }
        chars.clear();
        while (chars.position() == 0) {
            if (!eof && !bytes.hasRemaining()) {
                readMore();
            }
            CoderResult result = decoder.decode(bytes, chars, eof);
            if (result.isError()) {
                if (!chars.hasRemaining()) {
                    break;
                }
                long[] at = positionOfNextChar();
                if (!log.lenient()) {
                    refusal = new XmlInput.Refusal(new WxrException(
                            WxrProblem.Kind.INVALID_ENCODING,
                            at[0],
                            at[1],
                            result.length() + " byte(s) are not valid "
                                    + decoder.charset().name()));
                    if (chars.position() == 0) {
                        throw refusal;
                    }
                    break;
                }
                bytes.position(bytes.position() + result.length());
                chars.put(XmlChars.REPLACEMENT);
                log.repair(
                        WxrProblem.Kind.INVALID_ENCODING,
                        at[0],
                        at[1],
                        result.length() + " byte(s) are not valid "
                                + decoder.charset().name());
                continue;
            }
            if (result.isOverflow()) {
                break;
            }
            if (eof) {
                decoder.flush(chars);
                flushed = true;
                break;
            }
            if (chars.position() > 0) {
                // deliver these before reading on: a read error must not take decoded chars with it
                break;
            }
            readMore();
        }
        chars.flip();
        return chars.hasRemaining();
    }

    private void readMore() throws IOException {
        bytes.compact();
        int n = in.read(bytes.array(), bytes.arrayOffset() + bytes.position(), bytes.remaining());
        if (n < 0) {
            eof = true;
        } else {
            bytes.position(bytes.position() + n);
        }
        bytes.flip();
    }

    /** Line and column of the char about to be added to {@code chars}. */
    private long[] positionOfNextChar() {
        long savedLine = line;
        long savedColumn = column;
        boolean savedCr = afterCr;
        for (int i = 0; i < chars.position(); i++) {
            advance(chars.get(i));
        }
        long[] at = {line, column};
        line = savedLine;
        column = savedColumn;
        afterCr = savedCr;
        return at;
    }

    @Override
    public void close() throws IOException {
        in.close();
    }
}
