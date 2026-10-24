package org.quarkpress.it.posts;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.quarkpress.core.PostId;
import org.quarkpress.posts.PostException;
import org.quarkpress.revisions.AutosaveChanges;
import org.quarkpress.revisions.AutosaveResult;
import org.quarkpress.revisions.RevisionException;
import org.quarkpress.revisions.Revisions;

@Path("/it/posts/{id}/autosaves")
@Produces(MediaType.APPLICATION_JSON)
public class PostAutosavesResource {

    @Inject
    Revisions revisions;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Object autosave(@PathParam("id") long id, AutosaveRequest request) {
        AutosaveChanges.Builder changes = AutosaveChanges.builder();
        if (request != null) {
            if (request.title() != null) {
                changes.title(request.title());
            }
            if (request.content() != null) {
                changes.content(request.content());
            }
            if (request.excerpt() != null) {
                changes.excerpt(request.excerpt());
            }
        }
        return switch (revisions.autosave(PostId.of(id), changes.build())) {
            case AutosaveResult.Draft draft -> Map.of("post", PostView.of(draft.post()));
            case AutosaveResult.Stored stored -> RevisionView.of(stored.autosave());
        };
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
