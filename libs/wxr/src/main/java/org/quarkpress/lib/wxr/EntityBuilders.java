package org.quarkpress.lib.wxr;

import java.util.ArrayList;
import java.util.List;

/** Turns collected {@link Fields} into model records. */
final class EntityBuilders {

    private EntityBuilders() {}

    static WxrEntry build(String name, Fields f) {
        return switch (name) {
            case "wp:author" ->
                new WxrAuthor(
                        f.integer("wp:author_id"),
                        f.get("wp:author_login"),
                        f.get("wp:author_email"),
                        f.get("wp:author_display_name"),
                        f.get("wp:author_first_name"),
                        f.get("wp:author_last_name"),
                        f.values);
            case "wp:category" ->
                new WxrCategory(
                        f.integer("wp:term_id"),
                        f.get("wp:category_nicename"),
                        f.get("wp:category_parent"),
                        f.get("wp:cat_name"),
                        f.get("wp:category_description"),
                        f.meta,
                        f.values);
            case "wp:tag" ->
                new WxrTag(
                        f.integer("wp:term_id"),
                        f.get("wp:tag_slug"),
                        f.get("wp:tag_name"),
                        f.get("wp:tag_description"),
                        f.meta,
                        f.values);
            case "wp:term" ->
                new WxrTerm(
                        f.integer("wp:term_id"),
                        f.get("wp:term_taxonomy"),
                        f.get("wp:term_slug"),
                        f.get("wp:term_parent"),
                        f.get("wp:term_name"),
                        f.get("wp:term_description"),
                        f.meta,
                        f.values);
            case "item" -> item(f);
            default -> throw new IllegalArgumentException("not a WXR entry: " + name);
        };
    }

    private static WxrItem item(Fields f) {
        List<WxrComment> comments = new ArrayList<>(f.comments.size());
        for (Fields c : f.comments) {
            comments.add(comment(c));
        }
        return WxrItem.builder()
                .title(f.get("title"))
                .link(f.get("link"))
                .pubDate(f.get("pubDate"))
                .creator(f.get("dc:creator"))
                .guid(f.get("guid"))
                .guidIsPermaLink(permaLink(f.get("guid@isPermaLink")))
                .description(f.get("description"))
                .content(f.get("content:encoded"))
                .excerpt(f.get("excerpt:encoded"))
                .postId(f.integer("wp:post_id"))
                .postDate(f.get("wp:post_date"))
                .postDateGmt(f.get("wp:post_date_gmt"))
                .postModified(f.get("wp:post_modified"))
                .postModifiedGmt(f.get("wp:post_modified_gmt"))
                .commentStatus(f.get("wp:comment_status"))
                .pingStatus(f.get("wp:ping_status"))
                .postName(f.get("wp:post_name"))
                .status(f.get("wp:status"))
                .postParent(f.integer("wp:post_parent"))
                .menuOrder(f.integer("wp:menu_order"))
                .postType(f.get("wp:post_type"))
                .postPassword(f.get("wp:post_password"))
                .isSticky(f.integer("wp:is_sticky"))
                .attachmentUrl(f.get("wp:attachment_url"))
                .terms(f.terms)
                .postMeta(f.meta)
                .comments(comments)
                .rawFields(f.values)
                .build();
    }

    private static WxrComment comment(Fields c) {
        return WxrComment.builder()
                .id(c.integer("wp:comment_id"))
                .author(c.get("wp:comment_author"))
                .authorEmail(c.get("wp:comment_author_email"))
                .authorUrl(c.get("wp:comment_author_url"))
                .authorIp(c.get("wp:comment_author_IP"))
                .date(c.get("wp:comment_date"))
                .dateGmt(c.get("wp:comment_date_gmt"))
                .content(c.get("wp:comment_content"))
                .approved(c.get("wp:comment_approved"))
                .type(c.get("wp:comment_type"))
                .parent(c.integer("wp:comment_parent"))
                .userId(c.integer("wp:comment_user_id"))
                .commentMeta(c.meta)
                .rawFields(c.values)
                .build();
    }

    private static Boolean permaLink(String raw) {
        if ("true".equals(raw)) {
            return Boolean.TRUE;
        }
        return "false".equals(raw) ? Boolean.FALSE : null;
    }
}
