package org.quarkword.front.runtime;

import java.util.Optional;
import org.quarkword.core.PostId;

/**
 * The public URL of an attachment's file, in plain types: a bean only while the media module is present, so callers
 * ask {@code Instance<AttachmentFiles>.isResolvable()} first; without it there is no file to redirect to.
 */
public interface AttachmentFiles {

    /**
     * WordPress's {@code wp_get_attachment_url}: the main file's URL of the current site's attachment {@code attachment},
     * read past capabilities; empty for a post that is not an attachment with a file, and while the site's media
     * feature is off.
     */
    Optional<String> url(PostId attachment);
}
