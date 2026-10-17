package org.quarkpress.lib.wxr;

import java.util.List;
import java.util.Map;

/** A {@code wp:comment} of an item, in the order written (a reply may precede its parent). */
public record WxrComment(
        Long id,
        String author,
        String authorEmail,
        String authorUrl,
        String authorIp,
        String date,
        String dateGmt,
        String content,
        String approved,
        String type,
        Long parent,
        Long userId,
        List<WxrMeta> commentMeta,
        Map<String, String> rawFields) {

    public WxrComment {
        commentMeta = Copies.list(commentMeta);
        rawFields = Copies.map(rawFields);
    }

    public static Builder builder() {
        return new Builder();
    }

    public Builder toBuilder() {
        return new Builder()
                .id(id)
                .author(author)
                .authorEmail(authorEmail)
                .authorUrl(authorUrl)
                .authorIp(authorIp)
                .date(date)
                .dateGmt(dateGmt)
                .content(content)
                .approved(approved)
                .type(type)
                .parent(parent)
                .userId(userId)
                .commentMeta(commentMeta)
                .rawFields(rawFields);
    }

    public static final class Builder {
        private Long id;
        private String author;
        private String authorEmail;
        private String authorUrl;
        private String authorIp;
        private String date;
        private String dateGmt;
        private String content;
        private String approved;
        private String type;
        private Long parent;
        private Long userId;
        private List<WxrMeta> commentMeta = List.of();
        private Map<String, String> rawFields = Map.of();

        private Builder() {}

        public Builder id(Long id) {
            this.id = id;
            return this;
        }

        public Builder author(String author) {
            this.author = author;
            return this;
        }

        public Builder authorEmail(String authorEmail) {
            this.authorEmail = authorEmail;
            return this;
        }

        public Builder authorUrl(String authorUrl) {
            this.authorUrl = authorUrl;
            return this;
        }

        public Builder authorIp(String authorIp) {
            this.authorIp = authorIp;
            return this;
        }

        public Builder date(String date) {
            this.date = date;
            return this;
        }

        public Builder dateGmt(String dateGmt) {
            this.dateGmt = dateGmt;
            return this;
        }

        public Builder content(String content) {
            this.content = content;
            return this;
        }

        public Builder approved(String approved) {
            this.approved = approved;
            return this;
        }

        public Builder type(String type) {
            this.type = type;
            return this;
        }

        public Builder parent(Long parent) {
            this.parent = parent;
            return this;
        }

        public Builder userId(Long userId) {
            this.userId = userId;
            return this;
        }

        public Builder commentMeta(List<WxrMeta> commentMeta) {
            this.commentMeta = commentMeta;
            return this;
        }

        public Builder rawFields(Map<String, String> rawFields) {
            this.rawFields = rawFields;
            return this;
        }

        public WxrComment build() {
            return new WxrComment(
                    id,
                    author,
                    authorEmail,
                    authorUrl,
                    authorIp,
                    date,
                    dateGmt,
                    content,
                    approved,
                    type,
                    parent,
                    userId,
                    commentMeta,
                    rawFields);
        }
    }
}
