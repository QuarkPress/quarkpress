package org.quarkpress.lib.wxr;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Writes WXR 1.2 in WordPress 7.1.3's layout: the same elements in the same order, with CDATA exactly where
 * WordPress uses it. Unlike WordPress, the output is always well-formed: attribute values and the comment author URL
 * are escaped, characters XML cannot hold become U+FFFD, a carriage return is written as a character reference so
 * that it survives parsing, and the file is UTF-8 without a byte order mark. A term or category named {@code "0"}
 * keeps its name (WordPress drops it).
 *
 * <p>Sections must come in WordPress's order: authors, categories, tags, terms, items; {@link #close()} ends the
 * document and closes the stream. A category or term must come after its parent, which it names by slug or, in
 * data read from a WXR 1.0 file, by name. Which entries to export is the caller's policy: the writer neither
 * filters nor reorders the entries it is given (WordPress's exporter skips edit locks, spam comments, revisions and
 * auto-drafts before writing).
 *
 * <p>Within an entry the writer writes WordPress's fields, as given; the site's {@code wxrVersion} is ignored, since
 * the file is 1.2. A field that is null is not written (WordPress always has every field; data read from a file may
 * lack some), except that an integer field that is null is written from its raw text in {@code rawFields()} when
 * there is one, and the channel {@code pubDate} falls back to the clock. That fallback is the only use of
 * {@code rawFields()}: elements WordPress does not write (unknown or foreign elements a reader kept there) are not
 * written. {@code attachmentUrl} is written for attachments only, as WordPress does. Category, tag and term names
 * and descriptions are omitted when empty, as WordPress omits them.
 *
 * <p>Memory: one entry at a time, buffered as text before it is written (about 2 to 3 times the chars of its
 * values), plus the slug and name of every category and term written so far, kept for the parent checks.
 *
 * <p>Errors: an entry that is out of order or names a parent not written before it is refused before anything is
 * written, and the writer stays usable. Any other failure inside an entry method (an I/O error, surfacing as
 * {@link UncheckedIOException}) breaks the writer: later entry calls throw {@link IllegalStateException}, and
 * {@link #close()} closes the stream without ending the document. Not thread-safe.
 */
public final class WxrWriter implements AutoCloseable {

    /** The WXR version every file is written as. */
    public static final String WXR_VERSION = "1.2";

    private static final DateTimeFormatter RFC_822 = DateTimeFormatter.ofPattern(
                    "EEE, dd MMM yyyy HH:mm:ss '+0000'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    /** Buffer capacity kept between entries; a huge entry's buffer is released after it is written. */
    private static final int RETAINED_CAPACITY = 1 << 20;

    private enum Phase {
        AUTHORS("authors"),
        CATEGORIES("categories"),
        TAGS("tags"),
        TERMS("terms"),
        ITEMS("items"),
        CLOSED("closing");

        final String label;

        Phase(String label) {
            this.label = label;
        }
    }

    private final OutputStream sink;
    private final Writer out;
    private final WxrSite site;
    private final StringBuilder buf = new StringBuilder(8192);
    private final char[] chunk = new char[8192];
    private final Set<String> categoryParents = new HashSet<>();
    private final Map<String, Set<String>> termParents = new HashMap<>();
    private Phase phase = Phase.AUTHORS;
    /** Set when an entry failed part-way: nothing more is written. */
    private boolean broken;

    private WxrWriter(OutputStream out, WxrSite site) {
        this.sink = out;
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 65_536);
        this.site = site;
    }

    /** A writer to {@code out} dated by the system clock; see {@link #open(OutputStream, WxrSite, Clock)}. */
    public static WxrWriter open(OutputStream out, WxrSite site) {
        return open(out, site, Clock.systemUTC());
    }

    /**
     * A writer to {@code out} that has written the XML declaration, the namespaces and the channel fields of
     * {@code site}. {@code clock} dates the channel {@code pubDate} when the site has none. The writer owns the
     * stream: {@link #close()} closes it, and so does this method when writing the header fails.
     *
     * @throws NullPointerException when an argument is null
     * @throws UncheckedIOException when writing the header fails
     */
    public static WxrWriter open(OutputStream out, WxrSite site, Clock clock) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(clock, "clock");
        WxrWriter writer = new WxrWriter(out, site);
        try {
            writer.header(clock);
        } catch (RuntimeException | Error e) {
            try {
                out.close();
            } catch (IOException closing) {
                e.addSuppressed(closing);
            }
            throw e;
        }
        return writer;
    }

    /**
     * Writes an author. Authors come first.
     *
     * @throws IllegalStateException when another section was already started, the writer is closed, or it broke
     * @throws UncheckedIOException when writing fails (the writer is then broken)
     */
    public void author(WxrAuthor a) {
        Objects.requireNonNull(a, "author");
        checkOrder(Phase.AUTHORS);
        write(Phase.AUTHORS, () -> {
            indent(1);
            buf.append("<wp:author>");
            inlineInteger("wp:author_id", a.id(), a.rawFields());
            inlineCdata("wp:author_login", a.login());
            inlineCdata("wp:author_email", a.email());
            inlineCdata("wp:author_display_name", a.displayName());
            inlineCdata("wp:author_first_name", a.firstName());
            inlineCdata("wp:author_last_name", a.lastName());
            buf.append("</wp:author>\n");
        });
    }

    /**
     * Writes a category, after the authors. Its parent (a slug, or a name in data from a WXR 1.0 file) must be a
     * category written before it.
     *
     * @throws IllegalStateException when tags, terms or items were already started, the writer is closed, or it broke
     * @throws IllegalArgumentException when the parent was not written before (nothing is written)
     * @throws UncheckedIOException when writing fails (the writer is then broken)
     */
    public void category(WxrCategory c) {
        Objects.requireNonNull(c, "category");
        checkOrder(Phase.CATEGORIES);
        checkParent("category", c.nicename(), c.parent(), categoryParents);
        write(Phase.CATEGORIES, () -> {
            indent(1);
            buf.append("<wp:category>\n");
            integer(2, "wp:term_id", c.termId(), c.rawFields());
            cdata(2, "wp:category_nicename", c.nicename());
            cdata(2, "wp:category_parent", c.parent());
            optionalCdata(2, "wp:cat_name", c.name());
            optionalCdata(2, "wp:category_description", c.description());
            meta(2, "wp:termmeta", c.termMeta());
            indent(1);
            buf.append("</wp:category>\n");
        });
        remember(categoryParents, c.nicename(), c.name());
    }

    /**
     * Writes a tag, after the categories.
     *
     * @throws IllegalStateException when terms or items were already started, the writer is closed, or it broke
     * @throws UncheckedIOException when writing fails (the writer is then broken)
     */
    public void tag(WxrTag t) {
        Objects.requireNonNull(t, "tag");
        checkOrder(Phase.TAGS);
        write(Phase.TAGS, () -> {
            indent(1);
            buf.append("<wp:tag>\n");
            integer(2, "wp:term_id", t.termId(), t.rawFields());
            cdata(2, "wp:tag_slug", t.slug());
            optionalCdata(2, "wp:tag_name", t.name());
            optionalCdata(2, "wp:tag_description", t.description());
            meta(2, "wp:termmeta", t.termMeta());
            indent(1);
            buf.append("</wp:tag>\n");
        });
    }

    /**
     * Writes a custom-taxonomy term, or a nav menu ({@code taxonomy} "nav_menu", written in WordPress's short form
     * without parent, description or term meta), after the tags. A term's parent is looked up among the terms of
     * its own taxonomy written before it.
     *
     * @throws IllegalStateException when items were already started, the writer is closed, or it broke
     * @throws IllegalArgumentException when the parent was not written before (nothing is written)
     * @throws UncheckedIOException when writing fails (the writer is then broken)
     */
    public void term(WxrTerm t) {
        Objects.requireNonNull(t, "term");
        checkOrder(Phase.TERMS);
        String taxonomy = t.taxonomy() == null ? "" : t.taxonomy();
        boolean navMenu = "nav_menu".equals(t.taxonomy());
        if (!navMenu) {
            checkParent("term", t.slug(), t.parent(), termParents.getOrDefault(taxonomy, Set.of()));
        }
        write(Phase.TERMS, () -> writeTerm(t, navMenu));
        remember(termParents.computeIfAbsent(taxonomy, k -> new HashSet<>()), t.slug(), t.name());
    }

    private void writeTerm(WxrTerm t, boolean navMenu) {
        indent(1);
        if (navMenu) {
            buf.append("<wp:term>");
            inlineInteger("wp:term_id", t.termId(), t.rawFields());
            buf.append("<wp:term_taxonomy>nav_menu</wp:term_taxonomy>");
            inlineCdata("wp:term_slug", t.slug());
            if (t.name() != null && !t.name().isEmpty()) {
                inlineCdata("wp:term_name", t.name());
            }
            buf.append("</wp:term>\n");
        } else {
            buf.append("<wp:term>\n");
            integer(2, "wp:term_id", t.termId(), t.rawFields());
            cdata(2, "wp:term_taxonomy", t.taxonomy());
            cdata(2, "wp:term_slug", t.slug());
            cdata(2, "wp:term_parent", t.parent());
            optionalCdata(2, "wp:term_name", t.name());
            optionalCdata(2, "wp:term_description", t.description());
            meta(2, "wp:termmeta", t.termMeta());
            indent(1);
            buf.append("</wp:term>\n");
        }
    }

    /**
     * Writes an item (a post, page, attachment, nav menu item or any other post type) with its terms, post meta and
     * comments, after the terms.
     *
     * @throws IllegalStateException when the writer is closed or broke
     * @throws UncheckedIOException when writing fails (the writer is then broken)
     */
    public void item(WxrItem i) {
        Objects.requireNonNull(i, "item");
        checkOrder(Phase.ITEMS);
        write(Phase.ITEMS, () -> writeItem(i));
    }

    private void writeItem(WxrItem i) {
        Map<String, String> raw = i.rawFields();
        indent(1);
        buf.append("<item>\n");
        cdata(2, "title", i.title());
        text(2, "link", i.link());
        text(2, "pubDate", i.pubDate());
        cdata(2, "dc:creator", i.creator());
        if (i.guid() != null || i.guidIsPermaLink() != null) {
            indent(2);
            buf.append("<guid");
            if (i.guidIsPermaLink() != null) {
                buf.append(" isPermaLink=\"").append(i.guidIsPermaLink()).append('"');
            }
            buf.append('>');
            if (i.guid() != null) {
                XmlText.text(buf, i.guid());
            }
            buf.append("</guid>\n");
        }
        text(2, "description", i.description());
        cdata(2, "content:encoded", i.content());
        cdata(2, "excerpt:encoded", i.excerpt());
        integer(2, "wp:post_id", i.postId(), raw);
        cdata(2, "wp:post_date", i.postDate());
        cdata(2, "wp:post_date_gmt", i.postDateGmt());
        cdata(2, "wp:post_modified", i.postModified());
        cdata(2, "wp:post_modified_gmt", i.postModifiedGmt());
        cdata(2, "wp:comment_status", i.commentStatus());
        cdata(2, "wp:ping_status", i.pingStatus());
        cdata(2, "wp:post_name", i.postName());
        cdata(2, "wp:status", i.status());
        integer(2, "wp:post_parent", i.postParent(), raw);
        integer(2, "wp:menu_order", i.menuOrder(), raw);
        cdata(2, "wp:post_type", i.postType());
        cdata(2, "wp:post_password", i.postPassword());
        integer(2, "wp:is_sticky", i.isSticky(), raw);
        if ("attachment".equals(i.postType())) {
            cdata(2, "wp:attachment_url", i.attachmentUrl());
        }
        for (WxrItemTerm t : i.terms()) {
            indent(2);
            buf.append("<category");
            if (t.domain() != null) {
                buf.append(" domain=\"");
                XmlText.attribute(buf, t.domain());
                buf.append('"');
            }
            if (t.nicename() != null) {
                buf.append(" nicename=\"");
                XmlText.attribute(buf, t.nicename());
                buf.append('"');
            }
            buf.append('>');
            XmlText.cdata(buf, t.name());
            buf.append("</category>\n");
        }
        meta(2, "wp:postmeta", i.postMeta());
        for (WxrComment c : i.comments()) {
            comment(c);
        }
        indent(1);
        buf.append("</item>\n");
    }

    /**
     * Ends the channel and the document and closes the stream; later calls do nothing. A broken writer only closes
     * the stream: what the writers in front of it still hold is dropped, so that nothing is written twice, and the
     * document stays unfinished. The stream is closed also when writing the end fails.
     *
     * @throws UncheckedIOException when writing the end or closing the stream fails
     */
    @Override
    public void close() {
        if (phase == Phase.CLOSED) {
            return;
        }
        Phase was = phase;
        phase = Phase.CLOSED;
        RuntimeException failure = null;
        try {
            if (!broken) {
                if (was != Phase.ITEMS) {
                    channelTail();
                }
                buf.append("</channel>\n</rss>\n");
                flush();
                out.flush();
            }
        } catch (IOException e) {
            broken = true;
            failure = new UncheckedIOException(e);
        } catch (RuntimeException e) {
            broken = true;
            failure = e;
        } finally {
            buf.setLength(0);
            try {
                // the stream itself: the writers above it may hold chars of a failed entry, which must not follow
                sink.close();
            } catch (IOException e) {
                if (failure == null) {
                    failure = new UncheckedIOException(e);
                } else {
                    failure.addSuppressed(e);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    // ---- structure ----

    private void header(Clock clock) {
        buf.append("<?xml version=\"1.0\" encoding=\"UTF-8\" ?>\n")
                .append("<rss version=\"2.0\"\n")
                .append("\txmlns:excerpt=\"")
                .append(WxrNames.EXCERPT_1_2)
                .append("\"\n")
                .append("\txmlns:content=\"")
                .append(WxrNames.CONTENT)
                .append("\"\n")
                .append("\txmlns:wfw=\"")
                .append(WxrNames.WFW)
                .append("\"\n")
                .append("\txmlns:dc=\"")
                .append(WxrNames.DC)
                .append("\"\n")
                .append("\txmlns:wp=\"")
                .append(WxrNames.WP_1_2)
                .append("\"\n")
                .append(">\n\n<channel>\n");
        text(1, "title", site.title());
        text(1, "link", site.link());
        text(1, "description", site.description());
        text(1, "pubDate", site.pubDate() != null ? site.pubDate() : RFC_822.format(clock.instant()));
        text(1, "language", site.language());
        text(1, "wp:wxr_version", WXR_VERSION);
        text(1, "wp:base_site_url", site.baseSiteUrl());
        text(1, "wp:base_blog_url", site.baseBlogUrl());
        buf.append('\n');
        flush();
    }

    /** What WordPress writes between the terms and the items: the generator. */
    private void channelTail() {
        text(1, "generator", site.generator());
        buf.append('\n');
    }

    /** Refuses an entry that may not be written now, before anything is touched. */
    private void checkOrder(Phase target) {
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException("the writer is closed");
        }
        if (broken) {
            throw new IllegalStateException("an earlier entry failed; the writer is broken and only closes the stream");
        }
        if (phase.compareTo(target) > 0) {
            throw new IllegalStateException(target.label + " must be written before " + phase.label);
        }
    }

    /**
     * Enters {@code target}'s section, buffers an entry with {@code body} and writes it. Any failure breaks the
     * writer: the buffered rest is dropped and nothing more is written.
     */
    private void write(Phase target, Runnable body) {
        try {
            if (target == Phase.ITEMS && phase != Phase.ITEMS) {
                channelTail();
            }
            phase = target;
            body.run();
            flush();
        } catch (RuntimeException | Error e) {
            broken = true;
            buf.setLength(0);
            throw e;
        }
    }

    private static void checkParent(String what, String slug, String parent, Set<String> written) {
        if (parent != null && !parent.isEmpty() && !written.contains(parent)) {
            throw new IllegalArgumentException(what + " '" + Texts.clip(slug) + "' names parent '" + Texts.clip(parent)
                    + "', which was not written before it (WXR lists parents first)");
        }
    }

    private static void remember(Set<String> written, String slug, String name) {
        if (slug != null) {
            written.add(slug);
        }
        if (name != null) {
            written.add(name);
        }
    }

    private void comment(WxrComment c) {
        Map<String, String> raw = c.rawFields();
        indent(2);
        buf.append("<wp:comment>\n");
        integer(3, "wp:comment_id", c.id(), raw);
        cdata(3, "wp:comment_author", c.author());
        cdata(3, "wp:comment_author_email", c.authorEmail());
        text(3, "wp:comment_author_url", c.authorUrl());
        cdata(3, "wp:comment_author_IP", c.authorIp());
        cdata(3, "wp:comment_date", c.date());
        cdata(3, "wp:comment_date_gmt", c.dateGmt());
        cdata(3, "wp:comment_content", c.content());
        cdata(3, "wp:comment_approved", c.approved());
        cdata(3, "wp:comment_type", c.type());
        integer(3, "wp:comment_parent", c.parent(), raw);
        integer(3, "wp:comment_user_id", c.userId(), raw);
        meta(3, "wp:commentmeta", c.commentMeta());
        indent(2);
        buf.append("</wp:comment>\n");
    }

    private void meta(int depth, String element, List<WxrMeta> rows) {
        for (WxrMeta m : rows) {
            indent(depth);
            buf.append('<').append(element).append(">\n");
            cdata(depth + 1, "wp:meta_key", m.key());
            cdata(depth + 1, "wp:meta_value", m.value());
            indent(depth);
            buf.append("</").append(element).append(">\n");
        }
    }

    // ---- elements ----

    private void indent(int depth) {
        for (int i = 0; i < depth; i++) {
            buf.append('\t');
        }
    }

    private void text(int depth, String name, String value) {
        if (value == null) {
            return;
        }
        indent(depth);
        buf.append('<').append(name).append('>');
        XmlText.text(buf, value);
        buf.append("</").append(name).append(">\n");
    }

    private void cdata(int depth, String name, String value) {
        if (value == null) {
            return;
        }
        indent(depth);
        inlineCdata(name, value);
        buf.append('\n');
    }

    private void optionalCdata(int depth, String name, String value) {
        if (value != null && !value.isEmpty()) {
            cdata(depth, name, value);
        }
    }

    private void integer(int depth, String name, Long value, Map<String, String> raw) {
        text(depth, name, value != null ? value.toString() : raw.get(name));
    }

    private void inlineCdata(String name, String value) {
        if (value == null) {
            return;
        }
        buf.append('<').append(name).append('>');
        XmlText.cdata(buf, value);
        buf.append("</").append(name).append('>');
    }

    private void inlineInteger(String name, Long value, Map<String, String> raw) {
        String text = value != null ? value.toString() : raw.get(name);
        if (text != null) {
            buf.append('<').append(name).append('>');
            XmlText.text(buf, text);
            buf.append("</").append(name).append('>');
        }
    }

    /**
     * Hands the buffered entry to the stream in chunks, so a huge entry is never copied whole. The buffer is emptied
     * also when writing fails, so that nothing is written twice.
     */
    private void flush() {
        try {
            int n = buf.length();
            for (int at = 0; at < n; at += chunk.length) {
                int end = Math.min(n, at + chunk.length);
                buf.getChars(at, end, chunk, 0);
                out.write(chunk, 0, end - at);
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            buf.setLength(0);
            if (buf.capacity() > RETAINED_CAPACITY) {
                buf.trimToSize();
            }
        }
    }
}
