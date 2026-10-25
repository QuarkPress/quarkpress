package org.quarkpress.it.posts;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.security.Authenticated;
import io.quarkus.security.PermissionsAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.FeatureToggles;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.scheduling.SchedulingFeature;
import org.quarkpress.scheduling.SchedulingStatus;
import org.quarkpress.scheduling.runtime.Housekeeping;

/**
 * The scheduling module over HTTP: what this node published, its jobs' status, a housekeeping run, the site's
 * scheduling switch, and three row fixtures that put a post where a test needs it without waiting.
 */
@Path("/it/scheduling")
@Produces(MediaType.APPLICATION_JSON)
public class SchedulingResource {

    @Inject
    PublishedPosts published;

    @Inject
    SchedulingStatus status;

    @Inject
    Housekeeping housekeeping;

    @Inject
    FeatureToggles toggles;

    @Inject
    PostRepository posts;

    /** Whether the site's scheduling feature is on. */
    public record Enabled(boolean enabled) {}

    /** A trash time, in seconds since the epoch. */
    public record Seconds(long seconds) {}

    /** A modification time, an ISO-8601 instant. */
    public record At(String at) {}

    @GET
    @Path("/published")
    public Map<String, Object> published() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", published.ids().size());
        result.put("posts", published.ids());
        return result;
    }

    @GET
    @Path("/status")
    public Map<String, Object> status() {
        SchedulingStatus.Snapshot snapshot = status.snapshot();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(
                "lastPollEnded", snapshot.lastPollEnded().map(Instant::toString).orElse(null));
        result.put(
                "lastBatch",
                snapshot.lastBatch().stream()
                        .map(published -> published.post().value())
                        .toList());
        result.put("publishedSinceStart", snapshot.publishedSinceStart());
        result.put(
                "lastHousekeeping",
                snapshot.lastHousekeeping().map(Instant::toString).orElse(null));
        return result;
    }

    @POST
    @Path("/housekeeping")
    @Authenticated
    public Map<String, Object> housekeeping() {
        Housekeeping.Result result = housekeeping.run();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("trash", result.trashPurged());
        body.put("autoDrafts", result.autoDraftsPurged());
        return body;
    }

    @PUT
    @Path("/feature")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed("manage_options")
    public Map<String, Object> feature(Enabled request) {
        toggles.setForSite(SchedulingFeature.KEY, request.enabled());
        return Map.of("enabled", request.enabled());
    }

    /** Test fixture: makes the scheduled post due by dating it a second ago, bypassing the posts service. */
    @POST
    @Path("/due/{id}")
    @PermissionsAllowed("manage_options")
    public Map<String, Object> due(@PathParam("id") long id) {
        return fixture(id, row -> row.publishedAt = Instant.now().minusSeconds(1));
    }

    /**
     * Test fixture: replaces the post's trash time (the meta WordPress writes when trashing), bypassing the service.
     */
    @POST
    @Path("/trash-time/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed("manage_options")
    public Map<String, Object> trashTime(@PathParam("id") long id, Seconds request) {
        return fixture(id, row -> {
            ObjectNode meta = row.meta != null && row.meta.isObject()
                    ? ((ObjectNode) row.meta).deepCopy()
                    : JsonNodeFactory.instance.objectNode();
            meta.set("_wp_trash_meta_time", meta.arrayNode().add(request.seconds()));
            row.meta = meta;
        });
    }

    /** Test fixture: backdates the post's modification time, bypassing the service. */
    @POST
    @Path("/modified/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @PermissionsAllowed("manage_options")
    public Map<String, Object> modified(@PathParam("id") long id, At request) {
        return fixture(id, row -> row.modifiedAt = Instant.parse(request.at()));
    }

    private Map<String, Object> fixture(long id, Consumer<PostEntity> change) {
        QuarkusTransaction.requiringNew().run(() -> {
            PostEntity row = posts.findPost(SiteId.of(1), PostId.of(id)).orElseThrow(NotFoundException::new);
            change.accept(row);
        });
        return Map.of("id", id);
    }
}
