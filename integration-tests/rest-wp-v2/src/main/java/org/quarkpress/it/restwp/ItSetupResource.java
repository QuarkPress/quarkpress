package org.quarkpress.it.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
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
import org.quarkpress.persistence.Options;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.Posts;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.QuarkPressPrincipal;

/**
 * Test scaffolding, not part of any API: sets up what the REST API (wp/v2) does not serve yet — memberships, sites,
 * posts and options — through the modules' Java services, so that the tests can then exercise the REST API on that
 * state. Every call acts on the site the request is bound to (by its Host header and path) and as the caller:
 * {@code /it/...} on the main site, {@code /<site>/it/...} ({@link ItSiteSetupResource}) on a subdirectory site.
 */
@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
public class ItSetupResource {

    /** A role to give a user (by id) on the bound site. */
    public record MembershipRequest(long user, String role) {}

    /** A new site in a subdirectory of the bound site's domain, such as {@code /s-1234/}. */
    public record SiteRequest(String path, String title) {}

    /** A post to create on the bound site, written by the caller. */
    public record PostRequest(String type, String status, String title) {}

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

    @Inject
    Options options;

    @Inject
    Capabilities capabilities;

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

    /** Creates a site at {@code path} on the bound site's domain; answers its id. */
    @POST
    @Path("/sites")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> site(SiteRequest request) {
        SiteInfo created = sites.create(NewSite.of(site.site().domain(), request.path(), request.title()));
        return Map.of("id", created.id().value());
    }

    /** Creates a post on the bound site as the caller; answers its id. */
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

    /** Sets an option of the bound site, for a caller with {@code manage_options} there; answers whether it changed. */
    @PUT
    @Path("/options/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> option(@PathParam("name") String name, JsonNode value) {
        if (!capabilities.can("manage_options")) {
            throw new ForbiddenException();
        }
        return Map.of("changed", options.set(name, value));
    }
}
