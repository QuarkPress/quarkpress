package org.quarkpress.it.media;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.UserId;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.Sites;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.QuarkPressPrincipal;

/**
 * Test scaffolding, not part of any API: the caller's id, roles on the main site, posts to attach media to and a site in
 * a subdirectory, set up through the modules' Java services so that the media tests can upload as those users, to
 * those posts and on that site.
 */
@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
public class ItSetupResource {

    /** A role to give a user (by id) on the bound site. */
    public record MembershipRequest(long user, String role) {}

    /** A post to create on the bound site, written by the caller. */
    public record PostRequest(String type, String status, String title) {}

    /** A new site in a subdirectory of the bound site's domain, such as {@code /s-1234/}. */
    public record SiteRequest(String path, String title) {}

    @Inject
    SecurityIdentity identity;

    @Inject
    SiteContext site;

    @Inject
    Memberships memberships;

    @Inject
    Sites sites;

    @Inject
    Posts posts;

    /** The caller's user id; no {@code user} for an anonymous caller. */
    @GET
    @Path("/me")
    public Map<String, Object> me() {
        Map<String, Object> me = new LinkedHashMap<>();
        QuarkPressPrincipal.of(identity)
                .ifPresent(principal -> me.put("user", principal.user().value()));
        return me;
    }

    /** Replaces the user's roles on the bound site with the given one; answers the roles. */
    @POST
    @Path("/memberships")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<String> membership(MembershipRequest request) {
        return memberships
                .setRole(site.siteId(), UserId.of(request.user()), request.role())
                .roles();
    }

    /** Creates a site at {@code path} on the bound site's domain, with the multisite module's checks; answers its id. */
    @POST
    @Path("/sites")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> site(SiteRequest request) {
        SiteInfo created = sites.create(NewSite.of(site.site().domain(), request.path(), request.title()));
        return Map.of("id", created.id().value());
    }

    /** Creates a post on the bound site as the caller, with the posts module's checks; answers its id. */
    @POST
    @Path("/posts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> post(PostRequest request) {
        PostChanges.Builder changes = PostChanges.builder();
        if (request.type() != null) {
            changes.type(request.type());
        }
        if (request.status() != null) {
            changes.status(request.status());
        }
        if (request.title() != null) {
            changes.title(request.title());
        }
        return Map.of("id", posts.create(changes.build()).id().value());
    }
}
