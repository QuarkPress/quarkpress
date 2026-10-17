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
 * data read from a WXR 1.0 file, by name. Everything else is the caller's policy: the writer does not skip or
 * reorder anything it is given.
 *
 * <p>Values are written as given; the site's {@code wxrVersion} is ignored, since the file is 1.2. A field that is
 * null is not written (WordPress always has every field; data read from a file may lack some), except that an
 * integer field that is null is written from its raw text in {@code rawFields()} when there is one, and the
 * channel {@code pubDate} falls back to the clock. Category, tag and term names and descriptions are omitted when
 * empty, as WordPress omits them. Memory: one entry. Not thread-safe; I/O errors surface as {@link
 * UncheckedIOException}.
 */
public final class WxrWriter implements AutoCloseable {

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

    private final Writer out;
    private final WxrSite site;
    private final StringBuilder buf = new StringBuilder(8192);
    private final char[] chunk = new char[8192];
    private final Set<String> categoryParents = new HashSet<>();
    private final Map<String, Set<String>> termParents = new HashMap<>();
    private Phase phase = Phase.AUTHORS;

    private WxrWriter(OutputStream out, WxrSite site, Clock clock) {
        this.out = new BufferedWriter(new OutputStreamWriter(out, StandardCharsets.UTF_8), 65_536);
        this.site = site;
        header(clock);
    }

    public static WxrWriter open(OutputStream out, WxrSite site) {
        return open(out, site, Clock.systemUTC());
    }

    /** {@code clock} dates the channel {@code pubDate} when the site has none. */
    public static WxrWriter open(OutputStream out, WxrSite site, Clock clock) {
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(clock, "clock");
        return new WxrWriter(out, site, clock);
    }

    public void author(WxrAuthor a) {
        Objects.requireNonNull(a, "author");
        advance(Phase.AUTHORS);
        indent(1);
        buf.append("<wp:author>");
        inlineInteger("wp:author_id", a.id(), a.rawFields());
        inlineCdata("wp:author_login", a.login());
        inlineCdata("wp:author_email", a.email());
        inlineCdata("wp:author_display_name", a.displayName());
        inlineCdata("wp:author_first_name", a.firstName());
        inlineCdata("wp:author_last_name", a.lastName());
        buf.append("</wp:author>\n");
        flush();
    }

    public void category(WxrCategory c) {
        Objects.requireNonNull(c, "category");
        advance(Phase.CATEGORIES);
        checkParent("category", c.nicename(), c.parent(), categoryParents);
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
        remember(categoryParents, c.nicename(), c.name());
        flush();
    }

    public void tag(WxrTag t) {
        Objects.requireNonNull(t, "tag");
        advance(Phase.TAGS);
        indent(1);
        buf.append("<wp:tag>\n");
        integer(2, "wp:term_id", t.termId(), t.rawFields());
        cdata(2, "wp:tag_slug", t.slug());
        optionalCdata(2, "wp:tag_name", t.name());
        optionalCdata(2, "wp:tag_description", t.description());
        meta(2, "wp:termmeta", t.termMeta());
        indent(1);
        buf.append("</wp:tag>\n");
        flush();
    }

    /**
     * A custom-taxonomy term, or a nav menu ({@code taxonomy} "nav_menu", written in WordPress's short form without
     * parent, description or term meta). A term's parent is looked up among the terms of its own taxonomy.
     */
    public void term(WxrTerm t) {
        Objects.requireNonNull(t, "term");
        advance(Phase.TERMS);
        Set<String> sameTaxonomy =
                termParents.computeIfAbsent(t.taxonomy() == null ? "" : t.taxonomy(), k -> new HashSet<>());
        indent(1);
        if ("nav_menu".equals(t.taxonomy())) {
            buf.append("<wp:term>");
            inlineInteger("wp:term_id", t.termId(), t.rawFields());
            buf.append("<wp:term_taxonomy>nav_menu</wp:term_taxonomy>");
            inlineCdata("wp:term_slug", t.slug());
            if (t.name() != null && !t.name().isEmpty()) {
                inlineCdata("wp:term_name", t.name());
            }
            buf.append("</wp:term>\n");
        } else {
            checkParent("term", t.slug(), t.parent(), sameTaxonomy);
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
        remember(sameTaxonomy, t.slug(), t.name());
        flush();
    }

    public void item(WxrItem i) {
        Objects.requireNonNull(i, "item");
        advance(Phase.ITEMS);
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
        flush();
    }

    /** Ends the channel and the document and closes the stream; later calls do nothing. */
    @Override
    public void close() {
        if (phase == Phase.CLOSED) {
            return;
        }
        if (phase != Phase.ITEMS) {
            channelTail();
        }
        phase = Phase.CLOSED;
        buf.append("</channel>\n</rss>\n");
        flush();
        try {
            out.close();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
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

    private void advance(Phase target) {
        if (phase == Phase.CLOSED) {
            throw new IllegalStateException("the writer is closed");
        }
        if (phase.compareTo(target) > 0) {
            throw new IllegalStateException(target.label + " must be written before " + phase.label);
        }
        if (target == Phase.ITEMS && phase != Phase.ITEMS) {
            channelTail();
        }
        phase = target;
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

    /** Hands the buffered entry to the stream in chunks, so a huge entry is never copied whole. */
    private void flush() {
        try {
            int n = buf.length();
            for (int at = 0; at < n; at += chunk.length) {
                int end = Math.min(n, at + chunk.length);
                buf.getChars(at, end, chunk, 0);
                out.write(chunk, 0, end - at);
            }
            buf.setLength(0);
            if (buf.capacity() > RETAINED_CAPACITY) {
                buf.trimToSize();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
