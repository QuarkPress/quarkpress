package org.quarkpress.restwp.runtime.wpv2;

import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.RestRequest;

/**
 * Who may use the block editor's registries over REST (block types, patterns, themes): WordPress lets in a caller with
 * {@code edit_posts}, or with the {@code edit_posts} capability of any post type shown in REST (so one holding only
 * {@code edit_pages} passes too).
 */
final class EditorAccess {

    private EditorAccess() {}

    /**
     * Whether the caller has {@code edit_posts} (the capability the built-in {@code post} type edits with), or the
     * {@code edit_posts} capability of some post type shown in REST.
     */
    static boolean canEditAnyType(RestRequest request, PostTypes types, PostPermissions permissions) {
        if (permissions.canType(request.identity(), "edit_posts", "post")) {
            return true;
        }
        for (PostTypeDefinition type : types.all()) {
            if (type.showInRest() && permissions.canType(request.identity(), "edit_posts", type.key())) {
                return true;
            }
        }
        return false;
    }
}
