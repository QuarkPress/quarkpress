package org.quarkpress.lib.wxr;

import java.io.IOException;
import java.io.Reader;
import java.util.Objects;

/**
 * The lenient pre-filter in front of StAX. It tracks just enough XML (text, tag, comment, CDATA, processing
 * instruction, DOCTYPE) to repair what WordPress and other exporters get wrong, and logs each repair through
 * {@link ProblemLog#repair}, which throws in strict mode. Linear time; lookahead is at most {@link #LOOKAHEAD}
 * characters; memory is one input window plus the output of one step.
 *
 * <p>The parser buffers one whole comment, processing instruction, attribute name or attribute value at a time, so
 * this filter also holds each of them to the per-value limit: longer ones are refused (strict) or cut off with a
 * {@link WxrProblem.Kind#VALUE_TOO_LARGE} problem (lenient). Text and CDATA content are not cut here; the reader
 * that consumes the parser's events bounds those.
 *
 * <p>When asked to (the lenient reader does), it also binds the prefixes WordPress writes ({@code wp},
 * {@code excerpt}, {@code content}, {@code dc}, {@code wfw}) on the root element when the root does not declare
 * them: some exporters write {@code wp:} elements without the declaration, which a namespace-aware parser refuses.
 * Each is bound to a URI of its own ({@link WxrNames#UNDECLARED} plus the prefix), so that the reader reports only
 * those actually used. All five are bound when undeclared, used or not: whether a prefix is used is known only
 * after the root, and the added declarations shift the parser's columns on the root's line.
 */
final class RepairingReader extends Reader {

    private static final int WINDOW = 65_536;
    private static final int LOOKAHEAD = 48;
    private static final int MAX_NAME = 32;
    private static final int MAX_DIGITS = 8;
    private static final int MIN_DECLARATION_LIMIT = 1024;
    /** Leading zeros of a numeric reference that still count as one; keeps the lookahead under 48. */
    private static final int MAX_ZEROS = 24;
    /** Longer than any "xmlns:" declaration of a WordPress prefix. */
    private static final int MAX_ROOT_NAME = 16;

    private static final String TAG_STOPS = "<&>\"'";

    private enum State {
        TEXT,
        TAG,
        COMMENT,
        CDATA,
        PI,
        DOCTYPE
    }

    private final Reader in;
    private final ProblemLog log;
    private final long maxValue;
    /** The limit in force for the current value: the configured one, or the larger one of the XML declaration. */
    private long valueCap;

    private final char[] window = new char[WINDOW + LOOKAHEAD];
    private int pos;
    private int limit;
    private boolean inputDone;
    private final StringBuilder out = new StringBuilder();
    private int outPos;
    private State state = State.TEXT;
    private char quote;
    /** Chars of the current comment, instruction, attribute value or name seen so far, kept or not. */
    private long valueLength;

    /** Whether the processing instruction at the current position is the XML declaration (set by isReservedTarget). */
    private boolean declaration;
    /** How many ']' (at most two) end the text output so far; a '>' after two of them is escaped. */
    private int closers;

    private int doctypeBrackets;
    private char doctypeQuote;
    private long line = 1;
    private long column = 1;
    private long delivered;
    private IOException pendingIo;
    private RuntimeException pendingRuntime;
    /** A read error met while chars were still waiting in the window; thrown once they are processed. */
    private IOException inputFailure;
    /** Where the input failed: the position of the first char processed as if the input had ended there. */
    private long cutLine = Long.MAX_VALUE;

    private long cutColumn = Long.MAX_VALUE;

    /** Whether the root start tag is still to come and undeclared prefixes are to be bound on it. */
    private boolean rootPending;
    /** Inside the root start tag. */
    private boolean inRoot;
    /** Whether any start tag has begun. */
    private boolean startTagSeen;
    /** The attribute name being read in the root start tag, up to {@link #MAX_ROOT_NAME} chars. */
    private final StringBuilder rootName = new StringBuilder();
    /** Whether that name is longer than {@link #MAX_ROOT_NAME}, so no declaration of interest. */
    private boolean rootNameLong;
    /** Bit i set when the root declares {@code WxrNames.PREFIXES.get(i)}. */
    private int declaredPrefixes;
    /** Bit i set when this filter bound {@code WxrNames.PREFIXES.get(i)} on the root. */
    private int boundPrefixes;
    /** The last char of the root start tag outside whitespace, so far ('/' when it closes itself). */
    private char rootLast;

    RepairingReader(Reader in, ProblemLog log) {
        this(in, log, WxrReadOptions.DEFAULT_MAX_CHARS_PER_VALUE);
    }

    RepairingReader(Reader in, ProblemLog log, int maxCharsPerValue) {
        this(in, log, maxCharsPerValue, false);
    }

    /** {@code bindPrefixes}: bind the WordPress prefixes the root element does not declare (see above). */
    RepairingReader(Reader in, ProblemLog log, int maxCharsPerValue, boolean bindPrefixes) {
        if (maxCharsPerValue <= 0) {
            throw new IllegalArgumentException("maxCharsPerValue must be positive: " + maxCharsPerValue);
        }
        this.in = Objects.requireNonNull(in);
        this.log = Objects.requireNonNull(log);
        this.maxValue = maxCharsPerValue;
        this.valueCap = maxCharsPerValue;
        this.rootPending = bindPrefixes;
    }

    /** True when the underlying input ended without a read error and every char of it has been consumed. */
    boolean atEof() {
        return inputDone && pos >= limit && inputFailure == null;
    }

    /**
     * True when a problem at {@code problemLine}:{@code problemColumn} lies before the point where a read error cut
     * the input (always, when there was none): it was not caused by processing the remainder as the end of input.
     */
    boolean beforeCut(long problemLine, long problemColumn) {
        return problemLine < cutLine || (problemLine == cutLine && problemColumn < cutColumn);
    }

    /**
     * The WordPress prefixes this filter bound on the root element, as bits over {@code WxrNames.PREFIXES}: only
     * these bindings are its own, a document may spell the same URI itself.
     */
    int boundPrefixes() {
        return boundPrefixes;
    }

    /** Whether a start tag has begun in the input processed so far: the document has an element. */
    boolean startTagSeen() {
        return startTagSeen;
    }

    /** The number of chars returned by {@link #read} so far. */
    long delivered() {
        return delivered;
    }

    /**
     * Fills the caller's buffer as far as the input allows: it returns fewer chars than asked only at the end or when
     * a failure follows; chars already copied are returned first and the failure is thrown by the next call.
     */
    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, buffer.length);
        throwPending();
        if (length == 0) {
            return 0;
        }
        int filled = 0;
        try {
            while (filled < length) {
                if (outPos == out.length()) {
                    out.setLength(0);
                    outPos = 0;
                    try {
                        if (!step()) {
                            break;
                        }
                    } catch (IOException e) {
                        if (filled == 0) {
                            throw e;
                        }
                        pendingIo = e;
                        break;
                    } catch (RuntimeException e) {
                        if (filled == 0) {
                            throw e;
                        }
                        pendingRuntime = e;
                        break;
                    }
                    continue;
                }
                int n = Math.min(length - filled, out.length() - outPos);
                out.getChars(outPos, outPos + n, buffer, offset + filled);
                outPos += n;
                filled += n;
            }
        } finally {
            delivered += filled;
        }
        return filled == 0 ? -1 : filled;
    }

    private void throwPending() throws IOException {
        if (pendingIo != null) {
            throw pendingIo;
        }
        if (pendingRuntime != null) {
            throw pendingRuntime;
        }
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    /** Processes one construct or run of chars; false at the end of input. */
    private boolean step() throws IOException {
        if (peek(0) < 0) {
            if (inputFailure != null) {
                throw inputFailure;
            }
            return false;
        }
        switch (state) {
            case TEXT -> text();
            case TAG -> tag();
            case COMMENT -> section("-->", true);
            case CDATA -> section("]]>", false);
            case PI -> section("?>", true);
            case DOCTYPE -> doctype();
        }
        return true;
    }

    // ---- input window ----

    /** The char {@code ahead} positions past the current one, or -1 beyond the end of input. */
    private int peek(int ahead) throws IOException {
        if (pos + ahead >= limit) {
            fill(ahead);
        }
        return pos + ahead < limit ? window[pos + ahead] : -1;
    }

    private void fill(int ahead) throws IOException {
        if (inputDone) {
            return;
        }
        if (pos > 0) {
            System.arraycopy(window, pos, window, 0, limit - pos);
            limit -= pos;
            pos = 0;
        }
        while (limit <= ahead && !inputDone) {
            int n;
            try {
                n = in.read(window, limit, window.length - limit);
            } catch (IOException e) {
                cutLine = line;
                cutColumn = column;
                if (limit == 0) {
                    throw e;
                }
                // the chars already read are processed as the end of the input, then the error is thrown
                inputFailure = e;
                inputDone = true;
                return;
            }
            if (n < 0) {
                inputDone = true;
            } else {
                limit += n;
            }
        }
    }

    /** Like {@link #startsWith} for an ASCII-letter-and-punctuation prefix given in upper case. */
    private boolean startsWithIgnoreCase(String upper) throws IOException {
        for (int i = 0; i < upper.length(); i++) {
            int c = peek(i);
            char want = upper.charAt(i);
            boolean letter = want >= 'A' && want <= 'Z';
            if (c != want && !(letter && c == want + ('a' - 'A'))) {
                return false;
            }
        }
        return true;
    }

    private boolean startsWith(String s) throws IOException {
        for (int i = 0; i < s.length(); i++) {
            if (peek(i) != s.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** Moves past {@code n} chars, keeping line and column. */
    private void consume(int n) {
        for (int i = pos; i < pos + n; i++) {
            if (window[i] == '\n') {
                line++;
                column = 1;
            } else {
                column++;
            }
        }
        pos += n;
    }

    /** Copies {@code n} chars to the output and moves past them. */
    private void emit(int n) {
        out.append(window, pos, n);
        consume(n);
    }

    /**
     * Counts {@code n} more chars of the current bounded value and returns how many of them fit under the limit.
     * Reports the overflow once per value.
     */
    private int admit(int n) {
        long before = valueLength;
        valueLength += n;
        if (valueLength > valueCap) {
            if (before <= valueCap) {
                log.repair(
                        WxrProblem.Kind.VALUE_TOO_LARGE,
                        line,
                        column,
                        "a comment, instruction, attribute name or attribute value is longer than " + valueCap
                                + " chars");
            }
            return (int) Math.max(0, Math.min(n, valueCap - before));
        }
        return n;
    }

    /** Copies a run of {@code n} chars of a bounded value, as many as fit, and moves past all of them. */
    private void emitBounded(int n) {
        int kept = admit(n);
        out.append(window, pos, kept);
        consume(n);
    }

    /** Copies {@code n} chars of a value that may be bounded, or drops them when the bounded value is full. */
    private void emitOrDrop(int n, boolean bounded) {
        if (!bounded || admit(n) == n) {
            emit(n);
        } else {
            consume(n);
        }
    }

    // ---- states ----

    private void text() throws IOException {
        int i = pos;
        while (i < limit
                && window[i] != '<'
                && window[i] != '&'
                && window[i] != ']'
                && window[i] != '>'
                && XmlChars.isLegal(window[i])) {
            i++;
        }
        if (i > pos) {
            emit(i - pos);
            closers = 0;
            return;
        }
        char c = window[pos];
        if (c == ']') {
            emit(1);
            closers = Math.min(2, closers + 1);
        } else if (c == '>') {
            if (closers >= 2) {
                log.repair(WxrProblem.Kind.INVALID_MARKUP, line, column, "']]>' is not allowed in text");
                out.append("&gt;");
                consume(1);
            } else {
                emit(1);
            }
            closers = 0;
        } else if (c == '<') {
            markup();
        } else if (c == '&') {
            closers = 0;
            reference(false);
        } else {
            closers = 0;
            character(false);
        }
    }

    private void markup() throws IOException {
        if (startsWith("<!--")) {
            emit(4);
            valueLength = 0;
            closers = 0;
            state = State.COMMENT;
        } else if (startsWith("<![CDATA[")) {
            emit(9);
            closers = 0;
            state = State.CDATA;
        } else if (startsWithIgnoreCase("<!DOCTYPE")) {
            log.repair(WxrProblem.Kind.DOCTYPE, line, column, "DOCTYPE (DTDs are never processed)");
            consume(9);
            doctypeBrackets = 0;
            doctypeQuote = 0;
            state = State.DOCTYPE;
        } else if (startsWith("<?")) {
            if (!startsName(2)) {
                bareLessThan("a '<?' that is not followed by a target name is text");
            } else if (isReservedTarget()) {
                bareLessThan("an XML declaration is only allowed at the very start");
            } else if (declaration) {
                copyDeclaration();
            } else {
                emit(2);
                valueLength = 0;
                closers = 0;
                state = State.PI;
            }
        } else if (peek(1) == '/' ? startsName(2) : peek(1) != '!' && startsName(1)) {
            if (peek(1) != '/') {
                startTagSeen = true;
                inRoot = rootPending;
                rootPending = false;
                rootName.setLength(0);
                rootNameLong = false;
                rootLast = 0;
            }
            emit(1);
            quote = 0;
            valueLength = 0;
            closers = 0;
            state = State.TAG;
        } else {
            bareLessThan("a '<' that starts no markup is text");
        }
    }

    /**
     * Copies the XML declaration whole, in one step, so that one that is too long can be dropped instead of cut: a
     * cut declaration would not parse. Its limit is the value limit, but at least {@link #MIN_DECLARATION_LIMIT}.
     * Strict mode throws at the overflow; lenient mode drops the declaration, and the document has none.
     */
    private void copyDeclaration() throws IOException {
        out.append("<?");
        consume(2);
        valueLength = 0;
        closers = 0;
        valueCap = Math.max(maxValue, MIN_DECLARATION_LIMIT);
        state = State.PI;
        while (state == State.PI && peek(0) >= 0) {
            section("?>", true);
        }
        if (valueLength > valueCap) {
            out.setLength(0);
        }
        valueCap = maxValue;
    }

    private void bareLessThan(String detail) {
        log.repair(WxrProblem.Kind.BARE_LESS_THAN, line, column, detail);
        out.append("&lt;");
        consume(1);
        closers = 0;
    }

    /**
     * Whether the processing instruction at the current "&lt;?" has a target spelled like "xml" in any case, other
     * than a lowercase declaration at the very start of the input.
     */
    private boolean isReservedTarget() throws IOException {
        declaration = false;
        for (int i = 0; i < 3; i++) {
            int c = peek(2 + i);
            if (c < 0 || Character.toLowerCase((char) c) != "xml".charAt(i)) {
                return false;
            }
        }
        int after = peek(5);
        boolean continuesName = (after >= 'a' && after <= 'z')
                || (after >= 'A' && after <= 'Z')
                || (after >= '0' && after <= '9')
                || after == '-'
                || after == '.'
                || after == '_'
                || after == ':'
                || (after >= 0x80 && XmlChars.isNameStart(after));
        if (continuesName) {
            return false;
        }
        declaration = line == 1 && column == 1 && startsWith("<?xml");
        return !declaration;
    }

    /** Whether an XML name starts {@code ahead} chars past the current one. */
    private boolean startsName(int ahead) throws IOException {
        int c = peek(ahead);
        if (c >= 0 && Character.isHighSurrogate((char) c)) {
            int low = peek(ahead + 1);
            return low >= 0
                    && Character.isLowSurrogate((char) low)
                    && XmlChars.isNameStart(Character.toCodePoint((char) c, (char) low));
        }
        return c >= 0 && XmlChars.isNameStart(c);
    }

    private void tag() throws IOException {
        int i = pos;
        if (quote == 0) {
            while (i < limit && XmlChars.isLegal(window[i]) && TAG_STOPS.indexOf(window[i]) < 0) {
                i++;
            }
            if (i > pos) {
                copyNames(i - pos);
                return;
            }
            char c = window[pos];
            if (c == '>') {
                if (inRoot) {
                    endRootTag();
                }
                emit(1);
                state = State.TEXT;
            } else if (c == '"' || c == '\'') {
                if (inRoot) {
                    rootNameEnds();
                }
                quote = c;
                valueLength = 0;
                emit(1);
            } else if (c == '<' || c == '&') {
                emit(1); // not well-formed; StAX reports it
            } else {
                character(false);
            }
            return;
        }
        if (valueLength > valueCap) {
            // the value is already cut off: skip to its closing quote without looking at what is in between
            while (i < limit && window[i] != quote) {
                i++;
            }
            if (i > pos) {
                consume(i - pos);
                return;
            }
        }
        while (i < limit && XmlChars.isLegal(window[i]) && window[i] != quote && window[i] != '&' && window[i] != '<') {
            i++;
        }
        if (i > pos) {
            emitBounded(i - pos);
            return;
        }
        char c = window[pos];
        if (c == quote) {
            if (inRoot) {
                rootLast = quote;
            }
            quote = 0;
            valueLength = 0;
            emit(1);
        } else if (c == '&') {
            reference(true);
        } else if (c == '<') {
            if (valueLength >= valueCap) {
                admit(1);
            } else {
                log.repair(WxrProblem.Kind.BARE_LESS_THAN, line, column, "a '<' inside an attribute value is text");
                if (admit(4) == 4) {
                    out.append("&lt;");
                }
            }
            consume(1);
        } else {
            character(true);
        }
    }

    /** Copies a run of tag chars outside quotes, holding each name (a stretch without space, '=' or '/') to the limit. */
    private void copyNames(int n) {
        for (int i = pos; i < pos + n; i++) {
            char c = window[i];
            boolean space = c == ' ' || c == '\t' || c == '\n' || c == '\r';
            if (space || c == '=' || c == '/') {
                valueLength = 0;
                out.append(c);
                if (inRoot) {
                    rootChar(c, space, true);
                }
            } else {
                boolean kept = admit(1) == 1;
                if (kept) {
                    out.append(c);
                }
                if (inRoot) {
                    // the name as the parser will see it: a name cut at the limit declares what is left of it
                    rootChar(c, false, kept);
                }
            }
        }
        consume(n);
    }

    // ---- undeclared prefixes on the root element ----

    /** Follows one char of the root start tag outside quotes; {@code kept}: it reaches the parser. */
    private void rootChar(char c, boolean space, boolean kept) {
        if (!space) {
            rootLast = c;
        }
        if (space || c == '=' || c == '/') {
            rootNameEnds();
        } else if (!kept) {
            return;
        } else if (rootName.length() < MAX_ROOT_NAME) {
            rootName.append(c);
        } else {
            rootNameLong = true;
        }
    }

    /** An attribute name (or the element name) of the root start tag ends: note a WordPress prefix declaration. */
    private void rootNameEnds() {
        if (!rootNameLong && rootName.length() > 6 && rootName.indexOf("xmlns:") == 0) {
            int i = WxrNames.PREFIXES.indexOf(rootName.substring(6));
            if (i >= 0) {
                declaredPrefixes |= 1 << i;
            }
        }
        rootName.setLength(0);
        rootNameLong = false;
    }

    /** At the '>' of the root start tag: binds the WordPress prefixes it does not declare, unless it closes itself. */
    private void endRootTag() {
        rootNameEnds();
        inRoot = false;
        if (rootLast == '/') {
            return;
        }
        for (int i = 0; i < WxrNames.PREFIXES.size(); i++) {
            if ((declaredPrefixes & (1 << i)) == 0) {
                boundPrefixes |= 1 << i;
                String prefix = WxrNames.PREFIXES.get(i);
                out.append(" xmlns:")
                        .append(prefix)
                        .append("=\"")
                        .append(WxrNames.UNDECLARED)
                        .append(prefix)
                        .append('"');
            }
        }
    }

    /**
     * Copies a comment, CDATA section or processing instruction up to and including {@code end}. A bounded one is
     * held to the limit.
     */
    private void section(String end, boolean bounded) throws IOException {
        char first = end.charAt(0);
        if (bounded && valueLength > valueCap) {
            // already cut off: skip to the terminator without looking at what is in between
            int skip = pos;
            while (skip < limit && window[skip] != first) {
                skip++;
            }
            if (skip > pos) {
                consume(skip - pos);
                return;
            }
        }
        int i = pos;
        while (i < limit && window[i] != first && XmlChars.isLegal(window[i])) {
            i++;
        }
        if (i > pos) {
            if (bounded) {
                emitBounded(i - pos);
            } else {
                emit(i - pos);
            }
        } else if (startsWith(end)) {
            if (state == State.COMMENT && valueLength > valueCap) {
                out.append(' '); // a cut-off comment must not end in a dash
            }
            emit(end.length());
            state = State.TEXT;
        } else if (window[pos] == '-' && state == State.COMMENT && peek(1) == '-' && valueLength <= valueCap) {
            log.repair(WxrProblem.Kind.INVALID_MARKUP, line, column, "'--' is not allowed inside a comment");
            appendBounded("- ", true); // counted as the two chars it becomes
            consume(1);
        } else if (window[pos] == first) {
            emitOrDrop(1, bounded);
        } else {
            character(bounded);
        }
    }

    /** Skips the rest of a DOCTYPE, internal subset included; emits nothing. */
    private void doctype() throws IOException {
        int c;
        while ((c = peek(0)) >= 0) {
            if (c == '<' && doctypeQuote == 0 && doctypeBrackets > 0) {
                // comments and instructions in the internal subset are skipped whole: quotes and brackets in them mean
                // nothing
                if (startsWith("<!--")) {
                    skipThrough("-->", 4);
                    continue;
                }
                if (startsWith("<?")) {
                    skipThrough("?>", 2);
                    continue;
                }
            }
            consume(1);
            if (doctypeQuote != 0) {
                if (c == doctypeQuote) {
                    doctypeQuote = 0;
                }
            } else if (c == '"' || c == '\'') {
                doctypeQuote = (char) c;
            } else if (c == '[') {
                doctypeBrackets++;
            } else if (c == ']') {
                doctypeBrackets--;
            } else if (c == '>' && doctypeBrackets <= 0) {
                state = State.TEXT;
                return;
            }
        }
    }

    /** Skips {@code opening} chars and then everything up to and including {@code end}, or to the end of input. */
    private void skipThrough(String end, int opening) throws IOException {
        consume(opening);
        while (peek(0) >= 0) {
            if (startsWith(end)) {
                consume(end.length());
                return;
            }
            consume(1);
        }
    }

    private void reference(boolean bounded) throws IOException {
        if (bounded && valueLength >= valueCap) {
            admit(1); // the value is full: this char is dropped, and counted so the overflow is reported once
            consume(1);
            return;
        }
        int length = referenceLength();
        if (length == 0) {
            log.repair(WxrProblem.Kind.BARE_AMPERSAND, line, column, "a '&' that starts no reference is text");
            appendBounded("&amp;", bounded);
            consume(1);
            return;
        }
        if (window[pos + 1] == '#') {
            boolean hex = window[pos + 2] == 'x';
            int digits = hex ? pos + 3 : pos + 2;
            long codePoint = 0;
            for (int i = digits; i < pos + length - 1; i++) {
                codePoint = codePoint * (hex ? 16 : 10) + Character.digit(window[i], hex ? 16 : 10);
            }
            if (XmlChars.isLegal(codePoint)) {
                emitOrDrop(length, bounded);
            } else {
                log.repair(WxrProblem.Kind.ILLEGAL_CHARACTER, line, column, "reference to an XML-illegal code point");
                appendBounded("&#xFFFD;", bounded);
                consume(length);
            }
            return;
        }
        if (isPredefined(pos + 1, length - 2)) {
            emitOrDrop(length, bounded);
            return;
        }
        log.repair(
                WxrProblem.Kind.UNDEFINED_ENTITY,
                line,
                column,
                "undefined entity &" + new String(window, pos + 1, length - 2) + "; is text");
        appendBounded("&amp;", bounded);
        consume(1);
    }

    /** Appends a replacement to the output unless the bounded value it belongs to is already full. */
    private void appendBounded(String replacement, boolean bounded) {
        if (!bounded || admit(replacement.length()) == replacement.length()) {
            out.append(replacement);
        }
    }

    private boolean isPredefined(int from, int length) {
        return switch (length) {
            case 2 -> matches(from, "lt") || matches(from, "gt");
            case 3 -> matches(from, "amp");
            case 4 -> matches(from, "quot") || matches(from, "apos");
            default -> false;
        };
    }

    private boolean matches(int from, String name) {
        for (int i = 0; i < name.length(); i++) {
            if (window[from + i] != name.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    /** The length of a well-formed reference starting at the current '&', or 0. Fills the window as needed. */
    private int referenceLength() throws IOException {
        if (peek(1) == '#') {
            boolean hex = peek(2) == 'x';
            int start = hex ? 3 : 2;
            int i = start;
            while (i - start < MAX_ZEROS && peek(i) == '0') {
                i++;
            }
            int significant = i;
            while (i - significant < MAX_DIGITS && isDigit(peek(i), hex)) {
                i++;
            }
            return i > start && peek(i) == ';' ? i + 1 : 0;
        }
        if (!isAsciiNameStart(peek(1))) {
            return 0;
        }
        int i = 2;
        while (i <= MAX_NAME && isAsciiNameChar(peek(i))) {
            i++;
        }
        return peek(i) == ';' ? i + 1 : 0;
    }

    /** A char that is not plain: an XML-illegal one, or half of a surrogate pair. */
    private void character(boolean bounded) throws IOException {
        if (bounded && valueLength >= valueCap) {
            admit(1); // the value is full: this char is dropped, and counted so the overflow is reported once
            consume(1);
            return;
        }
        char c = window[pos];
        if (Character.isHighSurrogate(c)) {
            int next = peek(1);
            if (next >= 0 && Character.isLowSurrogate((char) next)) {
                emitOrDrop(2, bounded);
                return;
            }
        }
        // plain concatenation: this runs once per bad char, millions of times in a corrupted file
        log.repair(
                WxrProblem.Kind.ILLEGAL_CHARACTER,
                line,
                column,
                "char 0x" + Integer.toHexString(window[pos]) + " is not allowed in XML");
        if (!bounded || admit(1) == 1) {
            out.append('\uFFFD');
        }
        consume(1);
    }

    // ---- character classes ----

    private static boolean isAsciiNameStart(int c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || c == '_' || c == ':';
    }

    private static boolean isAsciiNameChar(int c) {
        return isAsciiNameStart(c) || (c >= '0' && c <= '9') || c == '-' || c == '.';
    }

    private static boolean isDigit(int c, boolean hex) {
        return (c >= '0' && c <= '9') || (hex && ((c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F')));
    }
}
