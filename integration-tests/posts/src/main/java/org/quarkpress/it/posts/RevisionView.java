package org.quarkpress.it.posts;

import org.quarkpress.core.UserId;
import org.quarkpress.revisions.RevisionSnapshot;

/** A revision or autosave as the test endpoints return it. */
public record RevisionView(
        long id,
        long post,
        Long author,
        String title,
        String content,
        String excerpt,
        boolean autosave,
        String createdAt) {

    static RevisionView of(RevisionSnapshot revision) {
        return new RevisionView(
                revision.id().value(),
                revision.post().value(),
                revision.author().map(UserId::value).orElse(null),
                revision.title(),
                revision.content(),
                revision.excerpt(),
                revision.autosave(),
                revision.createdAt().toString());
    }
}
