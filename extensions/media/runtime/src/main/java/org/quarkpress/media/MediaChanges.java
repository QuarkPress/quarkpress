package org.quarkpress.media;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * The fields of an attachment update; an absent field keeps the stored value. {@code caption} is the post's excerpt,
 * {@code description} its content. {@code altText} is the alternative text of the file, cleaned as a text field.
 * {@code parent} uses 0 for none, as WordPress does. {@code status} is saved by the posts module's status rule for
 * attachments, as {@code Posts.update} saves any attachment's status. {@code expectedVersion} makes the
 * update fail when the attachment post changed since that version. {@code date} (in the site's time zone), {@code
 * dateGmt}, {@code slug}, {@code commentStatus} and {@code pingStatus} are saved as {@code Posts.update} saves them
 * (a date never schedules an attachment).
 */
public record MediaChanges(
        Optional<String> title,
        Optional<String> caption,
        Optional<String> description,
        Optional<String> altText,
        OptionalLong parent,
        Optional<UserId> author,
        Optional<String> status,
        OptionalInt expectedVersion,
        Optional<LocalDateTime> date,
        Optional<Instant> dateGmt,
        Optional<String> slug,
        Optional<String> commentStatus,
        Optional<String> pingStatus) {

    public MediaChanges {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(caption, "caption");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(altText, "altText");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
        Objects.requireNonNull(date, "date");
        Objects.requireNonNull(dateGmt, "dateGmt");
        Objects.requireNonNull(slug, "slug");
        Objects.requireNonNull(commentStatus, "commentStatus");
        Objects.requireNonNull(pingStatus, "pingStatus");
        if (parent.isPresent() && parent.getAsLong() < 0) {
            throw new IllegalArgumentException("a parent id is 0 (none) or positive: " + parent.getAsLong());
        }
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String title;
        private String caption;
        private String description;
        private String altText;
        private OptionalLong parent = OptionalLong.empty();
        private UserId author;
        private String status;
        private OptionalInt expectedVersion = OptionalInt.empty();
        private LocalDateTime date;
        private Instant dateGmt;
        private String slug;
        private String commentStatus;
        private String pingStatus;

        private Builder() {}

        public Builder title(String value) {
            this.title = Objects.requireNonNull(value, "title");
            return this;
        }

        public Builder caption(String value) {
            this.caption = Objects.requireNonNull(value, "caption");
            return this;
        }

        public Builder description(String value) {
            this.description = Objects.requireNonNull(value, "description");
            return this;
        }

        public Builder altText(String value) {
            this.altText = Objects.requireNonNull(value, "altText");
            return this;
        }

        /** The parent's id; 0 detaches the attachment. */
        public Builder parent(long id) {
            if (id < 0) {
                throw new IllegalArgumentException("a parent id is 0 (none) or positive: " + id);
            }
            this.parent = OptionalLong.of(id);
            return this;
        }

        public Builder parent(PostId post) {
            return parent(post.value());
        }

        public Builder author(UserId value) {
            this.author = Objects.requireNonNull(value, "author");
            return this;
        }

        public Builder status(String value) {
            this.status = Objects.requireNonNull(value, "status");
            return this;
        }

        public Builder expectedVersion(int value) {
            this.expectedVersion = OptionalInt.of(value);
            return this;
        }

        /** The attachment's date in the site's time zone. */
        public Builder date(LocalDateTime value) {
            this.date = Objects.requireNonNull(value, "date");
            return this;
        }

        /** The attachment's date in UTC. */
        public Builder dateGmt(Instant value) {
            this.dateGmt = Objects.requireNonNull(value, "dateGmt");
            return this;
        }

        public Builder slug(String value) {
            this.slug = Objects.requireNonNull(value, "slug");
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

        public MediaChanges build() {
            return new MediaChanges(
                    Optional.ofNullable(title),
                    Optional.ofNullable(caption),
                    Optional.ofNullable(description),
                    Optional.ofNullable(altText),
                    parent,
                    Optional.ofNullable(author),
                    Optional.ofNullable(status),
                    expectedVersion,
                    Optional.ofNullable(date),
                    Optional.ofNullable(dateGmt),
                    Optional.ofNullable(slug),
                    Optional.ofNullable(commentStatus),
                    Optional.ofNullable(pingStatus));
        }
    }
}
