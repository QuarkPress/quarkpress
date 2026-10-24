package org.quarkpress.revisions;

import java.util.Objects;
import org.quarkpress.posts.PostSnapshot;

/** What an autosave did: updated the caller's own draft, or stored (or kept) the caller's autosave. */
public sealed interface AutosaveResult permits AutosaveResult.Draft, AutosaveResult.Stored {

    /** The post itself was updated: it was the caller's own draft or auto-draft. */
    record Draft(PostSnapshot post) implements AutosaveResult {
        public Draft {
            Objects.requireNonNull(post, "post");
        }
    }

    /**
     * The caller's autosave of the post, created or updated ({@code written}), or returned untouched because nothing
     * given differs from the post (then {@code written} is false and the autosave may be stale).
     */
    record Stored(RevisionSnapshot autosave, boolean written) implements AutosaveResult {
        public Stored {
            Objects.requireNonNull(autosave, "autosave");
        }
    }
}
