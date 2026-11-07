package org.quarkpress.media;

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
 * update fail when the attachment post changed since that version.
 */
public record MediaChanges(
        Optional<String> title,
        Optional<String> caption,
        Optional<String> description,
        Optional<String> altText,
        OptionalLong parent,
        Optional<UserId> author,
        Optional<String> status,
        OptionalInt expectedVersion) {

    public MediaChanges {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(caption, "caption");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(altText, "altText");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(expectedVersion, "expectedVersion");
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

        public MediaChanges build() {
            return new MediaChanges(
                    Optional.ofNullable(title),
                    Optional.ofNullable(caption),
                    Optional.ofNullable(description),
                    Optional.ofNullable(altText),
                    parent,
                    Optional.ofNullable(author),
                    Optional.ofNullable(status),
                    expectedVersion);
        }
    }
}
