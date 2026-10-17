package org.quarkpress.lib.wxr;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import javax.xml.stream.Location;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/**
 * Pulls the entries of a WXR file one at a time: authors, categories, tags, terms and items (each item with its
 * terms, meta and comments). Memory follows the largest entry, never the file. Field order inside an entry does
 * not matter; unknown elements are skipped with their whole subtree.
 *
 * <pre>{@code
 * try (WxrReader reader = WxrReader.open(in, WxrReadOptions.LENIENT)) {
 *     while (reader.hasNext()) {
 *         switch (reader.next()) {
 *             case WxrItem item -> ...
 *             default -> ...
 *         }
 *     }
 *     List<WxrProblem> problems = reader.problems();
 * }
 * }</pre>
 *
 * <p>A file that ends inside the document (an export cut off by a server timeout) or whose XML breaks somewhere
 * is read up to the break. Lenient mode delivers every complete entry, then the entry being read with the fields
 * completed before the break (a field, item term or meta row cut in the middle is dropped; a comment cut in the
 * middle is kept with its completed fields), records {@link WxrProblem.Kind#TRUNCATED} (the input ended) or
 * {@link WxrProblem.Kind#MALFORMED} (it broke earlier), and ends. Strict mode delivers the complete entries and
 * throws {@link WxrException} of that kind at the break. Input without any element is
 * {@link WxrProblem.Kind#NOT_WXR}. Lenient mode also reads {@code wp:} and the other WordPress prefixes when the
 * document never declares them, as the WordPress Importer's regular-expression parser does, recording
 * {@link WxrProblem.Kind#UNKNOWN_NAMESPACE}; strict mode refuses such a document as malformed.
 *
 * <p>Lenient mode does not make every limit a problem: a text or CDATA value longer than
 * {@link WxrReadOptions#maxCharsPerValue()} and an item past {@link WxrReadOptions#maxItems()} throw
 * {@link WxrException} ({@link WxrProblem.Kind#VALUE_TOO_LARGE}, {@link WxrProblem.Kind#TOO_MANY_ITEMS}) in both
 * modes; only overlong comments, processing instructions and attributes are cut off with a problem.
 *
 * <p>Problem positions come from the parser and the pre-filter. The reader reads ahead, so lenient mode can list
 * problems found past the point where reading stopped (junk after {@code </rss>}, or repairs just past a
 * {@link WxrProblem.Kind#MALFORMED} break): the problem list may include positions no entry covers. On the root
 * element's line, columns after the root start tag are shifted by the prefix declarations lenient mode adds there.
 * The parser counts lines, columns and offsets in {@code int}s, so on inputs beyond 2<sup>31</sup> chars its
 * positions can wrap.
 *
 * <p>Not thread-safe. I/O errors of the given stream surface as {@link UncheckedIOException} in both modes. A gzip
 * stream that is cut off or damaged is a content problem: strict mode throws {@link WxrException} of kind
 * {@link WxrProblem.Kind#TRUNCATED} (the compressed data ends early) or {@link WxrProblem.Kind#MALFORMED} (it is
 * corrupt), with the decompressor's exception as cause; lenient mode records that problem and ends after the
 * entries read completely before the damage.
 */
public final class WxrReader implements AutoCloseable {

    private final InputStream source;
    private final WxrReadOptions options;
    private final ProblemLog log;
    private final SiteFields site = new SiteFields();
    /** The decoded input, set when reading starts; it remembers a read error the parser does not pass on. */
    private WatchedReader decoded;

    private RepairingReader repairing;

    private XMLStreamReader xml;
    private WxrEntry pending;
    private boolean started;
    private boolean finished;
    private boolean versionChecked;
    private boolean unknownNamespaceNoted;
    /** The undeclared WordPress prefixes already reported, as bits over {@code WxrNames.PREFIXES}. */
    private int undeclaredNoted;

    private boolean rootSeen;
    private boolean inChannel;
    private long items;
    private Fields current;
    private String currentName;

    private WxrReader(InputStream source, WxrReadOptions options) {
        this.source = source;
        this.options = options;
        this.log = new ProblemLog(options.lenient());
    }

    /** A lenient reader of {@code in}; see {@link #open(InputStream, WxrReadOptions)}. */
    public static WxrReader open(InputStream in) {
        return open(in, WxrReadOptions.LENIENT);
    }

    /**
     * A reader of {@code in} with {@code options}. Nothing is read until the first call to {@link #hasNext()},
     * {@link #next()} or {@link #site()}. The reader owns the stream: closing the reader closes it.
     *
     * @throws NullPointerException when {@code in} or {@code options} is null
     */
    public static WxrReader open(InputStream in, WxrReadOptions options) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(options, "options");
        return new WxrReader(in, options);
    }

    /**
     * The channel fields read so far (all those before the first entry once {@link #hasNext()} was called). Calls
     * {@link #hasNext()}, so it can throw what that throws.
     */
    public WxrSite site() {
        hasNext();
        return site.snapshot();
    }

    /**
     * Whether another entry follows, reading up to the end of it. False at the end of the channel, after a break in
     * lenient mode, and once the reader is closed.
     *
     * @throws WxrException in strict mode at the first problem, and in both modes when a limit is exceeded
     * @throws UncheckedIOException when the given stream fails
     */
    public boolean hasNext() {
        if (pending == null) {
            pending = fetch();
        }
        return pending != null;
    }

    /**
     * The next entry.
     *
     * @throws NoSuchElementException when there is none ({@link #hasNext()} is false)
     * @throws WxrException as {@link #hasNext()} does
     * @throws UncheckedIOException as {@link #hasNext()} does
     */
    public WxrEntry next() {
        if (!hasNext()) {
            throw new NoSuchElementException();
        }
        WxrEntry entry = pending;
        pending = null;
        return entry;
    }

    /** Recoveries and notices so far; at most 1001 are kept (see {@link #problemCount()}). */
    public List<WxrProblem> problems() {
        return log.problems();
    }

    /** How many problems were found so far, including those beyond the 1000 that {@link #problems()} keeps. */
    public long problemCount() {
        return log.count();
    }

    /**
     * Closes the reader and the given stream. Afterwards {@link #hasNext()} is false; {@link #problems()} and
     * {@link #site()} still give what was read.
     *
     * @throws UncheckedIOException when closing the given stream fails
     */
    @Override
    public void close() {
        finished = true;
        pending = null;
        try {
            if (xml != null) {
                xml.close();
            }
        } catch (XMLStreamException e) {
            // nothing useful to report: the streams are closed next
        }
        try {
            if (decoded != null) {
                // ends the decompressor too, and closes the given stream through the chain
                decoded.close();
            }
        } catch (IOException e) {
            // the given stream is closed directly next, which reports its own failure
        }
        try {
            source.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // ---- reading ----

    private WxrEntry fetch() {
        if (finished) {
            return null;
        }
        try {
            if (!started) {
                started = true;
                decoded = new WatchedReader(XmlInput.decode(source, options, log));
                repairing = new RepairingReader(decoded, log, options.maxCharsPerValue(), options.lenient());
                xml = StaxFactory.create().createXMLStreamReader(repairing);
                openChannel();
            }
            WxrEntry entry = inChannel ? nextInChannel() : null;
            if (entry == null) {
                finish();
            }
            return entry;
        } catch (WxrException e) {
            finished = true;
            if (e == log.failure() && readError() != null && !beforeCut(e)) {
                // a strict refusal of what the cut left at the end of the input: the read error is the cause
                return readFailure(readError());
            }
            throw e;
        } catch (XMLStreamException e) {
            return recover(e);
        } catch (IOException e) {
            return readFailure(e);
        } catch (RuntimeException e) {
            finished = true;
            WxrException refused = log.failure();
            throw refused != null ? refused : e;
        }
    }

    private void finish() {
        finished = true;
        checkVersion();
        if (readError() != null) {
            // the parser read ahead past the end of the channel and met a read error there
            readFailure(readError());
        }
    }

    /** Once, before the first entry or at the end of the channel: what the WordPress Importer requires. */
    private void checkVersion() {
        if (versionChecked) {
            return;
        }
        versionChecked = true;
        String version = site.get("wp:wxr_version");
        if (version == null) {
            log.repair(WxrProblem.Kind.VERSION_MISSING, line(), column(), "no wp:wxr_version before the first entry");
            return;
        }
        String trimmed = Texts.phpTrim(version);
        int dot = trimmed.indexOf('.');
        if (dot < 1 || !digitsOnly(trimmed, 0, dot) || !digitsOnly(trimmed, dot + 1, trimmed.length())) {
            log.repair(
                    WxrProblem.Kind.VERSION_INVALID,
                    line(),
                    column(),
                    "wp:wxr_version '" + Texts.clip(version) + "' is not <digits>.<digits>");
            return;
        }
        if (newerThanCurrent(trimmed, dot)) {
            log.note(
                    WxrProblem.Kind.VERSION_NEWER,
                    line(),
                    column(),
                    "WXR " + Texts.clip(trimmed) + " is newer than 1.2");
        }
    }

    /** True for a non-empty run of ASCII digits in {@code s[from, to)}. */
    private static boolean digitsOnly(String s, int from, int to) {
        if (from >= to) {
            return false;
        }
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the number {@code <major>.<fraction>} is greater than 1.2, compared as a number (so "1.10" is 1.1 and
     * "1.20" is 1.2), without any size limit on the digits.
     */
    private static boolean newerThanCurrent(String version, int dot) {
        int start = 0;
        while (start < dot - 1 && version.charAt(start) == '0') {
            start++;
        }
        String major = version.substring(start, dot);
        if (!"0".equals(major) && !"1".equals(major)) {
            return true;
        }
        if ("0".equals(major)) {
            return false;
        }
        int end = version.length();
        while (end > dot + 1 && version.charAt(end - 1) == '0') {
            end--;
        }
        return version.substring(dot + 1, end).compareTo("2") > 0;
    }

    /**
     * After an XML error. A strict refusal recorded before it, or a read error behind it, decides first. Input without
     * any element is not WXR. Otherwise the error is TRUNCATED when the parser met it at the end of the input, else
     * MALFORMED: thrown in strict mode; in lenient mode recorded, the version checked, and the entry being read
     * returned with its completed fields.
     */
    private WxrEntry recover(XMLStreamException e) {
        finished = true;
        WxrException refused = log.failure();
        if (refused != null && (readError() == null || beforeCut(refused))) {
            throw refused;
        }
        // The JDK parser reports a failing read as a premature end of the document, without the cause.
        if (readError() != null) {
            return readFailure(readError());
        }
        if (e.getNestedException() instanceof IOException io) {
            return readFailure(io);
        }
        Location at = e.getLocation();
        if (!rootSeen && (repairing == null || !repairing.startTagSeen())) {
            versionChecked = true;
            log.repair(WxrProblem.Kind.NOT_WXR, lineOf(at), columnOf(at), "the input has no XML element");
            return null;
        }
        boolean truncated =
                repairing.atEof() && (at == null || atEndOfInput(at.getCharacterOffset(), repairing.delivered()));
        WxrProblem.Kind kind = truncated ? WxrProblem.Kind.TRUNCATED : WxrProblem.Kind.MALFORMED;
        String message =
                truncated ? "the file ends before the document does" : "malformed XML: " + Texts.clip(e.getMessage());
        if (!log.lenient()) {
            throw new WxrException(kind, lineOf(at), columnOf(at), message, e);
        }
        log.note(kind, lineOf(at), columnOf(at), message);
        checkVersion();
        WxrEntry partial = current == null ? null : EntityBuilders.build(currentName, current);
        current = null;
        currentName = null;
        return partial;
    }

    /**
     * Whether a parse error at char {@code offset} (as the parser reports it) lies at the end of the {@code delivered}
     * chars it was given. The parser reads ahead, so the whole of a small input is consumed long before an error in
     * its middle is reported: the input ended at the error only when the parser was at the end of what it had (at
     * an unfinished document the JDK parser reports an offset past the end). The parser's offset is an {@code int}
     * that wraps past 2<sup>32</sup> chars, so the two are compared by their difference modulo 2<sup>32</sup>, read
     * as signed: the parser is never 2<sup>31</sup> chars away from what it was given. -1 means it does not know.
     */
    static boolean atEndOfInput(int offset, long delivered) {
        if (offset == -1) {
            return true;
        }
        int ahead = (int) ((offset & 0xFFFF_FFFFL) - delivered);
        return ahead >= -2;
    }

    private boolean beforeCut(WxrException refused) {
        return repairing != null && repairing.beforeCut(refused.line(), refused.column());
    }

    private IOException readError() {
        return decoded == null ? null : decoded.failure;
    }

    /**
     * The input could not be read to its end. A failure of the caller's stream is thrown as
     * {@link UncheckedIOException} in both modes. Damage found while decompressing is content:
     * {@link WxrProblem.Kind#TRUNCATED} when the compressed data ends early, {@link WxrProblem.Kind#MALFORMED}
     * otherwise; thrown in strict mode, recorded in lenient mode, where reading ends.
     */
    private WxrEntry readFailure(IOException e) {
        finished = true;
        if (e instanceof XmlInput.SourceException source) {
            throw new UncheckedIOException(source.original());
        }
        if (e instanceof XmlInput.Refusal refusal) {
            throw refusal.refusal();
        }
        boolean early = e instanceof EOFException;
        WxrProblem.Kind kind = early ? WxrProblem.Kind.TRUNCATED : WxrProblem.Kind.MALFORMED;
        String detail = (early ? "the compressed input ends early: " : "the compressed input is damaged: ")
                + Texts.clip(String.valueOf(e));
        if (!log.lenient()) {
            throw new WxrException(kind, line(), column(), detail, e);
        }
        log.note(kind, line(), column(), detail);
        return null;
    }

    private void openChannel() throws XMLStreamException {
        if (!nextStartElement()) {
            versionChecked = true;
            log.repair(WxrProblem.Kind.NOT_WXR, line(), column(), "the input has no XML element");
            return;
        }
        rootSeen = true;
        if (!"rss".equals(xml.getLocalName())) {
            log.repair(
                    WxrProblem.Kind.NOT_WXR,
                    line(),
                    column(),
                    "the root element is <" + Texts.clip(xml.getLocalName()) + ">");
        }
        while (true) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                if ("channel".equals(xml.getLocalName()) && noNamespace()) {
                    inChannel = true;
                    return;
                }
                skipElement();
            } else if (event == XMLStreamConstants.END_ELEMENT || event == XMLStreamConstants.END_DOCUMENT) {
                log.repair(WxrProblem.Kind.NOT_WXR, line(), column(), "the document has no <channel>");
                return;
            }
        }
    }

    private WxrEntry nextInChannel() throws XMLStreamException {
        while (true) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                inChannel = false;
                return null;
            }
            if (event == XMLStreamConstants.END_DOCUMENT) {
                throw new XMLStreamException("the document ends inside <channel>", xml.getLocation());
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String name = name();
            switch (name) {
                case "wp:author", "wp:category", "wp:tag", "wp:term", "item" -> {
                    return entity(name);
                }
                default -> {
                    if (SiteFields.NAMES.contains(name)) {
                        String value = readLeaf();
                        if (value != null) {
                            site.putIfAbsent(name, value);
                        }
                    } else {
                        skipElement();
                    }
                }
            }
        }
    }

    private WxrEntry entity(String name) throws XMLStreamException {
        checkVersion();
        if ("item".equals(name) && ++items > options.maxItems()) {
            throw new WxrException(
                    WxrProblem.Kind.TOO_MANY_ITEMS, line(), column(), "more than " + options.maxItems() + " items");
        }
        current = new Fields();
        currentName = name;
        readChildren(current, name);
        WxrEntry entry = EntityBuilders.build(name, current);
        current = null;
        currentName = null;
        return entry;
    }

    private void readChildren(Fields into, String owner) throws XMLStreamException {
        while (true) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                return;
            }
            if (event == XMLStreamConstants.END_DOCUMENT) {
                throw new XMLStreamException("the document ends inside <" + owner + ">", xml.getLocation());
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            String child = name();
            switch (child) {
                case "category" -> {
                    if ("item".equals(owner)) {
                        into.terms.add(itemTerm());
                    } else {
                        leaf(into, child);
                    }
                }
                case "wp:postmeta" -> meta(into, "item".equals(owner));
                case "wp:commentmeta" -> meta(into, "wp:comment".equals(owner));
                case "wp:termmeta" ->
                    meta(into, "wp:category".equals(owner) || "wp:tag".equals(owner) || "wp:term".equals(owner));
                case "wp:comment" -> {
                    if ("item".equals(owner)) {
                        Fields comment = new Fields();
                        into.comments.add(comment);
                        readChildren(comment, child);
                    } else {
                        skipElement();
                    }
                }
                default -> leaf(into, child);
            }
        }
    }

    private void leaf(Fields into, String child) throws XMLStreamException {
        if ("guid".equals(child)) {
            String permaLink = xml.getAttributeValue(null, "isPermaLink");
            if (permaLink != null) {
                into.values.putIfAbsent("guid@isPermaLink", permaLink);
            }
        }
        String value = readLeaf();
        if (value != null) {
            into.values.putIfAbsent(child, value);
        }
    }

    private WxrItemTerm itemTerm() throws XMLStreamException {
        String domain = xml.getAttributeValue(null, "domain");
        String nicename = xml.getAttributeValue(null, "nicename");
        String text = readLeaf();
        return new WxrItemTerm(domain, nicename, text == null ? "" : text);
    }

    private void meta(Fields into, boolean belongs) throws XMLStreamException {
        if (!belongs) {
            skipElement();
            return;
        }
        String key = null;
        String value = null;
        while (true) {
            int event = xml.next();
            if (event == XMLStreamConstants.END_ELEMENT) {
                into.meta.add(new WxrMeta(key, value));
                return;
            }
            if (event == XMLStreamConstants.END_DOCUMENT) {
                throw new XMLStreamException("the document ends inside a meta row", xml.getLocation());
            }
            if (event == XMLStreamConstants.START_ELEMENT) {
                String child = name();
                if ("wp:meta_key".equals(child) && key == null) {
                    key = readLeaf();
                } else if ("wp:meta_value".equals(child) && value == null) {
                    value = readLeaf();
                } else {
                    skipElement();
                }
            }
        }
    }

    /** The text of a leaf element, CDATA and text joined, entities decoded, untrimmed; null if it had children. */
    private String readLeaf() throws XMLStreamException {
        TextBuffer text = new TextBuffer(options.maxCharsPerValue());
        int depth = 1;
        boolean children = false;
        while (true) {
            int event = xml.next();
            switch (event) {
                case XMLStreamConstants.CHARACTERS, XMLStreamConstants.CDATA, XMLStreamConstants.SPACE -> {
                    if (!children) {
                        text.append(xml);
                    }
                }
                case XMLStreamConstants.START_ELEMENT -> {
                    depth++;
                    children = true;
                }
                case XMLStreamConstants.END_ELEMENT -> {
                    if (--depth == 0) {
                        return children ? null : text.toString();
                    }
                }
                case XMLStreamConstants.END_DOCUMENT ->
                    throw new XMLStreamException("the document ends inside an element", xml.getLocation());
                default -> {}
            }
        }
    }

    private void skipElement() throws XMLStreamException {
        int depth = 1;
        while (depth > 0) {
            int event = xml.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                depth--;
            } else if (event == XMLStreamConstants.END_DOCUMENT) {
                throw new XMLStreamException("the document ends inside an element", xml.getLocation());
            }
        }
    }

    private boolean nextStartElement() throws XMLStreamException {
        while (xml.hasNext()) {
            if (xml.next() == XMLStreamConstants.START_ELEMENT) {
                return true;
            }
        }
        return false;
    }

    /** The canonical name of the current element: "title", "wp:post_id", or "{uri}local" for other namespaces. */
    private String name() {
        String uri = xml.getNamespaceURI();
        String local = xml.getLocalName();
        if (uri == null || uri.isEmpty()) {
            return local;
        }
        String undeclared = WxrNames.undeclaredPrefix(uri);
        int bit = undeclared == null ? 0 : 1 << WxrNames.PREFIXES.indexOf(undeclared);
        if ((repairing.boundPrefixes() & bit) != 0) {
            uri = WxrNames.uriOf(undeclared);
            if ((undeclaredNoted & bit) == 0) {
                undeclaredNoted |= bit;
                log.note(
                        WxrProblem.Kind.UNKNOWN_NAMESPACE,
                        line(),
                        column(),
                        "the prefix '" + undeclared + "' is not declared; read as " + uri);
            }
        }
        String prefix = WxrNames.knownPrefix(uri);
        if (prefix == null && options.acceptAnyWpNamespace()) {
            String written = xml.getPrefix();
            if ("wp".equals(written) || "excerpt".equals(written)) {
                prefix = written;
                if (!unknownNamespaceNoted) {
                    unknownNamespaceNoted = true;
                    log.note(
                            WxrProblem.Kind.UNKNOWN_NAMESPACE,
                            line(),
                            column(),
                            "'" + written + "' is bound to " + Texts.clip(uri)
                                    + ", a URI WordPress never used; read anyway");
                }
            }
        }
        return prefix == null ? "{" + uri + "}" + local : prefix + ":" + local;
    }

    private boolean noNamespace() {
        String uri = xml.getNamespaceURI();
        return uri == null || uri.isEmpty();
    }

    private long line() {
        return xml == null ? -1 : xml.getLocation().getLineNumber();
    }

    private long column() {
        return xml == null ? -1 : xml.getLocation().getColumnNumber();
    }

    private static long lineOf(Location at) {
        return at == null ? -1 : at.getLineNumber();
    }

    private static long columnOf(Location at) {
        return at == null ? -1 : at.getColumnNumber();
    }

    /** Passes reads through and remembers the first read error, which the JDK parser would otherwise swallow. */
    private static final class WatchedReader extends Reader {

        private final Reader in;
        IOException failure;

        WatchedReader(Reader in) {
            this.in = in;
        }

        @Override
        public int read(char[] buffer, int offset, int length) throws IOException {
            try {
                return in.read(buffer, offset, length);
            } catch (IOException e) {
                if (failure == null) {
                    failure = e;
                }
                throw e;
            }
        }

        @Override
        public void close() throws IOException {
            in.close();
        }
    }
}
