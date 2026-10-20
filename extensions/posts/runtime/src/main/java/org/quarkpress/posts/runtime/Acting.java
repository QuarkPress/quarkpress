package org.quarkpress.posts.runtime;

import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.SaveFilters;
import org.quarkpress.security.CapObject;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostVisibility;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.security.runtime.CapabilityGuard;
import org.quarkpress.security.runtime.JpaCapabilities;

/** The caller of a posts service: its identity, its user, its capability checks and its save filters. */
@ApplicationScoped
public class Acting {

    @Inject
    Capabilities capabilities;

    @Inject
    CapabilityGuard guard;

    /** The current identity; null when no request context is active (a caller nobody authenticated). */
    public SecurityIdentity identity() {
        return JpaCapabilities.currentIdentity();
    }

    /** The calling user; empty for the system, an anonymous caller or an identity without QuarkPress profile. */
    public Optional<UserId> user() {
        return QuarkPressPrincipal.of(identity()).map(QuarkPressPrincipal::user);
    }

    public boolean system() {
        return SystemIdentity.isSystem(identity());
    }

    /** Whether a user is logged in (the system counts as nobody, as WordPress's cron does). */
    public boolean loggedIn() {
        return user().isPresent();
    }

    public boolean can(SiteId site, String capability, Optional<CapObject> object) {
        return capabilities.canForSite(identity(), site, capability, object);
    }

    /** Throws Quarkus's {@code UnauthorizedException} or {@code ForbiddenException} unless the caller can. */
    public void require(SiteId site, String capability, Optional<CapObject> object) {
        guard.require(site, capability, object);
    }

    /**
     * A public post can be read by anyone (its effective status is public); any other post needs {@code read_post},
     * as WordPress's REST API decides.
     */
    public void requireReadable(SiteId site, PostFacts post) {
        if (post.effectiveVisibility() != PostVisibility.PUBLIC) {
            require(site, "read_post", Optional.of(post));
        }
    }

    /**
     * Meta keys starting with an underscore are protected: only the system writes them. Leading characters outside
     * printable ASCII are skipped before the underscore is looked for ({@link MetaRows#protectedKey}).
     */
    public void requireUnprotectedKey(String key) {
        if (MetaRows.protectedKey(key) && !system()) {
            throw new ForbiddenException("the meta key " + key + " is protected");
        }
    }

    /** Kses for a caller without {@code unfiltered_html}; custom-CSS stripping without {@code edit_css}. */
    public SaveFilters saveFilters(SiteId site) {
        return new SaveFilters(
                !can(site, "unfiltered_html", Optional.empty()), !can(site, "edit_css", Optional.empty()));
    }
}
