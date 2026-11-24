package org.quarkword.it.posts;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.quarkword.core.PostId;
import org.quarkword.core.TermId;
import org.quarkword.posts.PostException;
import org.quarkword.taxonomy.TermException;
import org.quarkword.taxonomy.TermRef;
import org.quarkword.taxonomy.Terms;

@Path("/it/posts/{id}/terms")
@Produces(MediaType.APPLICATION_JSON)
public class PostTermsResource {

    @Inject
    Terms terms;

    @PUT
    @Path("/{taxonomy}")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<Long> assign(@PathParam("id") long id, @PathParam("taxonomy") String taxonomy, TermRequest request) {
        List<TermRef> refs = request == null || request.names() == null
                ? List.of()
                : request.names().stream().map(TermRef::name).toList();
        return terms.setPostTerms(PostId.of(id), taxonomy, refs, false).stream()
                .map(TermId::value)
                .toList();
    }

    @GET
    @Path("/{taxonomy}")
    public List<TermView> postTerms(@PathParam("id") long id, @PathParam("taxonomy") String taxonomy) {
        return terms.postTerms(PostId.of(id), taxonomy).stream()
                .map(TermView::of)
                .toList();
    }

    @ServerExceptionMapper
    public Response refused(TermException refused) {
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", refused.code()))
                .build();
    }

    @ServerExceptionMapper
    public Response refusedPost(PostException refused) {
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", refused.code()))
                .build();
    }
}
