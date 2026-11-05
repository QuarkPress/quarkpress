package org.quarkpress.media;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * An upload as {@link UploadFilter}s see it, before it is stored.
 *
 * @param site the site it is uploaded to
 * @param user the uploading user, when there is one
 * @param clientName the file name the client sent
 * @param declaredType the MIME type the client declared, when it declared one
 * @param size the file's size in bytes
 * @param file the received file, to read only
 */
public record UploadCheck(
        SiteId site, Optional<UserId> user, String clientName, Optional<String> declaredType, long size, Path file) {

    public UploadCheck {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(user, "user");
        Objects.requireNonNull(clientName, "clientName");
        Objects.requireNonNull(declaredType, "declaredType");
        Objects.requireNonNull(file, "file");
        if (size < 0) {
            throw new IllegalArgumentException("a file size is not negative: " + size);
        }
    }
}
