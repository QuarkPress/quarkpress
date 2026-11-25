package org.quarkword.front;

/**
 * Renders the page of a resolved front-end request (a theme's job). The front end calls it once it decided that the
 * request is answered with a page: after the site gate, the request's bounds, the resolution, the old slug and
 * canonical redirects, and the {@code error} statuses that end a request with an empty body. The renderer gets the
 * request as WordPress resolved it (status, flags, queried object, posts, the post shown) and answers the status,
 * the headers it adds to WordPress's (already computed by the front end) and the body. The front end's own renderer,
 * a minimal page, is used when the application deploys no other bean of this type.
 */
public interface FrontRenderer {

    /** Whether the shown post's password lets the viewer see its content. */
    enum PasswordState {
        /** The post has no password, or the request shows no single post. */
        NOT_REQUIRED,
        /** The post has a password and the viewer has not given it: the form replaces the content. */
        REQUIRED,
        /** The viewer's password cookie does not match the post's password: the form replaces the content. */
        INVALID
    }

    /** The page answering {@code request} for {@code viewer}, with the shown post's {@code password} state. */
    FrontAnswer render(FrontRequest request, Viewer viewer, PasswordState password);
}
