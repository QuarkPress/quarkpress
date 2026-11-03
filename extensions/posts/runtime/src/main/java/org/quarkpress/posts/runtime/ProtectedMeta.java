package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PageTemplates;

/**
 * Writes the internal meta keys QuarkPress keeps itself, and only those: the editor's lock, the last editor, the
 * featured image and the page template. Any other key is refused with {@link IllegalArgumentException}: registered
 * meta goes through its sanitizer and auth, never through here. The meta column alone is written, with an update
 * statement on the locked row that names no version, and the managed row is read again afterwards: the post's
 * modification date and its version stay as they are, so a lock refresh is never a change of the post. Callers hold
 * the site's post lock and the row lock; they have checked the caller already.
 */
@ApplicationScoped
public class ProtectedMeta {

    /** The keys this writer accepts. */
    public static final Set<String> KEYS =
            Set.of(JpaEditLocks.EDIT_LOCK, JpaEditLocks.EDIT_LAST, JpaFeaturedImages.THUMBNAIL_ID, PageTemplates.KEY);

    private static final String UPDATE = "update PostEntity p set p.meta = :meta where p.id = :id and p.siteId = :site";

    @Inject
    PostRepository posts;

    /** Makes {@code value} the key's only value. */
    public void put(SiteId site, PostEntity locked, String key, JsonNode value) {
        requireKey(key);
        Objects.requireNonNull(value, "value");
        ObjectNode meta = MetaRows.copy(locked.meta);
        meta.putArray(key).add(value.deepCopy());
        write(site, locked, meta);
    }

    /** Removes the key with every value; nothing happens when it is absent. */
    public void remove(SiteId site, PostEntity locked, String key) {
        requireKey(key);
        ObjectNode meta = MetaRows.copy(locked.meta);
        if (meta.remove(key) == null) {
            return;
        }
        write(site, locked, meta);
    }

    /** The first value of the key as text ({@link #scalarText}); empty when absent or not a scalar. */
    public Optional<String> text(PostEntity row, String key) {
        List<JsonNode> values = MetaRows.values(row.meta, key);
        return values.isEmpty() ? Optional.empty() : scalarText(values.get(0));
    }

    /** A stored value as text: a string as it is, a number or boolean as its text; null or a container is empty. */
    public static Optional<String> scalarText(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode() || value.isContainerNode()) {
            return Optional.empty();
        }
        return Optional.of(value.isTextual() ? value.textValue() : value.asText());
    }

    private static void requireKey(String key) {
        if (!KEYS.contains(Objects.requireNonNull(key, "key"))) {
            throw new IllegalArgumentException("not a key of the protected meta writer: " + key);
        }
    }

    private void write(SiteId site, PostEntity locked, ObjectNode meta) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(locked.id, "the row is not stored");
        posts.getEntityManager()
                .createQuery(UPDATE)
                .setParameter("meta", meta)
                .setParameter("id", locked.id)
                .setParameter("site", TenantIds.param(site))
                .executeUpdate();
        posts.getEntityManager().refresh(locked);
    }
}
