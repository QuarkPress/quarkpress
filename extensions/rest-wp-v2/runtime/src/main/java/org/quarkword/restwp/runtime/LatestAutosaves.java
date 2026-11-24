package org.quarkword.restwp.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkword.core.PostId;
import org.quarkword.revisions.Revisions;

/**
 * The preview page's and the editor's autosave, read from the revisions module as the current viewer. A bean only while
 * the revisions module is present.
 */
@ApplicationScoped
class LatestAutosaves implements AutosaveOverlay {

    @Inject
    Revisions revisions;

    @Override
    public Optional<Autosave> latest(PostId post) {
        return revisions
                .autosaveOf(post, Optional.empty())
                .map(autosave -> new Autosave(autosave.title(), autosave.content()));
    }

    @Override
    public Optional<Stamp> latestStamp(PostId post) {
        return revisions
                .autosaveOf(post, Optional.empty())
                .map(autosave -> new Stamp(autosave.id().value(), autosave.createdAt()));
    }
}
