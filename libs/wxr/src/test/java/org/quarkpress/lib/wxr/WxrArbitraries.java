package org.quarkpress.lib.wxr;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;

/** Generated WXR documents for property and parity tests (test-gen; jqwik is not used). */
final class WxrArbitraries {

    private static final List<String> TRICKY = List.of(
            "]]>",
            "a]]>b",
            "]]]]>",
            "]]",
            "&",
            "&amp;",
            "&#038;",
            "<p>x</p>",
            "\"'",
            "\u0001\u000B",
            "\u0000",
            " lead",
            "trail ",
            "\t\n",
            "\uFFFE",
            "\uD83D\uDE00",
            "\u00E9",
            "&nbsp;",
            "<!-- c -->");

    private WxrArbitraries() {}

    record Document(
            WxrSite site,
            List<WxrAuthor> authors,
            List<WxrCategory> categories,
            List<WxrTag> tags,
            List<WxrTerm> terms,
            List<WxrItem> items) {

        byte[] write() {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (WxrWriter writer = WxrWriter.open(out, site)) {
                authors.forEach(writer::author);
                categories.forEach(writer::category);
                tags.forEach(writer::tag);
                terms.forEach(writer::term);
                items.forEach(writer::item);
            }
            return out.toByteArray();
        }

        List<WxrEntry> entries() {
            List<WxrEntry> all = new ArrayList<>();
            all.addAll(authors);
            all.addAll(categories);
            all.addAll(tags);
            all.addAll(terms);
            all.addAll(items);
            return all;
        }

        /** What a reader returns for this document: XML-illegal chars as U+FFFD, version 1.2. */
        Document sanitized() {
            WxrSite s = new WxrSite(
                    clean(site.title()),
                    clean(site.link()),
                    clean(site.description()),
                    clean(site.pubDate()),
                    clean(site.language()),
                    WxrWriter.WXR_VERSION,
                    clean(site.baseSiteUrl()),
                    clean(site.baseBlogUrl()),
                    clean(site.generator()));
            return new Document(
                    s,
                    authors.stream()
                            .map(a -> new WxrAuthor(
                                    a.id(),
                                    clean(a.login()),
                                    clean(a.email()),
                                    clean(a.displayName()),
                                    clean(a.firstName()),
                                    clean(a.lastName())))
                            .toList(),
                    categories.stream()
                            .map(c -> new WxrCategory(
                                    c.termId(),
                                    clean(c.nicename()),
                                    clean(c.parent()),
                                    clean(c.name()),
                                    clean(c.description()),
                                    clean(c.termMeta())))
                            .toList(),
                    tags.stream()
                            .map(t -> new WxrTag(
                                    t.termId(),
                                    clean(t.slug()),
                                    clean(t.name()),
                                    clean(t.description()),
                                    clean(t.termMeta())))
                            .toList(),
                    terms.stream()
                            .map(t -> new WxrTerm(
                                    t.termId(),
                                    clean(t.taxonomy()),
                                    clean(t.slug()),
                                    clean(t.parent()),
                                    clean(t.name()),
                                    clean(t.description()),
                                    clean(t.termMeta())))
                            .toList(),
                    items.stream().map(WxrArbitraries::clean).toList());
        }
    }

    // ---- text ----

    /** Text with markup-like pieces, `]]>`, references, control chars and non-ASCII; CR only if asked. */
    static Gen<String> text(boolean carriageReturns) {
        List<String> tricky = new ArrayList<>(TRICKY);
        if (carriageReturns) {
            tricky.add("\r\n");
            tricky.add("\r");
        }
        Gen<Character> chars =
                carriageReturns ? Gens.unicodeChars() : Gens.unicodeChars().filter(c -> c != '\r');
        Gen<String> piece = Gens.frequency(
                Gens.weighted(5, Gens.strings("abc xyz-_.", 0, 12)),
                Gens.weighted(2, Gens.strings(chars, 0, 8)),
                Gens.weighted(3, Gens.elements(tricky)));
        return Gens.lists(piece, 0, 4).map(parts -> String.join("", parts));
    }

    private static final Gen<String> SLUG_CHARS = Gens.strings("abcdefghijklmnopqrstuvwxyz0123456789-", 1, 8);

    // ---- documents ----

    static Gen<Document> documents(boolean carriageReturns) {
        Gen<String> text = text(carriageReturns);
        Gen<Long> number = Gens.longs(-5, 1_000_000);
        return random -> {
            WxrSite site = new WxrSite(
                    text.next(random),
                    text.next(random),
                    text.next(random),
                    text.next(random),
                    text.next(random),
                    null,
                    text.next(random),
                    text.next(random),
                    random.nextBoolean() ? null : text.next(random));
            List<WxrAuthor> authors = new ArrayList<>();
            for (int i = random.nextInt(3); i > 0; i--) {
                authors.add(new WxrAuthor(
                        number.next(random),
                        "user" + authors.size() + SLUG_CHARS.next(random),
                        text.next(random),
                        text.next(random),
                        text.next(random),
                        text.next(random)));
            }
            List<WxrCategory> categories = new ArrayList<>();
            for (int i = random.nextInt(4); i > 0; i--) {
                String parent = categories.isEmpty() || random.nextBoolean()
                        ? ""
                        : categories.get(random.nextInt(categories.size())).nicename();
                categories.add(new WxrCategory(
                        number.next(random),
                        "cat" + categories.size(),
                        parent,
                        optional(text, random),
                        optional(text, random),
                        meta(text, random)));
            }
            List<WxrTag> tags = new ArrayList<>();
            for (int i = random.nextInt(3); i > 0; i--) {
                tags.add(new WxrTag(
                        number.next(random),
                        "tag" + tags.size(),
                        optional(text, random),
                        optional(text, random),
                        meta(text, random)));
            }
            List<WxrTerm> terms = new ArrayList<>();
            for (int i = random.nextInt(3); i > 0; i--) {
                String parent = terms.isEmpty() || random.nextBoolean()
                        ? ""
                        : terms.get(random.nextInt(terms.size())).slug();
                terms.add(new WxrTerm(
                        number.next(random),
                        "genre",
                        "term" + terms.size(),
                        parent,
                        optional(text, random),
                        optional(text, random),
                        meta(text, random)));
            }
            if (random.nextInt(4) == 0) {
                terms.add(new WxrTerm(
                        number.next(random),
                        "nav_menu",
                        "menu",
                        null,
                        "Menu " + SLUG_CHARS.next(random),
                        null,
                        List.of()));
            }
            List<WxrItem> items = new ArrayList<>();
            for (int i = random.nextInt(4); i > 0; i--) {
                items.add(item(text, number, random));
            }
            return new Document(site, authors, categories, tags, terms, items);
        };
    }

    private static WxrItem item(Gen<String> text, Gen<Long> number, RandomGenerator random) {
        String type = Gens.elements("post", "page", "attachment").next(random);
        List<WxrItemTerm> terms = new ArrayList<>();
        for (int i = random.nextInt(4); i > 0; i--) {
            String domain =
                    Gens.elements("category", "post_tag", "tag", "product_cat").next(random);
            boolean bare = random.nextInt(5) == 0;
            terms.add(new WxrItemTerm(bare ? null : domain, bare ? null : SLUG_CHARS.next(random), text.next(random)));
        }
        List<WxrComment> comments = new ArrayList<>();
        for (int i = random.nextInt(3); i > 0; i--) {
            comments.add(WxrComment.builder()
                    .id(number.next(random))
                    .author(text.next(random))
                    .authorEmail(text.next(random))
                    .authorUrl(text.next(random))
                    .authorIp(text.next(random))
                    .date(text.next(random))
                    .dateGmt(text.next(random))
                    .content(text.next(random))
                    .approved(text.next(random))
                    .type(text.next(random))
                    .parent(number.next(random))
                    .userId(number.next(random))
                    .commentMeta(meta(text, random))
                    .build());
        }
        return WxrItem.builder()
                .title(text.next(random))
                .link(text.next(random))
                .pubDate(text.next(random))
                .creator(text.next(random))
                .guid(text.next(random))
                .guidIsPermaLink(random.nextBoolean())
                .description(text.next(random))
                .content(text.next(random))
                .excerpt(text.next(random))
                .postId(number.next(random))
                .postDate(text.next(random))
                .postDateGmt(text.next(random))
                .postModified(text.next(random))
                .postModifiedGmt(text.next(random))
                .commentStatus(text.next(random))
                .pingStatus(text.next(random))
                .postName(text.next(random))
                .status(text.next(random))
                .postParent(number.next(random))
                .menuOrder(number.next(random))
                .postType(type)
                .postPassword(text.next(random))
                .isSticky(number.next(random))
                .attachmentUrl("attachment".equals(type) ? text.next(random) : null)
                .terms(terms)
                .postMeta(meta(text, random))
                .comments(comments)
                .build();
    }

    /** Null or a non-empty text: WordPress omits these elements when empty, so an empty value reads back null. */
    private static String optional(Gen<String> text, RandomGenerator random) {
        if (random.nextBoolean()) {
            return null;
        }
        String value = text.next(random);
        return value.isEmpty() ? "x" + value : value;
    }

    private static List<WxrMeta> meta(Gen<String> text, RandomGenerator random) {
        List<WxrMeta> rows = new ArrayList<>();
        for (int i = random.nextInt(3); i > 0; i--) {
            rows.add(new WxrMeta(text.next(random), text.next(random)));
        }
        return rows;
    }

    // ---- export datasets ----

    /**
     * Datasets for export_wp() in the JSON form of the harness fixtures. Values WordPress transforms (site
     * name through display filters, URLs through esc_url, "0" names through empty()) are drawn from sets it
     * leaves alone; text is XML-legal and has no CR (those differences are D8 and D10, covered by hand-written
     * datasets).
     */
    static Gen<ObjectNode> exportDatasets() {
        Gen<String> text = text(false).map(XmlText::sanitize);
        Gen<String> name = text.filter(s -> !"0".equals(s));
        Gen<String> plain = Gens.strings("abcdefghij ABC", 0, 12);
        Gen<String> date = Gens.elements("2026-01-02 10:00:00", "2025-12-31 23:59:59", "2010-06-30 06:00:00");
        JsonNodeFactory f = JsonNodeFactory.instance;
        return random -> {
            ObjectNode d = f.objectNode();
            d.put("charset", "UTF-8");
            d.putObject("site")
                    .put("url", "https://example.test")
                    .put("name", plain.next(random))
                    .put("description", plain.next(random))
                    .put("language", "en-US");
            d.putObject("post_types")
                    .put("post", true)
                    .put("page", true)
                    .put("attachment", true)
                    .put("revision", false);
            int userCount = 1 + random.nextInt(2);
            ObjectNode users = d.putObject("users");
            for (int u = 1; u <= userCount; u++) {
                users.putObject(Integer.toString(u))
                        .put("ID", u)
                        .put("user_login", "user" + u)
                        .put("user_email", text.next(random))
                        .put("display_name", text.next(random))
                        .put("first_name", text.next(random))
                        .put("last_name", text.next(random));
            }
            ArrayNode categories = hierarchy(f, random, "category", "cat", 2, name);
            d.set("categories", categories);
            ArrayNode tags = d.putArray("tags");
            int tagCount = random.nextInt(3);
            for (int t = 0; t < tagCount; t++) {
                tags.addObject()
                        .put("term_id", 40 + t)
                        .put("name", name.next(random))
                        .put("slug", "tag-" + t)
                        .put("parent", 0)
                        .put("description", name.next(random))
                        .put("taxonomy", "post_tag");
            }
            ArrayNode customTerms = hierarchy(f, random, "genre", "genre", 20, name);
            d.set("terms", customTerms);
            ArrayNode menus = d.putArray("nav_menus");
            if (random.nextBoolean()) {
                menus.addObject()
                        .put("term_id", 30)
                        .put("name", name.next(random))
                        .put("slug", "main")
                        .put("taxonomy", "nav_menu");
            }
            ObjectNode termmeta = d.putObject("termmeta");
            List<JsonNode> withMeta = new ArrayList<>();
            categories.forEach(withMeta::add);
            tags.forEach(withMeta::add);
            customTerms.forEach(withMeta::add);
            for (JsonNode c : withMeta) {
                if (random.nextInt(3) == 0) {
                    termmeta.putArray(c.get("term_id").asText())
                            .addObject()
                            .put("meta_key", text.next(random))
                            .put("meta_value", text.next(random));
                }
            }
            ObjectNode taxonomies = d.putObject("object_taxonomies");
            taxonomies.putArray("post").add("category").add("post_tag");
            taxonomies.putArray("page");
            taxonomies.putArray("attachment");
            ArrayNode posts = d.putArray("posts");
            ObjectNode permalinks = d.putObject("permalinks");
            ObjectNode attachmentUrls = d.putObject("attachment_urls");
            ArrayNode sticky = d.putArray("sticky");
            ObjectNode relationships = d.putObject("relationships");
            ObjectNode postmeta = d.putObject("postmeta");
            ObjectNode comments = d.putObject("comments");
            ObjectNode commentmeta = d.putObject("commentmeta");
            int postCount = 1 + random.nextInt(4);
            int commentId = 100;
            for (int i = 0; i < postCount; i++) {
                int id = 10 + i;
                String key = Integer.toString(id);
                String type = Gens.elements("post", "page", "attachment").next(random);
                posts.addObject()
                        .put("ID", id)
                        .put("post_author", 1 + random.nextInt(userCount + 1))
                        .put("post_date", date.next(random))
                        .put("post_date_gmt", random.nextInt(4) == 0 ? ExportDataset.ZERO_DATE : date.next(random))
                        .put("post_modified", date.next(random))
                        .put("post_modified_gmt", date.next(random))
                        .put("post_content", text.next(random))
                        .put("post_excerpt", text.next(random))
                        .put("post_title", text.next(random))
                        .put("comment_status", Gens.elements("open", "closed").next(random))
                        .put("ping_status", Gens.elements("open", "closed").next(random))
                        .put("post_name", text.next(random))
                        .put(
                                "post_status",
                                Gens.elements("publish", "draft", "private", "auto-draft", "inherit")
                                        .next(random))
                        .put("post_parent", random.nextInt(3) == 0 ? 10 : 0)
                        .put("menu_order", random.nextInt(5))
                        .put("post_type", type)
                        .put("post_password", text.next(random))
                        .put("guid", "https://example.test/?p=" + id);
                permalinks.put(key, "https://example.test/" + SLUG_CHARS.next(random) + "/");
                if (type.equals("attachment")) {
                    attachmentUrls.put(
                            key, "https://example.test/wp-content/uploads/" + SLUG_CHARS.next(random) + ".png");
                }
                if (random.nextInt(4) == 0) {
                    sticky.add(id);
                }
                ArrayNode rel = relationships.putArray(key);
                for (JsonNode term : categories) {
                    if (random.nextBoolean()) {
                        rel.addObject()
                                .put("taxonomy", "category")
                                .put("slug", term.get("slug").asText())
                                .put("name", term.get("name").asText());
                    }
                }
                for (JsonNode term : tags) {
                    if (random.nextBoolean()) {
                        rel.addObject()
                                .put("taxonomy", "post_tag")
                                .put("slug", term.get("slug").asText())
                                .put("name", term.get("name").asText());
                    }
                }
                ArrayNode meta = postmeta.putArray(key);
                if (random.nextInt(3) == 0) {
                    meta.addObject().put("meta_key", "_edit_lock").put("meta_value", "1767000000:1");
                }
                for (int m = random.nextInt(3); m > 0; m--) {
                    meta.addObject().put("meta_key", text.next(random)).put("meta_value", text.next(random));
                }
                ArrayNode postComments = comments.putArray(key);
                int first = commentId;
                for (int c = random.nextInt(3); c > 0; c--) {
                    int cid = commentId++;
                    postComments
                            .addObject()
                            .put("comment_ID", cid)
                            .put("comment_post_ID", id)
                            .put("comment_author", text.next(random))
                            .put("comment_author_email", text.next(random))
                            .put(
                                    "comment_author_url",
                                    Gens.elements(
                                                    "",
                                                    "https://c.test/" + SLUG_CHARS.next(random),
                                                    "https://c.test/?a=1&b=" + SLUG_CHARS.next(random))
                                            .next(random))
                            .put(
                                    "comment_author_IP",
                                    Gens.elements("127.0.0.1", "::1", "").next(random))
                            .put("comment_date", date.next(random))
                            .put("comment_date_gmt", date.next(random))
                            .put("comment_content", text.next(random))
                            .put(
                                    "comment_approved",
                                    Gens.elements("1", "0", "trash", "spam").next(random))
                            .put(
                                    "comment_type",
                                    Gens.elements("comment", "", "pingback").next(random))
                            .put("comment_parent", cid > first && random.nextBoolean() ? first : 0)
                            .put("user_id", random.nextInt(userCount + 1));
                    if (random.nextBoolean()) {
                        commentmeta
                                .putArray(Integer.toString(cid))
                                .addObject()
                                .put("meta_key", text.next(random))
                                .put("meta_value", text.next(random));
                    }
                }
            }
            return d;
        };
    }

    /** Up to three terms with ids from {@code firstId}; a parent always has a smaller id; listed shuffled. */
    private static ArrayNode hierarchy(
            JsonNodeFactory f,
            RandomGenerator random,
            String taxonomy,
            String slugPrefix,
            int firstId,
            Gen<String> name) {
        List<ObjectNode> terms = new ArrayList<>();
        int count = random.nextInt(4);
        for (int k = 0; k < count; k++) {
            int parent = k > 0 && random.nextBoolean() ? firstId + random.nextInt(k) : 0;
            terms.add(f.objectNode()
                    .put("term_id", firstId + k)
                    .put("name", name.next(random))
                    .put("slug", slugPrefix + "-" + k)
                    .put("parent", parent)
                    .put("description", name.next(random))
                    .put("taxonomy", taxonomy));
        }
        for (int i = terms.size() - 1; i > 0; i--) {
            int j = random.nextInt(i + 1);
            ObjectNode swap = terms.get(i);
            terms.set(i, terms.get(j));
            terms.set(j, swap);
        }
        ArrayNode out = f.arrayNode();
        terms.forEach(out::add);
        return out;
    }

    // ---- sanitizing ----

    private static String clean(String s) {
        return s == null ? null : XmlText.sanitize(s);
    }

    private static List<WxrMeta> clean(List<WxrMeta> rows) {
        return rows.stream()
                .map(m -> new WxrMeta(clean(m.key()), clean(m.value())))
                .toList();
    }

    private static WxrItem clean(WxrItem i) {
        return i.toBuilder()
                .title(clean(i.title()))
                .link(clean(i.link()))
                .pubDate(clean(i.pubDate()))
                .creator(clean(i.creator()))
                .guid(clean(i.guid()))
                .description(clean(i.description()))
                .content(clean(i.content()))
                .excerpt(clean(i.excerpt()))
                .postDate(clean(i.postDate()))
                .postDateGmt(clean(i.postDateGmt()))
                .postModified(clean(i.postModified()))
                .postModifiedGmt(clean(i.postModifiedGmt()))
                .commentStatus(clean(i.commentStatus()))
                .pingStatus(clean(i.pingStatus()))
                .postName(clean(i.postName()))
                .status(clean(i.status()))
                .postPassword(clean(i.postPassword()))
                .attachmentUrl(clean(i.attachmentUrl()))
                .terms(i.terms().stream()
                        .map(t -> new WxrItemTerm(t.domain(), t.nicename(), clean(t.name())))
                        .toList())
                .postMeta(clean(i.postMeta()))
                .comments(i.comments().stream()
                        .map(c -> c.toBuilder()
                                .author(clean(c.author()))
                                .authorEmail(clean(c.authorEmail()))
                                .authorUrl(clean(c.authorUrl()))
                                .authorIp(clean(c.authorIp()))
                                .date(clean(c.date()))
                                .dateGmt(clean(c.dateGmt()))
                                .content(clean(c.content()))
                                .approved(clean(c.approved()))
                                .type(clean(c.type()))
                                .commentMeta(clean(c.commentMeta()))
                                .build())
                        .toList())
                .build();
    }
}
