package org.quarkpress.it.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Iterator;
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
import org.quarkpress.persistence.Options;
import org.quarkpress.persistence.SiteScope;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.Posts;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveResult;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.Revisions;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.SystemIdentity;
import org.quarkpress.taxonomy.PostFormats;
import org.quarkpress.taxonomy.TermRef;
import org.quarkpress.taxonomy.Terms;

/**
 * Test scaffolding, not part of any API: sets up what the REST API (wp/v2) does not serve yet (memberships, sites,
 * posts with their terms and meta, revisions, autosaves, edit locks and options) through the modules' Java services,
 * so that the tests can then exercise the REST API on that state. Every call acts on the site the request is bound to
 * (by its Host header and path) and as the caller: {@code /it/...} on the main site, {@code /<site>/it/...}
 * ({@link ItSiteSetupResource}) on a subdirectory site. A post's sticky flag, terms and meta ({@code _thumbnail_id},
 * {@code _wp_page_template}, {@code _edit_lock}, registered meta) are written as the system, on a thread of its own in
 * the site's scope: the request's own identity may not write them, and WordPress's setup functions check no
 * capability for them.
 */
@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
public class ItSetupResource {

    /** A role to give a user (by id) on the bound site. */
    public record MembershipRequest(long user, String role) {}

    /** A new site in a subdirectory of the bound site's domain, such as {@code /s-1234/}. */
    public record SiteRequest(String path, String title) {}

    /**
     * A post to create on the bound site, written by the caller. {@code fields} (all optional): {@code content},
     * {@code excerpt}, {@code password}, {@code parent} (a post id), {@code date_gmt} ({@code yyyy-MM-ddTHH:mm:ss}),
     * {@code slug}, {@code menu_order}, {@code comment_status}, {@code sticky}, {@code categories} and {@code tags}
     * (term ids), {@code format} (a post format slug), {@code featured_media} (an attachment id), {@code template},
     * {@code meta} ({key: value}).
     */
    public record PostRequest(String type, String status, String title, JsonNode fields) {}

    /** An update of a post's title and content by the caller, whose revision is answered. */
    public record RevisionRequest(long post, String title, String content) {}

    /** An autosave of a post by the caller. */
    public record AutosaveRequest(long post, String title, String content) {}

    /** The edit lock of a post: held by {@code user} since {@code age} seconds ago. */
    public record LockRequest(long user, int age) {}

    /**
     * The dates a scenario's setup leaves on one post: its modification date set to {@code at} ({@code
     * yyyy-MM-ddTHH:mm:ss}, UTC), and its post date too when {@code date} is true and the post has one.
     */
    public record Backdate(long id, String at, boolean date) {}

    /** The dates to leave on the setup's posts. */
    public record BackdateRequest(List<Backdate> posts) {}

    /** A user (by id) who leaves the bound site and becomes administrator of a new site at {@code path}. */
    public record OwnSiteRequest(long user, String path, String title) {}

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
    Terms terms;

    @Inject
    PostFormats postFormats;

    @Inject
    Revisions revisions;

    @Inject
    Options options;

    @Inject
    Capabilities capabilities;

    @Inject
    SystemIdentity system;

    @Inject
    SiteScope siteScope;

    @Inject
    PostRepository postRows;

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

    /** Removes the user (by id) from the bound site; answers whether the user was a member. */
    @DELETE
    @Path("/memberships/{user}")
    public Map<String, Object> leave(@PathParam("user") long user) {
        return Map.of("left", memberships.remove(site.siteId(), UserId.of(user)));
    }

    /** Creates a site at {@code path} on the bound site's domain; answers its id. */
    @POST
    @Path("/sites")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> site(SiteRequest request) {
        SiteInfo created = sites.create(NewSite.of(site.site().domain(), request.path(), request.title()));
        return Map.of("id", created.id().value());
    }

    /**
     * The user leaves the bound site and becomes administrator of a new site at {@code path} on the bound site's
     * domain, for a caller with {@code manage_options}; answers the new site's id.
     */
    @POST
    @Path("/own-sites")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> ownSite(OwnSiteRequest request) {
        requireManageOptions();
        SiteInfo created = sites.create(NewSite.of(site.site().domain(), request.path(), request.title()));
        UserId user = UserId.of(request.user());
        memberships.setRole(created.id(), user, "administrator");
        memberships.remove(site.siteId(), user);
        return Map.of("id", created.id().value());
    }

    /** Creates a post on the bound site as the caller, with its fields; answers its id. */
    @POST
    @Path("/posts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> post(PostRequest request) {
        JsonNode fields = request.fields() == null ? JsonNodeFactory.instance.objectNode() : request.fields();
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
        for (Iterator<Map.Entry<String, JsonNode>> it = fields.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            JsonNode value = field.getValue();
            switch (field.getKey()) {
                case "content" -> changes.content(value.asText());
                case "excerpt" -> changes.excerpt(value.asText());
                case "password" -> changes.password(value.asText());
                case "parent" -> changes.parent(value.asLong());
                case "date_gmt" ->
                    changes.dateGmt(LocalDateTime.parse(value.asText()).toInstant(ZoneOffset.UTC));
                case "slug" -> changes.slug(value.asText());
                case "menu_order" -> changes.menuOrder(value.asInt());
                case "comment_status" -> changes.commentStatus(value.asText());
                default -> {}
            }
        }
        PostId created = posts.create(changes.build()).id();
        Map<String, JsonNode> meta = new LinkedHashMap<>();
        if (fields.has("featured_media")) {
            meta.put(
                    "_thumbnail_id",
                    TextNode.valueOf(fields.get("featured_media").asText()));
        }
        if (fields.has("template")) {
            meta.put(
                    "_wp_page_template", TextNode.valueOf(fields.get("template").asText()));
        }
        if (fields.path("meta").isObject()) {
            fields.get("meta").fields().forEachRemaining(entry -> meta.put(entry.getKey(), entry.getValue()));
        }
        boolean sticky = fields.path("sticky").asBoolean(false);
        boolean hasTerms = fields.has("categories") || fields.has("tags") || fields.has("format");
        if (sticky || hasTerms || !meta.isEmpty()) {
            // as WordPress's stick_post, wp_set_post_terms, set_post_format and update_post_meta, these check no
            // capability
            asSystem(site.siteId(), () -> {
                if (sticky) {
                    posts.stick(created);
                }
                if (fields.has("format")) {
                    postFormats.set(created, fields.get("format").asText());
                }
                for (String key : List.of("categories", "tags")) {
                    if (fields.has(key)) {
                        List<TermRef> refs = new ArrayList<>();
                        fields.get(key).forEach(id -> refs.add(TermRef.id(id.asLong())));
                        terms.setPostTerms(created, key.equals("categories") ? "category" : "post_tag", refs, false);
                    }
                }
                meta.forEach((key, value) -> postMeta.update(created, key, value));
                return null;
            });
        }
        return Map.of("id", created.value());
    }

    /** Updates the post's title and content as the caller; answers the id of the revision the update made. */
    @POST
    @Path("/revisions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> revision(RevisionRequest request) {
        PostId post = PostId.of(request.post());
        List<Long> before = revisionIds(post);
        posts.update(
                post,
                PostChanges.builder()
                        .title(request.title())
                        .content(request.content())
                        .build());
        long newest = 0;
        for (long id : revisionIds(post)) {
            if (!before.contains(id) && id > newest) {
                newest = id;
            }
        }
        if (newest == 0) {
            throw new WebApplicationException(Response.status(409)
                    .entity(Map.of("error", "the update of post " + request.post() + " made no revision"))
                    .build());
        }
        return Map.of("id", newest);
    }

    private List<Long> revisionIds(PostId post) {
        List<Long> ids = new ArrayList<>();
        for (RevisionSnapshot revision : revisions.ofPost(post, RevisionQuery.all())) {
            ids.add(revision.id().value());
        }
        return ids;
    }

    /** Autosaves the post as the caller; answers the autosave's id (the post's own when its draft was updated). */
    @POST
    @Path("/autosaves")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> autosave(AutosaveRequest request) {
        AutosaveResult result = revisions.autosave(
                PostId.of(request.post()),
                AutosaveChanges.builder()
                        .title(request.title())
                        .content(request.content())
                        .build());
        long id = switch (result) {
            case AutosaveResult.Draft draft -> draft.post().id().value();
            case AutosaveResult.Stored stored -> stored.autosave().id().value();
        };
        return Map.of("id", id);
    }

    /**
     * Sets the post's edit lock to {@code "<now - age>:<user>"}, for a caller with {@code manage_options}; answers the
     * value.
     */
    @PUT
    @Path("/locks/{post}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> lock(@PathParam("post") long post, LockRequest request) {
        requireManageOptions();
        String value = (Instant.now().getEpochSecond() - request.age()) + ":" + request.user();
        asSystem(site.siteId(), () -> {
            postMeta.update(PostId.of(post), "_edit_lock", TextNode.valueOf(value));
            return null;
        });
        return Map.of("value", value);
    }

    /**
     * Sets the dates of posts of the bound site past the services, for a caller with {@code manage_options}: the row
     * alone changes (no version, no event, no revision), as WordPress's setup writes the columns; answers how many
     * posts changed.
     */
    @PUT
    @Path("/backdate")
    @Consumes(MediaType.APPLICATION_JSON)
    @Transactional
    public Map<String, Object> backdate(BackdateRequest request) {
        requireManageOptions();
        int changed = 0;
        for (Backdate post : request.posts()) {
            Instant at = LocalDateTime.parse(post.at()).toInstant(ZoneOffset.UTC);
            changed += postRows.getEntityManager()
                    .createQuery("update PostEntity p set p.modifiedAt = :at where p.siteId = :site and p.id = :id")
                    .setParameter("at", at)
                    .setParameter("site", TenantIds.param(site.siteId()))
                    .setParameter("id", post.id())
                    .executeUpdate();
            if (post.date()) {
                postRows.getEntityManager()
                        .createQuery("update PostEntity p set p.publishedAt = :at where p.siteId = :site"
                                + " and p.id = :id and p.publishedAt is not null")
                        .setParameter("at", at)
                        .setParameter("site", TenantIds.param(site.siteId()))
                        .setParameter("id", post.id())
                        .executeUpdate();
            }
        }
        return Map.of("changed", changed);
    }

    /** Sets an option of the bound site, for a caller with {@code manage_options} there; answers whether it changed. */
    @PUT
    @Path("/options/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> option(@PathParam("name") String name, JsonNode value) {
        requireManageOptions();
        return Map.of("changed", options.set(name, value));
    }

    /** The calls that write as the system or across sites are for a caller with {@code manage_options} only. */
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
