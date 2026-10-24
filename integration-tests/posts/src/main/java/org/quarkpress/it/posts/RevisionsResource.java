package org.quarkpress.it.posts;

import jakarta.inject.Inject;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Set;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.quarkpress.core.RevisionId;
import org.quarkpress.posts.PostException;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.Revisions;

@Path("/it/revisions")
@Produces(MediaType.APPLICATION_JSON)
public class RevisionsResource {

    @Inject
    Revisions revisions;

    @POST
    @Path("/{id}/restore")
    public PostView restore(@PathParam("id") long id) {
        return PostView.of(revisions.restore(RevisionId.of(id), Set.of()));
    }

    @DELETE
    @Path("/{id}")
    public RevisionView delete(@PathParam("id") long id) {
        return new RevisionView(id, 0, null, "", "", "", false, "");
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
