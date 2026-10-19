package org.quarkpress.security;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;

/**
 * What a post capability check needs to know about a post: its id, its type's capability names (empty when the type
 * is not registered), its raw status, its author (empty for none), the status it had before it was trashed, and the
 * effective status a read check uses (for attachments, inherited from the parent) with that status's visibility. The
 * posts module builds it from its registries.
 *
 * <p>Revisions: parent facts may be passed only for {@code edit_post} and {@code read_post} on a revision. Deleting a
 * revision must be refused by the revisions module or checked with {@link MissingObject}, which maps to
 * {@code do_not_allow}; a revision whose parent is missing must also be passed as {@link MissingObject}.
 */
public record PostFacts(
        PostId id,
        Optional<PostTypeCapabilities> type,
        String status,
        Optional<UserId> author,
        Optional<String> statusBeforeTrash,
        String effectiveStatus,
        PostVisibility effectiveVisibility)
        implements CapObject {

    public PostFacts {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(statusBeforeTrash, "statusBeforeTrash");
        Objects.requireNonNull(effectiveStatus, "effectiveStatus");
        Objects.requireNonNull(effectiveVisibility, "effectiveVisibility");
    }

    /**
     * A post whose effective status is its status, with a built-in status's visibility. An attachment whose status is
     * not private, trash, auto-draft or inherit counts as published, as in WordPress; an attachment with status
     * inherit must be built with {@link #ofAttachment}.
     */
    public static PostFacts of(PostId id, PostTypeCapabilities type, String status, Optional<UserId> author) {
        if (type.name().equals("attachment")
                && !List.of("private", "trash", "auto-draft", "inherit").contains(status)) {
            return new PostFacts(
                    id, Optional.of(type), status, author, Optional.empty(), "publish", PostVisibility.PUBLIC);
        }
        return new PostFacts(
                id,
                Optional.of(type),
                status,
                author,
                Optional.empty(),
                status,
                PostVisibility.ofBuiltInStatus(status));
    }

    /**
     * An attachment: with status inherit it follows its parent's effective status, and one without a parent counts as
     * published; any other status is resolved as in {@link #of}. The parent is empty when the attachment has no parent,
     * its parent is missing, or it is its own parent (a parent with the attachment's own id is treated as none). A
     * trashed parent counts with its status before trashing, or published when none is recorded; a custom status
     * before trashing needs {@link #withEffectiveStatus}.
     */
    public static PostFacts ofAttachment(
            PostId id, String status, Optional<UserId> author, Optional<PostFacts> parent) {
        PostFacts base = of(id, PostTypeCapabilities.ATTACHMENT, status, author);
        if (!status.equals("inherit")) {
            return base;
        }
        return parent.filter(p -> !p.id().equals(id))
                .map(p -> {
                    if (p.effectiveStatus().equals("trash")) {
                        String before = p.statusBeforeTrash().orElse("publish");
                        return base.withEffectiveStatus(before, PostVisibility.ofBuiltInStatus(before));
                    }
                    return base.withEffectiveStatus(p.effectiveStatus(), p.effectiveVisibility());
                })
                .orElseGet(() -> base.withEffectiveStatus("publish", PostVisibility.PUBLIC));
    }

    public PostFacts withStatusBeforeTrash(String before) {
        return new PostFacts(id, type, status, author, Optional.of(before), effectiveStatus, effectiveVisibility);
    }

    public PostFacts withEffectiveStatus(String effective, PostVisibility visibility) {
        return new PostFacts(id, type, status, author, statusBeforeTrash, effective, visibility);
    }

    public PostFacts withUnregisteredType() {
        return new PostFacts(
                id, Optional.empty(), status, author, statusBeforeTrash, effectiveStatus, effectiveVisibility);
    }
}
