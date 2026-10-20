package org.quarkpress.posts;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * The fields of a post write; an absent field is not given (an insert takes WordPress's default, an update keeps the
 * stored value). {@code author} and {@code parent} use 0 for none, as WordPress does; a positive author must be an
 * existing user, a positive parent a post of the same site. {@code date} is the local date in the site's zone,
 * {@code dateGmt} the GMT date; when both are given and disagree the GMT date wins. {@code editDate} lets an update
 * give a date to a draft whose date floats (WordPress's {@code edit_date}). {@code expectedVersion} makes an update
 * fail when the post changed since that version.
 */
public record PostChanges(
        Optional<String> type,
        Optional<String> status,
        Optional<String> title,
        Optional<String> content,
        Optional<String> excerpt,
        Optional<String> slug,
        Optional<String> password,
        Optional<String> commentStatus,
        Optional<String> pingStatus,
        OptionalLong author,
        OptionalLong parent,
        OptionalInt menuOrder,
        Optional<LocalDateTime> date,
        Optional<Instant> dateGmt,
        boolean editDate,
        OptionalInt expectedVersion) {

    public PostChanges {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(content, "content");
        Objects.requireNonNull(excerpt, "excerpt");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(commentStatus, "commentStatus");
        Objects.requireNonNull(pingStatus, "pingStatus");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(menuOrder, "menuOrder");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(dateGmt, "dateGmt");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        if (author.isPresent() && author.getAsLong() < 0) {
            throw new IllegalArgumentException("an author id is 0 (none) or positive: " + author.getAsLong());
        }
        if (parent.isPresent() && parent.getAsLong() < 0) {
            throw new IllegalArgumentException("a parent id is 0 (none) or positive: " + parent.getAsLong());
        }
    }

    /** Nothing given. */
    public static PostChanges none() {
        return builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    /** A builder holding these changes. */
    public Builder toBuilder() {
        Builder builder = new Builder();
        builder.type = type.orElse(null);
        builder.status = status.orElse(null);
        builder.title = title.orElse(null);
        builder.content = content.orElse(null);
        builder.excerpt = excerpt.orElse(null);
        builder.slug = slug.orElse(null);
        builder.password = password.orElse(null);
        builder.commentStatus = commentStatus.orElse(null);
        builder.pingStatus = pingStatus.orElse(null);
        builder.author = author;
        builder.parent = parent;
        builder.menuOrder = menuOrder;
        builder.date = date.orElse(null);
        builder.dateGmt = dateGmt.orElse(null);
        builder.editDate = editDate;
        builder.expectedVersion = expectedVersion;
        return builder;
    }

    public static final class Builder {
        private String type;
        private String status;
        private String title;
        private String content;
        private String excerpt;
        private String slug;
        private String password;
        private String commentStatus;
        private String pingStatus;
        private OptionalLong author = OptionalLong.empty();
        private OptionalLong parent = OptionalLong.empty();
        private OptionalInt menuOrder = OptionalInt.empty();
        private LocalDateTime date;
        private Instant dateGmt;
        private boolean editDate;
        private OptionalInt expectedVersion = OptionalInt.empty();

        private Builder() {}

        public Builder type(String value) {
            this.type = Objects.requireNonNull(value, "type");
            return this;
        }

        public Builder status(String value) {
            this.status = Objects.requireNonNull(value, "status");
            return this;
        }

        public Builder title(String value) {
            this.title = Objects.requireNonNull(value, "title");
            return this;
        }

        public Builder content(String value) {
            this.content = Objects.requireNonNull(value, "content");
            return this;
        }

        public Builder excerpt(String value) {
            this.excerpt = Objects.requireNonNull(value, "excerpt");
            return this;
        }

        public Builder slug(String value) {
            this.slug = Objects.requireNonNull(value, "slug");
            return this;
        }

        public Builder password(String value) {
            this.password = Objects.requireNonNull(value, "password");
            return this;
        }

        public Builder commentStatus(String value) {
            this.commentStatus = Objects.requireNonNull(value, "commentStatus");
            return this;
        }

        public Builder pingStatus(String value) {
            this.pingStatus = Objects.requireNonNull(value, "pingStatus");
            return this;
        }

        /** The author's id; 0 for none. */
        public Builder author(long id) {
            this.author = OptionalLong.of(id);
            return this;
        }

        public Builder author(UserId user) {
            return author(user.value());
        }

        /** The parent's id; 0 for none. */
        public Builder parent(long id) {
            this.parent = OptionalLong.of(id);
            return this;
        }

        public Builder parent(PostId post) {
            return parent(post.value());
        }

        public Builder menuOrder(int value) {
            this.menuOrder = OptionalInt.of(value);
            return this;
        }

        public Builder date(LocalDateTime value) {
            this.date = Objects.requireNonNull(value, "date");
            return this;
        }

        public Builder dateGmt(Instant value) {
            this.dateGmt = Objects.requireNonNull(value, "dateGmt");
            return this;
        }

        public Builder editDate(boolean value) {
            this.editDate = value;
            return this;
        }

        public Builder expectedVersion(int value) {
            this.expectedVersion = OptionalInt.of(value);
            return this;
        }

        public PostChanges build() {
            return new PostChanges(
                    Optional.ofNullable(type),
                    Optional.ofNullable(status),
                    Optional.ofNullable(title),
                    Optional.ofNullable(content),
                    Optional.ofNullable(excerpt),
                    Optional.ofNullable(slug),
                    Optional.ofNullable(password),
                    Optional.ofNullable(commentStatus),
                    Optional.ofNullable(pingStatus),
                    author,
                    parent,
                    menuOrder,
                    Optional.ofNullable(date),
                    Optional.ofNullable(dateGmt),
                    editDate,
                    expectedVersion);
        }
    }
}
