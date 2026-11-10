package org.quarkpress.restwp.runtime.wpv2;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.Optional;
import org.quarkpress.posts.PostSnapshot;

/**
 * Whether the media routes exist: a bean only while the media module is present, so a class loaded without that
 * module asks {@code Instance<MediaRoutesPresent>.isResolvable()} first, then {@link #served()}.
 */
public interface MediaRoutesPresent {

    /**
     * Whether the media routes answer on the current request's site: its media feature on, attachments shown in
     * REST.
     */
    boolean served();

    /**
     * The URL of the featured image {@code post} names, as the media routes link it: when the image exists on this
     * site, its type is shown in REST, and it is published in effect or {@code caller} may read it; none otherwise.
     * The image is read once per request, however many posts name it.
     */
    Optional<String> featuredMedia(PostSnapshot post, SecurityIdentity caller);
}
