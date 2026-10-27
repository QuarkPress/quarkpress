package org.quarkpress.devui.runtime;

/**
 * The revisions module's part of the Dev UI's content reads, for the current site. A bean only when the revisions
 * module is installed; this interface names no type of that module, so the content provider can ask for it either way.
 */
public interface RevisionReads {

    /** The number of revisions of the current site's posts, autosaves included. */
    long count();
}
