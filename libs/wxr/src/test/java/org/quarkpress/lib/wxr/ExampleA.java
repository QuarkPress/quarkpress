package org.quarkpress.lib.wxr;

import java.util.List;

/** The content of reference Example A (WordPress 7.1.3 export_wp output, harness fixture-small.php). */
final class ExampleA {

    static final WxrSite SITE = new WxrSite(
            "Example",
            "https://example.test",
            "A demo",
            "Sat, 17 Oct 2026 04:03:27 +0000",
            "en-US",
            "1.2",
            "https://example.test",
            "https://example.test",
            "https://wordpress.org/?v=7.1.3");

    static final WxrAuthor AUTHOR = new WxrAuthor(1L, "admin", "admin@example.test", "Ad Min", "Ad", "");

    static final WxrCategory CATEGORY = new WxrCategory(2L, "news", "", "News", null, List.of());

    static final WxrTag TAG = new WxrTag(5L, "php", "php", null, List.of());

    static final WxrComment COMMENT = WxrComment.builder()
            .id(100L)
            .author("Ann")
            .authorEmail("ann@example.test")
            .authorUrl("https://ann.test/")
            .authorIp("127.0.0.1")
            .date("2026-01-04 09:00:00")
            .dateGmt("2026-01-04 14:00:00")
            .content("Nice post")
            .approved("1")
            .type("comment")
            .parent(0L)
            .userId(0L)
            .commentMeta(List.of(new WxrMeta("rating", "5")))
            .build();

    static final WxrItem ITEM = WxrItem.builder()
            .title("Hello")
            .link("https://example.test/2026/01/hello-world/")
            .pubDate("Fri, 02 Jan 2026 15:00:00 +0000")
            .creator("admin")
            .guid("https://example.test/?p=10")
            .guidIsPermaLink(false)
            .description("")
            .content("<p>Use x[y[0]]> z</p>")
            .excerpt("")
            .postId(10L)
            .postDate("2026-01-02 10:00:00")
            .postDateGmt("2026-01-02 15:00:00")
            .postModified("2026-01-03 10:00:00")
            .postModifiedGmt("2026-01-03 15:00:00")
            .commentStatus("open")
            .pingStatus("open")
            .postName("hello-world")
            .status("publish")
            .postParent(0L)
            .menuOrder(0L)
            .postType("post")
            .postPassword("")
            .isSticky(0L)
            .terms(List.of(new WxrItemTerm("category", "news", "News"), new WxrItemTerm("post_tag", "php", "php")))
            .postMeta(List.of(new WxrMeta("colors", "a:1:{i:0;s:3:\"red\";}")))
            .comments(List.of(COMMENT))
            .build();

    private ExampleA() {}

    static List<WxrEntry> entries() {
        return List.of(AUTHOR, CATEGORY, TAG, ITEM);
    }
}
