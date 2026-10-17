package org.quarkpress.lib.wxr;

import java.util.List;
import java.util.Map;

/**
 * An {@code item}: a post of any type with its terms, meta and comments. Text fields are null when absent and
 * never trimmed; integer fields are null when absent or not whole numbers (their text stays in {@link
 * #rawFields()}). {@code guidIsPermaLink} is the {@code isPermaLink} attribute ({@code "true"}/{@code "false"},
 * otherwise null).
 */
public record WxrItem(
        String title,
        String link,
        String pubDate,
        String creator,
        String guid,
        Boolean guidIsPermaLink,
        String description,
        String content,
        String excerpt,
        Long postId,
        String postDate,
        String postDateGmt,
        String postModified,
        String postModifiedGmt,
        String commentStatus,
        String pingStatus,
        String postName,
        String status,
        Long postParent,
        Long menuOrder,
        String postType,
        String postPassword,
        Long isSticky,
        String attachmentUrl,
        List<WxrItemTerm> terms,
        List<WxrMeta> postMeta,
        List<WxrComment> comments,
        Map<String, String> rawFields)
        implements WxrEntry {

    public WxrItem {
        terms = Copies.list(terms);
        postMeta = Copies.list(postMeta);
        comments = Copies.list(comments);
        rawFields = Copies.map(rawFields);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .title(title)
                .link(link)
                .pubDate(pubDate)
                .creator(creator)
                .guid(guid)
                .guidIsPermaLink(guidIsPermaLink)
                .description(description)
                .content(content)
                .excerpt(excerpt)
                .postId(postId)
                .postDate(postDate)
                .postDateGmt(postDateGmt)
                .postModified(postModified)
                .postModifiedGmt(postModifiedGmt)
                .commentStatus(commentStatus)
                .pingStatus(pingStatus)
                .postName(postName)
                .status(status)
                .postParent(postParent)
                .menuOrder(menuOrder)
                .postType(postType)
                .postPassword(postPassword)
                .isSticky(isSticky)
                .attachmentUrl(attachmentUrl)
                .terms(terms)
                .postMeta(postMeta)
                .comments(comments)
                .rawFields(rawFields);
    }

    public static final class Builder {
        private String title;
        private String link;
        private String pubDate;
        private String creator;
        private String guid;
        private Boolean guidIsPermaLink;
        private String description;
        private String content;
        private String excerpt;
        private Long postId;
        private String postDate;
        private String postDateGmt;
        private String postModified;
        private String postModifiedGmt;
        private String commentStatus;
        private String pingStatus;
        private String postName;
        private String status;
        private Long postParent;
        private Long menuOrder;
        private String postType;
        private String postPassword;
        private Long isSticky;
        private String attachmentUrl;
        private List<WxrItemTerm> terms = List.of();
        private List<WxrMeta> postMeta = List.of();
        private List<WxrComment> comments = List.of();
        private Map<String, String> rawFields = Map.of();

        private Builder() {}

        public Builder title(String title) {
            this.title = title;
            return this;
        }

        public Builder link(String link) {
            this.link = link;
            return this;
        }

        public Builder pubDate(String pubDate) {
            this.pubDate = pubDate;
            return this;
        }

        public Builder creator(String creator) {
            this.creator = creator;
            return this;
        }

        public Builder guid(String guid) {
            this.guid = guid;
            return this;
        }

        public Builder guidIsPermaLink(Boolean guidIsPermaLink) {
            this.guidIsPermaLink = guidIsPermaLink;
            return this;
        }

        public Builder description(String description) {
            this.description = description;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder excerpt(String excerpt) {
            this.excerpt = excerpt;
            return this;
        }

        public Builder postId(Long postId) {
            this.postId = postId;
            return this;
        }

        public Builder postDate(String postDate) {
            this.postDate = postDate;
            return this;
        }

        public Builder postDateGmt(String postDateGmt) {
            this.postDateGmt = postDateGmt;
            return this;
        }

        public Builder postModified(String postModified) {
            this.postModified = postModified;
            return this;
        }

        public Builder postModifiedGmt(String postModifiedGmt) {
            this.postModifiedGmt = postModifiedGmt;
            return this;
        }

        public Builder commentStatus(String commentStatus) {
            this.commentStatus = commentStatus;
            return this;
        }

        public Builder pingStatus(String pingStatus) {
            this.pingStatus = pingStatus;
            return this;
        }

        public Builder postName(String postName) {
            this.postName = postName;
            return this;
        }

        public Builder status(String status) {
            this.status = status;
            return this;
        }

        public Builder postParent(Long postParent) {
            this.postParent = postParent;
            return this;
        }

        public Builder menuOrder(Long menuOrder) {
            this.menuOrder = menuOrder;
            return this;
        }

        public Builder postType(String postType) {
            this.postType = postType;
            return this;
        }

        public Builder postPassword(String postPassword) {
            this.postPassword = postPassword;
            return this;
        }

        public Builder isSticky(Long isSticky) {
            this.isSticky = isSticky;
            return this;
        }

        public Builder attachmentUrl(String attachmentUrl) {
            this.attachmentUrl = attachmentUrl;
            return this;
        }

        public Builder terms(List<WxrItemTerm> terms) {
            this.terms = terms;
            return this;
        }

        public Builder postMeta(List<WxrMeta> postMeta) {
            this.postMeta = postMeta;
            return this;
        }

        public Builder comments(List<WxrComment> comments) {
            this.comments = comments;
            return this;
        }

        public Builder rawFields(Map<String, String> rawFields) {
            this.rawFields = rawFields;
            return this;
        }

        public WxrItem build() {
            return new WxrItem(
                    title,
                    link,
                    pubDate,
                    creator,
                    guid,
                    guidIsPermaLink,
                    description,
                    content,
                    excerpt,
                    postId,
                    postDate,
                    postDateGmt,
                    postModified,
                    postModifiedGmt,
                    commentStatus,
                    pingStatus,
                    postName,
                    status,
                    postParent,
                    menuOrder,
                    postType,
                    postPassword,
                    isSticky,
                    attachmentUrl,
                    terms,
                    postMeta,
                    comments,
                    rawFields);
        }
    }
}
