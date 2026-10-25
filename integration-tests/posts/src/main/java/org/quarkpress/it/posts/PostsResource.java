package org.quarkpress.it.posts;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.persistence.OptimisticLockException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.QuarkPressPrincipal;

@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
public class PostsResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    Posts posts;

    @Inject
    Memberships memberships;

    @GET
    @Path("/me")
    public Map<String, Object> me() {
        Map<String, Object> me = new LinkedHashMap<>();
        me.put("anonymous", identity.isAnonymous());
        QuarkPressPrincipal.of(identity)
                .ifPresent(principal -> me.put("user", principal.user().value()));
        return me;
    }

    @POST
    @Path("/memberships")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<String> member(MembershipRequest request) {
        return memberships
                .add(SiteId.of(1), UserId.of(request.user()), request.role())
                .roles();
    }

    @POST
    @Path("/posts")
    @Consumes(MediaType.APPLICATION_JSON)
    public PostView create(PostRequest request) {
        return PostView.of(posts.create(changes(request).build()));
    }

    @PUT
    @Path("/posts/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public PostView update(@PathParam("id") long id, PostRequest request) {
        return PostView.of(posts.update(PostId.of(id), changes(request).build()));
    }

    @GET
    @Path("/posts/{id}")
    public PostView find(@PathParam("id") long id) {
        return PostView.of(posts.find(PostId.of(id)).orElseThrow(NotFoundException::new));
    }

    @POST
    @Path("/posts/{id}/trash")
    @Produces(MediaType.TEXT_PLAIN)
    public String trash(@PathParam("id") long id) {
        return status(posts.trash(PostId.of(id)));
    }

    @POST
    @Path("/posts/{id}/untrash")
    @Produces(MediaType.TEXT_PLAIN)
    public String untrash(@PathParam("id") long id) {
        return status(posts.untrash(PostId.of(id)));
    }

    @DELETE
    @Path("/posts/{id}")
    @Produces(MediaType.TEXT_PLAIN)
    public String delete(@PathParam("id") long id, @QueryParam("force") @DefaultValue("false") boolean force) {
        return status(posts.delete(PostId.of(id), force));
    }

    @POST
    @Path("/posts/{id}/sticky")
    public boolean stick(@PathParam("id") long id) {
        return posts.stick(PostId.of(id));
    }

    @GET
    @Path("/posts")
    public Map<String, Object> query(
            @QueryParam("search") String search,
            @QueryParam("status") String status,
            @QueryParam("perPage") Integer perPage,
            @QueryParam("stickyFirst") @DefaultValue("false") boolean stickyFirst) {
        PostQuery.Builder query = PostQuery.builder().stickyFirst(stickyFirst);
        if (search != null) {
            query.search(search);
        }
        if (status != null) {
            query.statuses(status);
        }
        if (perPage != null) {
            query.perPage(perPage);
        }
        PostPage page = posts.query(query.build());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ids", page.posts().stream().map(post -> post.id().value()).toList());
        result.put("found", page.found());
        return result;
    }

    @ServerExceptionMapper
    public Response refused(PostException refused) {
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", refused.code()))
                .build();
    }

    @ServerExceptionMapper
    public Response stale(OptimisticLockException stale) {
        return Response.status(409).build();
    }

    private static String status(Optional<PostSnapshot> before) {
        return before.map(PostSnapshot::status).orElse("");
    }

    private static PostChanges.Builder changes(PostRequest request) {
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
        if (request.content() != null) {
            changes.content(request.content());
        }
        if (request.slug() != null) {
            changes.slug(request.slug());
        }
        if (request.parent() != null) {
            changes.parent(request.parent());
        }
        if (request.version() != null) {
            changes.expectedVersion(request.version());
        }
        if (request.dateGmt() != null) {
            changes.dateGmt(Instant.parse(request.dateGmt()));
        }
        return changes;
    }
}
