package org.quarkword.it.front;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import org.jboss.resteasy.reactive.RestForm;
import org.jboss.resteasy.reactive.multipart.FileUpload;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteId;
import org.quarkword.media.Media;
import org.quarkword.media.MediaSnapshot;
import org.quarkword.media.Upload;
import org.quarkword.persistence.Options;
import org.quarkword.persistence.SiteScope;
import org.quarkword.posts.PostChanges;
import org.quarkword.posts.Posts;
import org.quarkword.security.Capabilities;
import org.quarkword.security.SystemIdentity;
import org.quarkword.taxonomy.TermChanges;
import org.quarkword.taxonomy.TermRef;
import org.quarkword.taxonomy.TermSnapshot;
import org.quarkword.taxonomy.Terms;

/**
 * Test scaffolding, not part of any API: sets up the site's posts, terms, options and uploaded attachments through the
 * modules' Java services, so that the tests can then request the site's public pages. Every call acts on the site as
 * the caller. A post's categories are written as the system, on a thread of its own in the site's scope: the request's
 * own identity may not write them, and WordPress's {@code wp_set_post_terms} checks no capability.
 */
@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
public class ItSetupResource {

    /**
     * A post to create, written by the caller. {@code fields} (all optional): {@code content}, {@code password},
     * {@code date_gmt} ({@code yyyy-MM-ddTHH:mm:ss}), {@code slug}, {@code categories} (term ids).
     */
    public record PostRequest(String type, String status, String title, JsonNode fields) {}

    /** A change of a post by the caller: its {@code slug} and {@code title}, each optional. */
    public record PostUpdate(String slug, String title) {}

    /** A term to create, named {@code name}. */
    public record TermRequest(String name) {}

    @Inject
    SiteContext site;

    @Inject
    Posts posts;

    @Inject
    Terms terms;

    @Inject
    Options options;

    @Inject
    Capabilities capabilities;

    @Inject
    SystemIdentity system;

    @Inject
    SiteScope siteScope;

    @Inject
    Media media;

    /** Creates a post as the caller, with its fields; answers its id. */
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
        if (fields.has("content")) {
            changes.content(fields.get("content").asText());
        }
        if (fields.has("password")) {
            changes.password(fields.get("password").asText());
        }
        if (fields.has("date_gmt")) {
            changes.dateGmt(LocalDateTime.parse(fields.get("date_gmt").asText()).toInstant(ZoneOffset.UTC));
        }
        if (fields.has("slug")) {
            changes.slug(fields.get("slug").asText());
        }
        PostId created = posts.create(changes.build()).id();
        if (fields.has("categories")) {
            List<TermRef> refs = new ArrayList<>();
            fields.get("categories").forEach(id -> refs.add(TermRef.id(id.asLong())));
            asSystem(site.siteId(), () -> {
                terms.setPostTerms(created, "category", refs, false);
                return null;
            });
        }
        return Map.of("id", created.value());
    }

    /** Changes the post's slug and title as the caller (WordPress then remembers an old slug); answers its slug. */
    @PUT
    @Path("/posts/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> update(@PathParam("id") long id, PostUpdate request) {
        PostChanges.Builder changes = PostChanges.builder();
        if (request.slug() != null) {
            changes.slug(request.slug());
        }
        if (request.title() != null) {
            changes.title(request.title());
        }
        return Map.of("slug", posts.update(PostId.of(id), changes.build()).slug());
    }

    /** Creates a term of the taxonomy as the caller; answers its id and slug. */
    @POST
    @Path("/terms/{taxonomy}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> term(@PathParam("taxonomy") String taxonomy, TermRequest request) {
        TermSnapshot created = terms.create(
                taxonomy, TermChanges.builder().name(request.name()).build());
        return Map.of("id", created.id().value(), "slug", created.slug());
    }

    /** Sets an option of the site, for a caller with {@code manage_options}; answers whether it changed. */
    @PUT
    @Path("/options/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> option(@PathParam("name") String name, JsonNode value) {
        if (!capabilities.can("manage_options")) {
            throw new ForbiddenException();
        }
        return Map.of("changed", options.set(name, value));
    }

    /**
     * Uploads the {@code file} part with its declared type as the caller, through the media module's upload (outside
     * any transaction, as it requires), attached to {@code post} when given; answers the attachment's id. A missing
     * file part or a {@code post} that is no number answers 400.
     */
    @POST
    @Path("/media")
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    public Map<String, Object> media(@RestForm("file") FileUpload file, @RestForm("post") String post) {
        if (file == null) {
            throw new WebApplicationException(Response.status(400)
                    .entity(Map.of("error", "a file part is required"))
                    .build());
        }
        Upload.Builder upload = Upload.builder(file.fileName(), file.uploadedFile());
        if (file.contentType() != null && !file.contentType().isBlank()) {
            upload.declaredType(file.contentType());
        }
        if (post != null && !post.isBlank()) {
            try {
                upload.parent(PostId.of(Long.parseLong(post.strip())));
            } catch (NumberFormatException notAnId) {
                throw new WebApplicationException(Response.status(400)
                        .entity(Map.of("error", "post is not a post id: " + post))
                        .build());
            }
        }
        MediaSnapshot stored = media.upload(upload.build());
        return Map.of("id", stored.post().id().value());
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
