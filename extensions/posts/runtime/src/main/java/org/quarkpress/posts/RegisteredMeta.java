package org.quarkpress.posts;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.identity.SecurityIdentity;
import java.util.List;
import java.util.Set;
import org.quarkpress.core.PostId;

/**
 * The registered post meta keys (the built-in ones, then every {@link MetaKeyProvider}'s) and the writes WordPress
 * makes through them below its REST layer: a write runs the key's sanitizer once, with the caller's identity (the
 * sanitized value is what is compared and stored; WordPress runs the callback again inside its meta API), checks
 * {@code edit_post} on the post and the key's auth, and changes the stored values under the site's post lock. Writes
 * answer {@link MetaWrite} and never throw for a refusal; a missing post, or one of another site, throws
 * {@link PostException} {@code invalid_post}. Call it while bound to the post's site.
 */
public interface RegisteredMeta {

    /** The keys of {@code type}: those registered for every type first, then the type's, in registration order. */
    List<MetaKeyDefinition> keys(String type);

    /** The keys of {@code type} shown in REST, in the same order. */
    List<MetaKeyDefinition> restKeys(String type);

    /** The keys of {@code type} that keep revisions, in the same order. */
    Set<String> revisionedKeys(String type);

    /**
     * Whether {@code identity} may write {@code key} on {@code post} for {@code capability} ({@code edit_post_meta},
     * {@code add_post_meta} or {@code delete_post_meta}): the key must be allowed (not protected, unless its auth
     * says otherwise) and the identity must hold {@code edit_post} on the post.
     */
    boolean mayEdit(SecurityIdentity identity, PostSnapshot post, MetaKeyDefinition key, String capability);

    /**
     * The value a write of {@code key} on {@code post} would store: the key's sanitizer run once with the caller's
     * identity, and the result checked as storable. A JSON null, a sanitizer answering JSON null and a value that cannot
     * be stored (nested too deep, say) are refused with {@link IllegalArgumentException}. No permission check and
     * nothing written: for a caller that stores the value elsewhere, an autosave say.
     */
    JsonNode sanitize(PostSnapshot post, MetaKeyDefinition key, JsonNode value);

    /** The stored values of {@code key}, raw and in order; no check (the caller has read the post). */
    List<JsonNode> stored(PostId post, String key);

    /**
     * Stores one value: exactly one stored row equal to the sanitized value (scalars compared as PHP strings) is
     * {@link MetaWrite#UNCHANGED} without any permission check; else {@link #mayEdit} for {@code edit_post_meta} or
     * {@link MetaWrite#DENIED}; else the sanitized value replaces the rows. A JSON null is refused with
     * {@link IllegalArgumentException}: delete the key instead.
     */
    MetaWrite updateSingle(PostId post, MetaKeyDefinition key, JsonNode value);

    /**
     * Deletes every value of the key: {@link #mayEdit} for {@code delete_post_meta} or {@link MetaWrite#DENIED};
     * nothing stored is {@link MetaWrite#UNCHANGED}.
     */
    MetaWrite delete(PostId post, MetaKeyDefinition key);

    /**
     * Makes the stored values those given: {@link #mayEdit} for {@code edit_post_meta} or {@link MetaWrite#DENIED};
     * then stored values equal to a requested one are kept, the other stored values deleted and the remaining
     * requested values added in request order, each sanitized.
     */
    MetaWrite replace(PostId post, MetaKeyDefinition key, List<JsonNode> values);
}
