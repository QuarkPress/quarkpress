package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.interceptor.Interceptor;
import jakarta.transaction.Status;
import jakarta.transaction.Synchronization;
import jakarta.transaction.TransactionSynchronizationRegistry;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.media.MediaStorage;
import org.quarkpress.media.StoredKey;
import org.quarkpress.persistence.runtime.QuarkPressRuntimeConfig;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostDeleting;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.runtime.JpaFeaturedImages;

/**
 * Deletes what an attachment leaves behind when it is deleted for good, however it is deleted ({@code Media.delete},
 * {@code Posts.delete}, the trash's housekeeping), as WordPress's {@code wp_delete_attachment} does: inside the
 * deleting transaction, the featured images naming it are cleared and its media row, locked and read again, goes with
 * the post; after the transaction has committed, its files are removed, one by one, in WordPress's order: a legacy
 * thumbnail no other attachment of the site names, every size, the untouched original, the files of the client-side
 * flow (source image, animated video and its poster), the image editor's backups, and the main file last. A rolled-back
 * delete removes nothing.
 *
 * <p>Every derived name must be a single name, placed in the main file's folder: an entry holding a {@code /}, a
 * {@code \}, a name the storage refuses ({@code ..}, a NUL) or an empty name is skipped and logged. The main file must
 * lie inside the site's uploads (a site other than the main one under {@code sites/<id>/}, the main site outside
 * {@code sites/}); otherwise no file at all is removed and the attachment is logged. A file already missing is
 * skipped, and a storage failure is logged, never raised: the row is gone by then.
 */
@ApplicationScoped
public class AttachmentDeletion {

    private static final Logger LOG = Logger.getLogger(AttachmentDeletion.class);

    /** The meta key of the image editor's backups. */
    static final String BACKUP_SIZES = "_wp_attachment_backup_sizes";

    // the keys of extra naming files kept beside the main file, in WordPress's order
    private static final List<String> CLIENT_FLOW_FILES =
            List.of("source_image", "animated_video", "animated_video_poster");

    // the whole metadata of the other attachments, in any letter case: their files, sizes and other keys
    private static final String SHARED_THUMB = "select count(m) from MediaEntity m where m.siteId = ?1"
            + " and m.postId <> ?2 and (lower(m.storageKey) like ?3 escape '!'"
            + " or lower(coalesce(m.originalKey, '')) like ?3 escape '!'"
            + " or lower(cast(m.sizes as String)) like ?3 escape '!'"
            + " or lower(cast(m.extra as String)) like ?3 escape '!')";

    @Inject
    JpaFeaturedImages featuredImages;

    @Inject
    MediaRepository mediaRows;

    @Inject
    ActiveStorage storage;

    @Inject
    TransactionSynchronizationRegistry registry;

    @Inject
    QuarkPressRuntimeConfig runtimeConfig;

    void deleting(@Observes @Priority(Interceptor.Priority.LIBRARY_BEFORE) PostDeleting event) {
        PostSnapshot post = event.post();
        if (!post.type().equals("attachment")) {
            return;
        }
        try {
            SiteId site = event.site();
            // the deleting caller holds the site's post lock: posts come before the media row in the lock order
            featuredImages.forget(site, post.id());
            Optional<MediaEntity> locked = mediaRows.lockMedia(site, post.id());
            if (locked.isEmpty()) {
                return;
            }
            MediaEntity row = locked.get();
            List<String> keys = files(site, post, row);
            mediaRows.delete(row);
            if (!keys.isEmpty()) {
                registry.registerInterposedSynchronization(new RemoveAfterCommit(storage.storage(), post.id(), keys));
            }
        } catch (RuntimeException failure) {
            // what is cleaned up must not be committed without the delete, whatever the caller's rollback rules
            try {
                registry.setRollbackOnly();
            } catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    /** The keys of the attachment's files, in the order they are removed; none when its main file is not the site's. */
    private List<String> files(SiteId site, PostSnapshot post, MediaEntity row) {
        String main = row.storageKey;
        if (!insideUploads(site, main)) {
            LOG.warnf(
                    "attachment %s of site %s names the main file %s, outside the site's uploads: no file of it is"
                            + " deleted",
                    post.id().value(), site.value(), main);
            return List.of();
        }
        String folder = main.substring(0, main.lastIndexOf('/') + 1);
        Set<String> keys = new LinkedHashSet<>();
        ObjectNode extra = row.extra instanceof ObjectNode node ? node : null;
        if (extra != null && extra.path("thumb").isTextual()) {
            String thumb = extra.path("thumb").textValue();
            if (!thumb.isEmpty() && !sharedThumb(site, post.id(), thumb)) {
                add(keys, folder, thumb, post.id());
            }
        }
        if (row.sizes instanceof ObjectNode sizes) {
            for (JsonNode size : sizes) {
                JsonNode file = size.path("file");
                if (file.isTextual()) {
                    add(keys, folder, file.textValue(), post.id());
                }
            }
        }
        if (row.originalKey != null) {
            add(keys, folder, row.originalKey.substring(row.originalKey.lastIndexOf('/') + 1), post.id());
        }
        if (extra != null) {
            for (String key : CLIENT_FLOW_FILES) {
                if (extra.path(key).isTextual()) {
                    add(keys, folder, extra.path(key).textValue(), post.id());
                }
            }
        }
        List<JsonNode> backups = post.meta(BACKUP_SIZES);
        if (!backups.isEmpty() && backups.get(0) instanceof ObjectNode entries) {
            for (JsonNode backup : entries) {
                JsonNode file = backup.path("file");
                if (file.isTextual()) {
                    add(keys, folder, file.textValue(), post.id());
                }
            }
        }
        keys.add(main);
        return new ArrayList<>(keys);
    }

    /** Whether {@code key} is a valid key inside the site's uploads. */
    private boolean insideUploads(SiteId site, String key) {
        try {
            StoredKey.require(key);
        } catch (IllegalArgumentException invalid) {
            return false;
        }
        if (site.value() == runtimeConfig.mainSiteId()) {
            return !key.startsWith("sites/");
        }
        String base = "sites/" + site.value() + "/";
        return key.startsWith(base);
    }

    /** Adds the file {@code name} of the main file's {@code folder}, unless it is no single name there (logged). */
    private static void add(Set<String> keys, String folder, String name, PostId attachment) {
        if (!name.isEmpty() && name.indexOf('/') < 0 && name.indexOf('\\') < 0) {
            String key = folder + name;
            try {
                keys.add(StoredKey.require(key));
                return;
            } catch (IllegalArgumentException invalid) {
                // logged below
            }
        }
        LOG.warnf(
                "attachment %s names the file \"%s\", which is no single name in its folder: it is not deleted",
                attachment.value(), name);
    }

    /** Whether another attachment of the site names {@code thumb} anywhere in its metadata, in any letter case. */
    private boolean sharedThumb(SiteId site, PostId attachment, String thumb) {
        Long count = mediaRows
                .getEntityManager()
                .createQuery(SHARED_THUMB, Long.class)
                .setParameter(1, TenantIds.param(site))
                .setParameter(2, attachment.value())
                .setParameter(3, "%" + likeEscaped(thumb.toLowerCase(Locale.ROOT)) + "%")
                .getSingleResult();
        return count != null && count > 0;
    }

    /** {@code text} matched as itself by {@code like ... escape '!'}. */
    private static String likeEscaped(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '!' || c == '%' || c == '_') {
                escaped.append('!');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }

    /** Removes the files once the deleting transaction has committed; each failure is logged. */
    private record RemoveAfterCommit(MediaStorage storage, PostId attachment, List<String> keys)
            implements Synchronization {

        @Override
        public void beforeCompletion() {}

        @Override
        public void afterCompletion(int status) {
            if (status != Status.STATUS_COMMITTED) {
                return;
            }
            for (String key : keys) {
                try {
                    storage.delete(key);
                } catch (RuntimeException failure) {
                    LOG.warnf(
                            failure,
                            "the file %s of the deleted attachment %s could not be removed",
                            key,
                            attachment.value());
                }
            }
        }
    }
}
