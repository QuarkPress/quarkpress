package org.quarkpress.lib.wxr;

import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Writes with {@link WxrWriter} what export_wp() writes for a full export of a parity dataset (the JSON form of
 * the harness fixtures). The selection and ordering export_wp() does are applied here, as a caller would.
 */
final class ExportDataset {

    static final String ZERO_DATE = "0000-00-00 00:00:00";
    private static final DateTimeFormatter MYSQL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter RFC_822 =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss '+0000'", Locale.ENGLISH);

    private ExportDataset() {}

    static byte[] write(JsonNode d) {
        JsonNode site = d.get("site");
        String url = site.get("url").asText();
        WxrSite wxrSite = new WxrSite(
                site.get("name").asText(),
                url,
                site.get("description").asText(),
                "Thu, 01 Jan 2026 00:00:00 +0000",
                site.get("language").asText(),
                null,
                url,
                url,
                "https://wordpress.org/?v=7.1.3");
        List<JsonNode> posts = exportedPosts(d);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (WxrWriter w = WxrWriter.open(out, wxrSite)) {
            for (JsonNode u : authors(d, posts)) {
                w.author(new WxrAuthor(
                        u.get("ID").asLong(),
                        text(u, "user_login"),
                        text(u, "user_email"),
                        text(u, "display_name"),
                        text(u, "first_name"),
                        text(u, "last_name")));
            }
            List<JsonNode> categories = parentsFirst(list(d.get("categories")));
            Map<Long, String> categorySlugs = slugs(categories);
            for (JsonNode c : categories) {
                w.category(new WxrCategory(
                        c.get("term_id").asLong(),
                        text(c, "slug"),
                        parentSlug(c, categorySlugs),
                        text(c, "name"),
                        text(c, "description"),
                        termMeta(d, c)));
            }
            for (JsonNode t : list(d.get("tags"))) {
                w.tag(new WxrTag(
                        t.get("term_id").asLong(),
                        text(t, "slug"),
                        text(t, "name"),
                        text(t, "description"),
                        termMeta(d, t)));
            }
            List<JsonNode> terms = parentsFirst(list(d.get("terms")));
            Map<Long, String> termSlugs = slugs(terms);
            for (JsonNode t : terms) {
                w.term(new WxrTerm(
                        t.get("term_id").asLong(),
                        text(t, "taxonomy"),
                        text(t, "slug"),
                        parentSlug(t, termSlugs),
                        text(t, "name"),
                        text(t, "description"),
                        termMeta(d, t)));
            }
            for (JsonNode m : list(d.get("nav_menus"))) {
                w.term(new WxrTerm(
                        m.get("term_id").asLong(),
                        "nav_menu",
                        text(m, "slug"),
                        null,
                        text(m, "name"),
                        null,
                        List.of()));
            }
            for (JsonNode p : posts) {
                w.item(item(d, p));
            }
        }
        return out.toByteArray();
    }

    /** R17: exportable post types, no auto-drafts, in dataset (database) order. */
    private static List<JsonNode> exportedPosts(JsonNode d) {
        List<JsonNode> posts = new ArrayList<>();
        for (JsonNode p : list(d.get("posts"))) {
            boolean exportable = d.path("post_types").path(text(p, "post_type")).asBoolean(false);
            if (exportable && !"auto-draft".equals(text(p, "post_status"))) {
                posts.add(p);
            }
        }
        return posts;
    }

    /** R9: authors of the exported posts, first-seen order, users that exist. */
    private static List<JsonNode> authors(JsonNode d, List<JsonNode> posts) {
        Set<String> ids = new LinkedHashSet<>();
        posts.forEach(p -> ids.add(p.get("post_author").asText()));
        List<JsonNode> users = new ArrayList<>();
        for (String id : ids) {
            JsonNode user = d.path("users").path(id);
            if (!user.isMissingNode()) {
                users.add(user);
            }
        }
        return users;
    }

    /** R14: take terms from the front; keep one whose parent is kept, else move it to the back. */
    private static List<JsonNode> parentsFirst(List<JsonNode> terms) {
        Deque<JsonNode> queue = new ArrayDeque<>(terms);
        Map<Long, JsonNode> kept = new LinkedHashMap<>();
        while (!queue.isEmpty()) {
            JsonNode t = queue.removeFirst();
            long parent = t.path("parent").asLong(0);
            if (parent == 0 || kept.containsKey(parent)) {
                kept.put(t.get("term_id").asLong(), t);
            } else {
                queue.addLast(t);
            }
        }
        return new ArrayList<>(kept.values());
    }

    private static Map<Long, String> slugs(List<JsonNode> terms) {
        Map<Long, String> slugs = new LinkedHashMap<>();
        terms.forEach(t -> slugs.put(t.get("term_id").asLong(), text(t, "slug")));
        return slugs;
    }

    private static String parentSlug(JsonNode term, Map<Long, String> slugs) {
        long parent = term.path("parent").asLong(0);
        return parent == 0 ? "" : slugs.get(parent);
    }

    private static List<WxrMeta> termMeta(JsonNode d, JsonNode term) {
        return meta(d.path("termmeta").path(term.get("term_id").asText()), false);
    }

    /** Meta rows; R45: export_wp skips _edit_lock (for post meta). */
    private static List<WxrMeta> meta(JsonNode rows, boolean skipEditLock) {
        List<WxrMeta> meta = new ArrayList<>();
        for (JsonNode m : list(rows)) {
            if (!(skipEditLock && "_edit_lock".equals(text(m, "meta_key")))) {
                meta.add(new WxrMeta(text(m, "meta_key"), text(m, "meta_value")));
            }
        }
        return meta;
    }

    private static WxrItem item(JsonNode d, JsonNode p) {
        String id = p.get("ID").asText();
        String type = text(p, "post_type");
        List<WxrItemTerm> terms = new ArrayList<>();
        JsonNode taxonomies = d.path("object_taxonomies").path(type);
        if (taxonomies.isArray() && !taxonomies.isEmpty()) {
            for (JsonNode r : list(d.path("relationships").path(id))) {
                terms.add(new WxrItemTerm(text(r, "taxonomy"), text(r, "slug"), text(r, "name")));
            }
        }
        List<WxrComment> comments = new ArrayList<>();
        for (JsonNode c : list(d.path("comments").path(id))) {
            if ("spam".equals(text(c, "comment_approved"))) {
                continue; // R47
            }
            comments.add(WxrComment.builder()
                    .id(c.get("comment_ID").asLong())
                    .author(text(c, "comment_author"))
                    .authorEmail(text(c, "comment_author_email"))
                    .authorUrl(text(c, "comment_author_url"))
                    .authorIp(text(c, "comment_author_IP"))
                    .date(text(c, "comment_date"))
                    .dateGmt(text(c, "comment_date_gmt"))
                    .content(text(c, "comment_content"))
                    .approved(text(c, "comment_approved"))
                    .type(text(c, "comment_type"))
                    .parent(c.get("comment_parent").asLong())
                    .userId(c.get("user_id").asLong())
                    .commentMeta(
                            meta(d.path("commentmeta").path(c.get("comment_ID").asText()), false))
                    .build());
        }
        JsonNode user = d.path("users").path(p.get("post_author").asText());
        boolean sticky = false;
        for (JsonNode s : d.path("sticky")) {
            sticky |= s.asLong() == p.get("ID").asLong();
        }
        return WxrItem.builder()
                .title(text(p, "post_title"))
                .link(d.path("permalinks").path(id).asText())
                .pubDate(rfc822(text(p, "post_date_gmt")))
                .creator(user.isMissingNode() ? "" : text(user, "user_login"))
                .guid(text(p, "guid"))
                .guidIsPermaLink(false)
                .description("")
                .content(text(p, "post_content"))
                .excerpt(text(p, "post_excerpt"))
                .postId(p.get("ID").asLong())
                .postDate(text(p, "post_date"))
                .postDateGmt(text(p, "post_date_gmt"))
                .postModified(text(p, "post_modified"))
                .postModifiedGmt(text(p, "post_modified_gmt"))
                .commentStatus(text(p, "comment_status"))
                .pingStatus(text(p, "ping_status"))
                .postName(text(p, "post_name"))
                .status(text(p, "post_status"))
                .postParent(p.get("post_parent").asLong())
                .menuOrder(p.get("menu_order").asLong())
                .postType(type)
                .postPassword(text(p, "post_password"))
                .isSticky(sticky ? 1L : 0L)
                .attachmentUrl(
                        "attachment".equals(type)
                                ? d.path("attachment_urls").path(id).asText()
                                : null)
                .terms(terms)
                .postMeta(meta(d.path("postmeta").path(id), true))
                .comments(comments)
                .build();
    }

    /** R16: the GMT publish time, empty for the zero date. */
    private static String rfc822(String gmt) {
        if (gmt == null || ZERO_DATE.equals(gmt)) {
            return "";
        }
        return LocalDateTime.parse(gmt, MYSQL).format(RFC_822);
    }

    private static List<JsonNode> list(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(out::add);
        }
        return out;
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }
}
