package org.quarkword.media.image.vips.deployment;

import io.quarkus.arc.Arc;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.narayana.jta.QuarkusTransactionException;
import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import javax.sql.DataSource;
import org.quarkword.core.SiteId;
import org.quarkword.core.UserId;
import org.quarkword.media.Media;
import org.quarkword.media.MediaSnapshot;
import org.quarkword.media.Upload;
import org.quarkword.persistence.SiteScope;
import org.quarkword.persistence.SiteScopedNativeSql;
import org.quarkword.security.QuarkWordPrincipal;

/**
 * Uploads through the Java media API on the main site, as an author written with plain JDBC. A small copy of what the
 * media module's tests do with their helpers, which this module cannot see. Its SQL names the main site explicitly.
 */
@SiteScopedNativeSql
public final class Uploads {

    /** The main site, which the persistence migrations create. */
    static final SiteId MAIN = SiteId.of(1);

    private Uploads() {}

    /** A new profile that is an author of the main site. */
    public static UserId author(DataSource dataSource) {
        String subject = "author-" + UUID.randomUUID();
        update(
                dataSource,
                "insert into qw_user_profile (subject, display_name, nicename) values (?, ?, ?)",
                subject,
                "author",
                "n-" + UUID.randomUUID().toString().replace("-", "").substring(0, 30));
        long id = single(dataSource, "select id from qw_user_profile where subject = ?", subject);
        String json = VipsApp.kind().equals("postgresql") ? "cast(? as json)" : "?";
        update(
                dataSource,
                "insert into qw_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + json
                        + ", '{}')",
                MAIN.value(),
                id,
                "[\"author\"]");
        return UserId.of(id);
    }

    /** An upload of {@code bytes} named {@code name}, declared as {@code type}. */
    public static Upload.Builder of(String name, byte[] bytes, String type) {
        try {
            Path dir = Files.createDirectories(Path.of("target", "vips-uploads"));
            Path file = Files.write(dir.resolve(UUID.randomUUID().toString()), bytes);
            return Upload.builder(name, file).declaredType(type);
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }

    /**
     * Uploads as {@code user} on the main site, outside any transaction; what the upload throws is rethrown as it is.
     */
    public static MediaSnapshot upload(Media media, UserId user, Upload.Builder upload) throws Exception {
        SiteScope scope = Arc.container().instance(SiteScope.class).get();
        String subject = "user-" + user.value();
        return scope.call(MAIN, () -> {
            Arc.container()
                    .instance(CurrentIdentityAssociation.class)
                    .get()
                    .setIdentity(QuarkusSecurityIdentity.builder()
                            .setPrincipal(new QuarkusPrincipal(subject))
                            .addAttribute(QuarkWordPrincipal.ATTRIBUTE, new QuarkWordPrincipal(user, subject))
                            .build());
            try {
                return QuarkusTransaction.suspendingExisting().call(() -> media.upload(upload.build()));
            } catch (QuarkusTransactionException wrapped) {
                throw wrapped.getCause() instanceof Exception cause ? cause : wrapped;
            }
        });
    }

    /**
     * An upload an earlier run of the application stored but left pending: a {@code width}x{@code height} JPEG named
     * {@code name} in {@code storage}, on the main site, whose post was created ten minutes ago. Answers the post id.
     */
    public static long pendingJpeg(DataSource dataSource, TestStorage storage, String name, int width, int height)
            throws IOException {
        byte[] bytes = org.quarkword.testing.wprestparity.MediaFixtures.jpeg(width, height);
        String key = "2026/11/" + name;
        Path dir = Files.createDirectories(Path.of("target", "vips-uploads"));
        storage.put(
                key,
                Files.write(dir.resolve(UUID.randomUUID().toString()), bytes),
                "image/jpeg",
                org.quarkword.media.WriteMode.CREATE_NEW);
        boolean postgres = VipsApp.kind().equals("postgresql");
        String now = postgres ? "now()" : "utc_timestamp(6)";
        String json = postgres ? "cast(? as json)" : "?";
        String guid = "guid-" + UUID.randomUUID();
        update(
                dataSource,
                "insert into qw_post (site_id, type, status, slug, title, content, excerpt, created_at, modified_at,"
                        + " comment_status, ping_status, guid) values (?, 'attachment', 'inherit', ?, ?, '', '', "
                        + now + ", " + now + ", 'open', 'open', ?)",
                MAIN.value(),
                name,
                name,
                guid);
        long post = single(dataSource, "select id from qw_post where guid = ?", guid);
        update(dataSource, "update qw_post set created_at = created_at - interval '10' minute where id = ?", post);
        update(
                dataSource,
                "insert into qw_media (post_id, site_id, storage_key, mime_type, file_size, width, height, sizes,"
                        + " image_meta, extra, alt_text, processing, stored_bytes, version) values (?, ?, ?,"
                        + " 'image/jpeg', ?, " + width + ", " + height + ", " + json + ", null, " + json
                        + ", '', 'pending', ?, 0)",
                post,
                MAIN.value(),
                key,
                (long) bytes.length,
                "{}",
                "{}",
                (long) bytes.length);
        return post;
    }

    /** The first column of the first row {@code sql} selects, as text; null when there is none. */
    public static String text(DataSource dataSource, String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, params);
                ResultSet rows = statement.executeQuery()) {
            return rows.next() ? rows.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(DataSource dataSource, String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, params)) {
            statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static long single(DataSource dataSource, String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, params);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }
}
