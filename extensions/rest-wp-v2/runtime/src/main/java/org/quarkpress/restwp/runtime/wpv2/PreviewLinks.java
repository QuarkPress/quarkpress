package org.quarkpress.restwp.runtime.wpv2;

import java.util.Optional;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;

/**
 * WordPress's preview link of a post, as its autosaves answers carry it: the empty text for a type that is not
 * viewable; otherwise the post's link followed (with {@code &} when the link has a query, else {@code ?}) by
 * {@code preview_id=<post id>&preview_nonce=<token>&} when a preview token is given, and {@code preview=true} last.
 */
final class PreviewLinks {

    private PreviewLinks() {}

    /**
     * The preview link of {@code post}, a post of {@code routes}' type.
     *
     * @param link the post's link (its plain permalink)
     * @param token the caller's preview token of the post; none for the link of a draft the caller saved itself
     */
    static String of(PostRoutes routes, PostSnapshot post, String link, Optional<String> token) {
        if (!PostPermissions.typeViewable(routes.type())) {
            return "";
        }
        StringBuilder preview = new StringBuilder(link).append(link.indexOf('?') >= 0 ? '&' : '?');
        token.ifPresent(nonce -> preview.append("preview_id=")
                .append(post.id().value())
                .append("&preview_nonce=")
                .append(nonce)
                .append('&'));
        return preview.append("preview=true").toString();
    }
}
