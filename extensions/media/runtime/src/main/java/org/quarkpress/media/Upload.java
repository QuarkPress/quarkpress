package org.quarkpress.media;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * A file to store in the media library, with the fields of its attachment that the request gives.
 *
 * @param clientName the file name the client sent
 * @param declaredType the MIME type the client declared for the file, when it declared one
 * @param file the received file; it is copied into the storage, never moved or changed
 * @param size the file's size in bytes; storing refuses an upload whose file holds another size
 * @param parent the post the attachment belongs to, when there is one
 * @param title the attachment's title; when absent or empty as PHP sees it ({@code ""} or {@code "0"}), the
 *     embedded title, then the client name
 * @param caption the attachment's caption; when absent or empty as PHP sees it, the embedded caption when there is
 *     one
 * @param description the attachment's description; when absent, empty
 * @param altText the alternative text; when absent, the embedded one
 * @param status the attachment's status; when absent, {@code inherit}
 * @param author the attachment's author; when absent, the caller (another user needs {@code edit_others_posts})
 * @param generateSubSizes whether sizes are generated (false also lets an image the processor cannot handle through)
 * @param convertFormat whether sizes may be written in another type than the file's (HEIC images in JPEG)
 */
public record Upload(
        String clientName,
        Optional<String> declaredType,
        Path file,
        long size,
        Optional<PostId> parent,
        Optional<String> title,
        Optional<String> caption,
        Optional<String> description,
        Optional<String> altText,
        Optional<String> status,
        Optional<UserId> author,
        boolean generateSubSizes,
        boolean convertFormat) {

    public Upload {
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(declaredType, "declaredType");
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(caption, "caption");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(altText, "altText");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(author, "author");
        if (size < 0) {
            throw new IllegalArgumentException("a file size is not negative: " + size);
        }
    }

    /**
     * An upload of {@code file} sent as {@code clientName}, its size read from the file, sizes generated and
     * converted as configured.
     *
     * @throws UncheckedIOException when the file's size cannot be read
     */
    public static Builder builder(String clientName, Path file) {
        return new Builder(clientName, file);
    }

    public static final class Builder {
        private final String clientName;
        private final Path file;
        private final long size;
        private String declaredType;
        private PostId parent;
        private String title;
        private String caption;
        private String description;
        private String altText;
        private String status;
        private UserId author;
        private boolean generateSubSizes = true;
        private boolean convertFormat = true;

        private Builder(String clientName, Path file) {
            this.clientName = Objects.requireNonNull(clientName, "clientName");
            this.file = Objects.requireNonNull(file, "file");
            try {
                this.size = Files.size(file);
            } catch (IOException failure) {
                throw new UncheckedIOException(failure);
            }
        }

        public Builder declaredType(String value) {
            this.declaredType = Objects.requireNonNull(value, "declaredType");
            return this;
        }

        public Builder parent(PostId value) {
            this.parent = Objects.requireNonNull(value, "parent");
            return this;
        }

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

        public Builder status(String value) {
            this.status = Objects.requireNonNull(value, "status");
            return this;
        }

        public Builder author(UserId value) {
            this.author = Objects.requireNonNull(value, "author");
            return this;
        }

        public Builder generateSubSizes(boolean value) {
            this.generateSubSizes = value;
            return this;
        }

        public Builder convertFormat(boolean value) {
            this.convertFormat = value;
            return this;
        }

        public Upload build() {
            return new Upload(
                    clientName,
                    Optional.ofNullable(declaredType),
                    file,
                    size,
                    Optional.ofNullable(parent),
                    Optional.ofNullable(title),
                    Optional.ofNullable(caption),
                    Optional.ofNullable(description),
                    Optional.ofNullable(altText),
                    Optional.ofNullable(status),
                    Optional.ofNullable(author),
                    generateSubSizes,
                    convertFormat);
        }
    }
}
