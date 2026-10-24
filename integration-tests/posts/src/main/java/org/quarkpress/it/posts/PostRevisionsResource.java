package org.quarkpress.it.posts;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.posts.PostException;
import org.quarkpress.revisions.RevisionCount;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.RevisionQuery;
import org.quarkpress.revisions.Revisions;

@Path("/it/posts/{id}/revisions")
@Produces(MediaType.APPLICATION_JSON)
public class PostRevisionsResource {

    @Inject
    Revisions revisions;

    @GET
    public List<RevisionView> list(@PathParam("id") long id) {
        return revisions.ofPost(PostId.of(id), RevisionQuery.all()).stream()
                .map(RevisionView::of)
                .toList();
    }

    @GET
    @Path("/count")
    public Map<String, Object> count(@PathParam("id") long id) {
        RevisionCount found = revisions.latestAndCount(PostId.of(id));
        Map<String, Object> count = new LinkedHashMap<>();
        count.put("latest", found.latest().map(RevisionId::value).orElse(0L));
        count.put("count", found.count());
        return count;
    }

    @ServerExceptionMapper
    public Response refused(RevisionException refused) {
        return RevisionRefusals.of(refused);
    }

    @ServerExceptionMapper
    public Response refusedPost(PostException refused) {
        return RevisionRefusals.of(refused);
    }
}
