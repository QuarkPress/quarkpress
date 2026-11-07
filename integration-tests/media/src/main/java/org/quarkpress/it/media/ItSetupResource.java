package org.quarkpress.it.media;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.UserId;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.Sites;
import org.quarkpress.persistence.NetworkOptions;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.Posts;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.SystemIdentity;

/**
 * Test scaffolding, not part of any API: the caller's id, roles on the main site, posts to attach media to and a site
 * in a subdirectory, set up through the modules' Java services so that the media tests can upload as those users, to
 * those posts and on that site. The media parity run also sets posts' dates, network options and a post's protected
 * meta (its featured image) here, the last two for a caller with {@code manage_options} only.
 */
@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
public class ItSetupResource {

    /** A role to give a user (by id) on the bound site. */
    public record MembershipRequest(long user, String role) {}

    /**
     * A post to create on the bound site, written by the caller; {@code date}, when given, is its local post date
     * ({@code yyyy-MM-dd HH:mm:ss}).
     */
    public record PostRequest(String type, String status, String title, String date) {}

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

    @Inject
    PostMeta postMeta;

    @Inject
    NetworkOptions networkOptions;

    @Inject
    Capabilities capabilities;

    @Inject
    SystemIdentity system;

    @Inject
    SiteScope siteScope;

    private static final DateTimeFormatter MYSQL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

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

    /**
     * Creates a site at {@code path} on the bound site's domain, with the multisite module's checks; answers its id.
     */
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
        if (request.date() != null) {
            changes.date(LocalDateTime.parse(request.date(), MYSQL_DATE));
        }
        return Map.of("id", posts.create(changes.build()).id().value());
    }

    /** The values of a meta key of a post of the bound site, read as the system (protected keys included). */
    @GET
    @Path("/posts/{id}/meta/{key}")
    public List<JsonNode> meta(@PathParam("id") long id, @PathParam("key") String key) {
        requireManageOptions();
        return asSystem(site.siteId(), () -> postMeta.get(PostId.of(id), key));
    }

    /** Sets a meta key of a post of the bound site, as the system (protected keys included); answers the values. */
    @PUT
    @Path("/posts/{id}/meta/{key}")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<JsonNode> setMeta(@PathParam("id") long id, @PathParam("key") String key, JsonNode value) {
        requireManageOptions();
        return asSystem(site.siteId(), () -> {
            postMeta.update(PostId.of(id), key, value);
            return postMeta.get(PostId.of(id), key);
        });
    }

    /** A network option: {@code value}, null when the option is not set. */
    @GET
    @Path("/network-options/{name}")
    public Map<String, Object> networkOption(@PathParam("name") String name) {
        requireManageOptions();
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("value", networkOptions.find(name).orElse(NullNode.getInstance()));
        answer.put("exists", networkOptions.find(name).isPresent());
        return answer;
    }

    /** Sets a network option; a JSON null removes it. Answers whether it changed. */
    @PUT
    @Path("/network-options/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> setNetworkOption(@PathParam("name") String name, JsonNode value) {
        requireManageOptions();
        boolean changed =
                value == null || value.isNull() ? networkOptions.delete(name) : networkOptions.set(name, value);
        return Map.of("changed", changed);
    }

    private void requireManageOptions() {
        if (!capabilities.can("manage_options")) {
            throw new ForbiddenException();
        }
    }

    /**
     * Runs {@code work} as the system in the scope of {@code siteId}, on a thread of its own (the system identity
     * refuses to run inside an HTTP request), and waits for it.
     */
    private <T> T asSystem(SiteId siteId, Callable<T> work) {
        FutureTask<T> task = new FutureTask<>(() -> siteScope.call(siteId, () -> system.call(work)));
        Thread.ofVirtual().start(task);
        try {
            return task.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (ExecutionException e) {
            if (e.getCause() instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(e.getCause());
        }
    }
}
