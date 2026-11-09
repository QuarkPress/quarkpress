package org.quarkpress.restwp.runtime.wpv2;

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
}
