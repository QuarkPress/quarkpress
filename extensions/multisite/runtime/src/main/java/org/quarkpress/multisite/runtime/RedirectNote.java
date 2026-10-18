package org.quarkpress.multisite.runtime;

import java.net.URI;
import java.util.Optional;
import org.quarkpress.core.SiteId;

/**
 * Carries the redirect of a redirecting alias from the domain resolver to the HTTP resolution that called it, on the
 * same thread and within one resolution.
 */
final class RedirectNote {

    private record Note(SiteId site, URI uri) {}

    private static final ThreadLocal<Note> NOTE = new ThreadLocal<>();

    private RedirectNote() {}

    static void note(SiteId site, URI uri) {
        NOTE.set(new Note(site, uri));
    }

    /**
     * The noted redirect, when the resolution's answer is the site it was noted for. Only the site is compared, not the
     * resolver that gave the answer: another resolver answering the same site keeps the redirect.
     */
    static Optional<URI> take(Optional<SiteId> answer) {
        Note note = NOTE.get();
        NOTE.remove();
        if (note == null || answer.isEmpty() || !answer.get().equals(note.site())) {
            return Optional.empty();
        }
        return Optional.of(note.uri());
    }

    static void clear() {
        NOTE.remove();
    }
}
