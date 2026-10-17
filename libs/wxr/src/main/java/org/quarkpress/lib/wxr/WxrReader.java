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
 * Not thread-safe. I/O errors of the given stream surface as {@link UncheckedIOException} in both modes. A gzip
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
    private boolean inChannel;
    private long items;
    private Fields current;
    private String currentName;

    private WxrReader(InputStream source, WxrReadOptions options) {
        this.source = source;
        this.options = options;
        this.log = new ProblemLog(options.lenient());
    }

    /** A lenient reader. */
    public static WxrReader open(InputStream in) {
        return open(in, WxrReadOptions.LENIENT);
    }

    public static WxrReader open(InputStream in, WxrReadOptions options) {
        Objects.requireNonNull(in, "in");
        Objects.requireNonNull(options, "options");
        return new WxrReader(in, options);
    }

    /** The channel fields read so far (all those before the first entry once {@link #hasNext()} was called). */
    public WxrSite site() {
        hasNext();
        return site.snapshot();
    }

    public boolean hasNext() {
        if (pending == null) {
            pending = fetch();
        }
        return pending != null;
    }

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

    public long problemCount() {
        return log.count();
    }

    @Override
    public void close() {
        finished = true;
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
                repairing = new RepairingReader(decoded, log, options.maxCharsPerValue());
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
        if (readError() != null) {
            // the parser read ahead past the end of the channel and met a read error there
            readFailure(readError());
        }
    }

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
        throw new WxrException(WxrProblem.Kind.MALFORMED, lineOf(at), columnOf(at), String.valueOf(e.getMessage()), e);
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
        boolean early = e instanceof EOFException;
        WxrProblem.Kind kind = early ? WxrProblem.Kind.TRUNCATED : WxrProblem.Kind.MALFORMED;
        String detail = (early ? "the compressed input ends early: " : "the compressed input is damaged: ") + e;
        if (!log.lenient()) {
            throw new WxrException(kind, line(), column(), detail, e);
        }
        log.note(kind, line(), column(), detail);
        return null;
    }

    private void openChannel() throws XMLStreamException {
        if (!nextStartElement()) {
            return;
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
        String prefix = WxrNames.knownPrefix(uri);
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
