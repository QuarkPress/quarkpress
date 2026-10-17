package org.quarkpress.lib.wxr;

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
