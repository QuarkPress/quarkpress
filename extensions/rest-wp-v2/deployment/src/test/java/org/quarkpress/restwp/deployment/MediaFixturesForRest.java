package org.quarkpress.restwp.deployment;

import io.quarkus.arc.Arc;
import io.quarkus.arc.Unremovable;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.UnaryOperator;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.media.Media;
import org.quarkpress.media.MediaSnapshot;
import org.quarkpress.media.Upload;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.testing.wprestparity.MediaFixtures;

/**
 * Attachments for tests, uploaded through the media module as a user: a file whose bytes and declared type follow its
 * name's extension ({@code .jpg} a 64x48 JPEG, {@code .png} a 64x48 PNG, {@code .pdf}, {@code .mp3}, {@code .mp4}).
 * Call it from a test method (no request context, no transaction).
 */
public final class MediaFixturesForRest {

    private MediaFixturesForRest() {}

    /**
     * Keeps the media module's service in the application: a test application that injects it nowhere else would have
     * it removed as unused.
     */
    @Singleton
    @Unremovable
    public static class Beans {

        @Inject
        Media media;
    }

    /** Uploads {@code name} on {@code site} as {@code user}, making its image sizes when {@code generateSubSizes}. */
    public static MediaSnapshot upload(SiteId site, UserId user, String name, boolean generateSubSizes) {
        return upload(site, user, name, generateSubSizes, UnaryOperator.identity());
    }

    /** As {@link #upload(SiteId, UserId, String, boolean)}, with further fields set on the upload by {@code fields}. */
    public static MediaSnapshot upload(
            SiteId site, UserId user, String name, boolean generateSubSizes, UnaryOperator<Upload.Builder> fields) {
        Upload upload = fields.apply(Upload.builder(name, file(bytes(name)))
                        .declaredType(type(name))
                        .generateSubSizes(generateSubSizes))
                .build();
        try {
            return Arc.container().instance(SiteScope.class).get().call(site, () -> {
                Arc.container().instance(CurrentIdentityAssociation.class).get().setIdentity(TestBearer.identity(user));
                Media media = Arc.container().instance(Beans.class).get().media;
                // the upload stores the file before its rows' transaction, so it runs outside the scope's own
                return QuarkusTransaction.suspendingExisting().call(() -> media.upload(upload));
            });
        } catch (RuntimeException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static byte[] bytes(String name) {
        return switch (extension(name)) {
            case "jpg" -> MediaFixtures.jpeg(64, 48);
            case "png" -> MediaFixtures.png(64, 48);
            case "pdf" -> MediaFixtures.pdf();
            case "mp3" -> MediaFixtures.mp3(Map.of(), Optional.empty());
            case "mp4" -> MediaFixtures.mp4(64, 48, 1);
            default -> throw new IllegalArgumentException("no fixture for " + name);
        };
    }

    private static String type(String name) {
        return switch (extension(name)) {
            case "jpg" -> "image/jpeg";
            case "png" -> "image/png";
            case "pdf" -> "application/pdf";
            case "mp3" -> "audio/mpeg";
            case "mp4" -> "video/mp4";
            default -> throw new IllegalArgumentException("no fixture for " + name);
        };
    }

    private static String extension(String name) {
        return name.substring(name.lastIndexOf('.') + 1);
    }

    private static Path file(byte[] bytes) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "media-fixtures"));
            return Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
