package org.quarkpress.it.security;

import io.quarkus.security.PermissionsAllowed;
import jakarta.enterprise.context.ApplicationScoped;
import org.quarkpress.security.PostFacts;

/** Service methods gated by WordPress capabilities. */
@ApplicationScoped
public class SecuredService {

    @PermissionsAllowed("edit_posts")
    public String draft() {
        return "drafted";
    }

    @PermissionsAllowed("manage_options")
    public String settings() {
        return "configured";
    }

    @PermissionsAllowed("edit_post")
    public String edit(PostFacts post) {
        return "edited";
    }
}
