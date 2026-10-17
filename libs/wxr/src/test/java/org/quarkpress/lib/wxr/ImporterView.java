package org.quarkpress.lib.wxr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One normal form for the importer's parse result (reference R51) and for what this library read: lists in
 * order, every listed field present as a string ("" when absent, as the regex and SimpleXML parsers give and
 * the XML-processor parser omits), version and base URLs trimmed as the parser does (SimpleXML trims them; the
 * XML-processor parser keeps them as written and matches its version pattern on the raw value). The library's side
 * applies what the importer's parsers do to values they could read: SimpleXML's (int) casts, the authors map
 * keyed per parser, category references without a nicename dropped, SimpleXML's base_blog_url fallback, and
 * a version problem becoming the importer's error.
 */
final class ImporterView {

    static final List<String> AUTHOR = List.of(
            "author_id",
            "author_login",
            "author_email",
            "author_display_name",
            "author_first_name",
            "author_last_name");
    static final List<String> CATEGORY =
            List.of("term_id", "category_nicename", "category_parent", "cat_name", "category_description");
    static final List<String> TAG = List.of("term_id", "tag_slug", "tag_name", "tag_description");
    static final List<String> TERM =
            List.of("term_id", "term_taxonomy", "slug", "term_parent", "term_name", "term_description");
    static final List<String> POST = List.of(
            "post_title",
            "guid",
            "post_author",
            "post_content",
            "post_excerpt",
            "post_id",
            "post_date",
            "post_date_gmt",
            "comment_status",
            "ping_status",
            "post_name",
            "status",
            "post_parent",
            "menu_order",
            "post_type",
            "post_password",
            "is_sticky",
            "attachment_url");
    static final List<String> MODIFIED = List.of("post_modified", "post_modified_gmt");
    static final List<String> COMMENT = List.of(
            "comment_id",
            "comment_author",
            "comment_author_email",
            "comment_author_url",
            "comment_author_IP",
            "comment_date",
            "comment_date_gmt",
            "comment_content",
            "comment_approved",
            "comment_type",
            "comment_parent",
            "comment_user_id");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private ImporterView() {}

    // ---- the importer's side ----

    static ObjectNode fromImporter(JsonNode out, boolean withModified) {
        ObjectNode view = NODES.objectNode();
        if (out.has("error")) {
            view.put("error", out.get("error").asText());
            return view;
        }
        JsonNode r = out.get("result");
        boolean trims = trims(out.path("parser").asText());
        view.put("version", trim(text(r.get("version")), trims));
        view.put("base_url", trim(text(r.get("base_url")), trims));
        view.put("base_blog_url", trim(text(r.get("base_blog_url")), trims));
        ArrayNode authors = view.putArray("authors");
        r.path("authors").forEach(a -> authors.add(fields(a, AUTHOR)));
        ArrayNode categories = view.putArray("categories");
        r.path("categories").forEach(c -> categories.add(withMeta(fields(c, CATEGORY), "termmeta", c)));
        ArrayNode tags = view.putArray("tags");
        r.path("tags").forEach(t -> tags.add(withMeta(fields(t, TAG), "termmeta", t)));
        ArrayNode terms = view.putArray("terms");
        r.path("terms").forEach(t -> terms.add(withMeta(fields(t, TERM), "termmeta", t)));
        ArrayNode posts = view.putArray("posts");
        for (JsonNode p : r.path("posts")) {
            ObjectNode post = fields(p, POST);
            if (withModified) {
                MODIFIED.forEach(k -> post.put(k, text(p.get(k))));
            }
            ArrayNode refs = post.putArray("terms");
            p.path("terms").forEach(t -> refs.add(fields(t, List.of("domain", "slug", "name"))));
            withMeta(post, "postmeta", p);
            ArrayNode comments = post.putArray("comments");
            p.path("comments").forEach(c -> comments.add(withMeta(fields(c, COMMENT), "commentmeta", c)));
            posts.add(post);
        }
        return view;
    }

    private static ObjectNode fields(JsonNode node, List<String> names) {
        ObjectNode o = NODES.objectNode();
        names.forEach(k -> o.put(k, text(node.get(k))));
        return o;
    }

    private static ObjectNode withMeta(ObjectNode into, String name, JsonNode from) {
        ArrayNode rows = into.putArray(name);
        from.path(name).forEach(m -> rows.add(fields(m, List.of("key", "value"))));
        return into;
    }

    private static String text(JsonNode n) {
        if (n == null || n.isNull() || n.isMissingNode()) {
            return "";
        }
        return n.isTextual() ? n.textValue() : n.asText();
    }

    // ---- the library's side ----

    static ObjectNode fromReader(TestWxr.Result r, String parser, boolean withModified) {
        ObjectNode view = NODES.objectNode();
        if (r.kinds().contains(WxrProblem.Kind.VERSION_MISSING)
                || r.kinds().contains(WxrProblem.Kind.VERSION_INVALID)) {
            view.put("error", "WXR_parse_error");
            return view;
        }
        boolean simplexml = "simplexml".equals(parser);
        boolean trims = trims(parser);
        WxrSite site = r.site();
        String base = trim(nz(site.baseSiteUrl()), trims);
        view.put("version", trim(nz(site.wxrVersion()), trims));
        view.put("base_url", base);
        // SimpleXML falls back to base_url when wp:base_blog_url is absent; the XML-processor parser leaves it empty
        // (checked on this host with the driver: field-after-meta.xml gives base_blog_url "").
        String blog = site.baseBlogUrl() == null ? (simplexml ? base : "") : trim(site.baseBlogUrl(), trims);
        view.put("base_blog_url", blog);
        Map<String, ObjectNode> authors = new LinkedHashMap<>();
        ArrayNode categories = NODES.arrayNode();
        ArrayNode tags = NODES.arrayNode();
        ArrayNode terms = NODES.arrayNode();
        ArrayNode posts = NODES.arrayNode();
        for (WxrEntry entry : r.entries()) {
            switch (entry) {
                case WxrAuthor a -> {
                    ObjectNode o = NODES.objectNode();
                    o.put("author_id", integer(a.rawFields().get("wp:author_id"), simplexml));
                    o.put("author_login", nz(a.login()));
                    o.put("author_email", nz(a.email()));
                    o.put("author_display_name", nz(a.displayName()));
                    o.put("author_first_name", nz(a.firstName()));
                    o.put("author_last_name", nz(a.lastName()));
                    authors.put(authorKey(a, simplexml, authors.size()), o);
                }
                case WxrCategory c -> {
                    ObjectNode o = NODES.objectNode();
                    o.put("term_id", termId(c.rawFields().get("wp:term_id"), simplexml));
                    o.put("category_nicename", nz(c.nicename()));
                    o.put("category_parent", nz(c.parent()));
                    o.put("cat_name", nz(c.name()));
                    o.put("category_description", nz(c.description()));
                    categories.add(meta(o, "termmeta", c.termMeta()));
                }
                case WxrTag t -> {
                    ObjectNode o = NODES.objectNode();
                    o.put("term_id", termId(t.rawFields().get("wp:term_id"), simplexml));
                    o.put("tag_slug", nz(t.slug()));
                    o.put("tag_name", nz(t.name()));
                    o.put("tag_description", nz(t.description()));
                    tags.add(meta(o, "termmeta", t.termMeta()));
                }
                case WxrTerm t -> {
                    ObjectNode o = NODES.objectNode();
                    o.put("term_id", termId(t.rawFields().get("wp:term_id"), simplexml));
                    o.put("term_taxonomy", nz(t.taxonomy()));
                    o.put("slug", nz(t.slug()));
                    o.put("term_parent", nz(t.parent()));
                    o.put("term_name", nz(t.name()));
                    o.put("term_description", nz(t.description()));
                    terms.add(meta(o, "termmeta", t.termMeta()));
                }
                case WxrItem i -> posts.add(post(i, simplexml, withModified));
            }
        }
        ArrayNode authorList = view.putArray("authors");
        authors.values().forEach(authorList::add);
        view.set("categories", categories);
        view.set("tags", tags);
        view.set("terms", terms);
        view.set("posts", posts);
        return view;
    }

    private static ObjectNode post(WxrItem i, boolean simplexml, boolean withModified) {
        Map<String, String> raw = i.rawFields();
        ObjectNode o = NODES.objectNode();
        o.put("post_title", nz(i.title()));
        o.put("guid", nz(i.guid()));
        o.put("post_author", nz(i.creator()));
        o.put("post_content", nz(i.content()));
        o.put("post_excerpt", nz(i.excerpt()));
        o.put("post_id", integer(raw.get("wp:post_id"), simplexml));
        o.put("post_date", nz(i.postDate()));
        o.put("post_date_gmt", nz(i.postDateGmt()));
        o.put("comment_status", nz(i.commentStatus()));
        o.put("ping_status", nz(i.pingStatus()));
        o.put("post_name", nz(i.postName()));
        o.put("status", nz(i.status()));
        o.put("post_parent", integer(raw.get("wp:post_parent"), simplexml));
        o.put("menu_order", integer(raw.get("wp:menu_order"), simplexml));
        o.put("post_type", nz(i.postType()));
        o.put("post_password", nz(i.postPassword()));
        o.put("is_sticky", integer(raw.get("wp:is_sticky"), simplexml));
        o.put("attachment_url", nz(i.attachmentUrl()));
        if (withModified) {
            o.put("post_modified", nz(i.postModified()));
            o.put("post_modified_gmt", nz(i.postModifiedGmt()));
        }
        ArrayNode refs = o.putArray("terms");
        for (WxrItemTerm t : i.terms()) {
            if (t.nicename() != null) { // R32: every parser ignores a category without nicename
                refs.addObject()
                        .put("domain", nz(t.domain()))
                        .put("slug", t.nicename())
                        .put("name", t.name());
            }
        }
        meta(o, "postmeta", i.postMeta());
        ArrayNode comments = o.putArray("comments");
        for (WxrComment c : i.comments()) {
            Map<String, String> craw = c.rawFields();
            ObjectNode co = NODES.objectNode();
            co.put("comment_id", integer(craw.get("wp:comment_id"), simplexml));
            co.put("comment_author", nz(c.author()));
            co.put("comment_author_email", nz(c.authorEmail()));
            co.put("comment_author_url", nz(c.authorUrl()));
            co.put("comment_author_IP", nz(c.authorIp()));
            co.put("comment_date", nz(c.date()));
            co.put("comment_date_gmt", nz(c.dateGmt()));
            co.put("comment_content", nz(c.content()));
            co.put("comment_approved", nz(c.approved()));
            co.put("comment_type", nz(c.type()));
            co.put("comment_parent", nz(craw.get("wp:comment_parent")));
            co.put("comment_user_id", integer(craw.get("wp:comment_user_id"), simplexml));
            comments.add(meta(co, "commentmeta", c.commentMeta()));
        }
        return o;
    }

    private static ObjectNode meta(ObjectNode into, String name, List<WxrMeta> rows) {
        ArrayNode list = into.putArray(name);
        rows.forEach(m -> list.addObject().put("key", nz(m.key())).put("value", nz(m.value())));
        return into;
    }

    /** SimpleXML keys authors by login; the XML-processor parser by login, else email, else id, else position. */
    private static String authorKey(WxrAuthor a, boolean simplexml, int position) {
        if (simplexml || a.login() != null) {
            return nz(a.login());
        }
        if (a.email() != null) {
            return a.email();
        }
        String id = a.rawFields().get("wp:author_id");
        return id != null ? id : Integer.toString(position);
    }

    /**
     * Both parsers turn term ids into ints (R52, R55); when absent, SimpleXML casts "" to 0 and the XML-processor
     * parser omits the field.
     */
    private static String termId(String raw, boolean simplexml) {
        if (raw == null) {
            return simplexml ? "0" : "";
        }
        return Long.toString(phpIntCast(raw));
    }

    private static String integer(String raw, boolean simplexml) {
        if (simplexml) {
            return Long.toString(phpIntCast(raw == null ? "" : raw));
        }
        return nz(raw);
    }

    /** PHP's (int) cast of a string: leading whitespace, optional sign, digits; 0 when none; saturating. */
    static long phpIntCast(String s) {
        int i = 0;
        while (i < s.length() && " \t\n\r\u000B\f".indexOf(s.charAt(i)) >= 0) {
            i++;
        }
        boolean negative = false;
        if (i < s.length() && (s.charAt(i) == '-' || s.charAt(i) == '+')) {
            negative = s.charAt(i) == '-';
            i++;
        }
        long value = 0;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') {
            int digit = s.charAt(i) - '0';
            if (value > (Long.MAX_VALUE - digit) / 10) {
                return negative ? Long.MIN_VALUE : Long.MAX_VALUE;
            }
            value = value * 10 + digit;
            i++;
        }
        return negative ? -value : value;
    }

    /**
     * The XML-processor parser keeps the version and base URLs untrimmed (checked with the driver on this host:
     * {@code <wp:base_site_url> http://s.test </wp:base_site_url>} gives {@code " http://s.test "}, and a version
     * written {@code " 1.2 "} is rejected); SimpleXML trims them.
     */
    private static boolean trims(String parser) {
        return !"xmlprocessor".equals(parser);
    }

    private static String trim(String s, boolean trims) {
        return trims ? Texts.phpTrim(s) : s;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
