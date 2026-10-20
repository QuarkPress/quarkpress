package org.quarkpress.it.security;

import io.quarkus.security.PermissionChecker;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.PostFacts;

/** The meta capability edit_post, answered for the post a secured method receives. */
@ApplicationScoped
public class PostChecks {

    @Inject
    Capabilities capabilities;

    @PermissionChecker("edit_post")
    @Blocking
    public boolean canEdit(SecurityIdentity identity, PostFacts post) {
        return capabilities.can(identity, "edit_post", Optional.of(post));
    }
}
