package org.quarkpress.it.posts;

import jakarta.inject.Inject;
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
import java.util.List;
import java.util.Map;
import org.jboss.resteasy.reactive.server.ServerExceptionMapper;
import org.quarkpress.core.TermId;
import org.quarkpress.taxonomy.TermChanges;
import org.quarkpress.taxonomy.TermException;
import org.quarkpress.taxonomy.TermQuery;
import org.quarkpress.taxonomy.Terms;

@Path("/it/terms")
@Produces(MediaType.APPLICATION_JSON)
public class TermsResource {

    @Inject
    Terms terms;

    @POST
    @Path("/{taxonomy}")
    @Consumes(MediaType.APPLICATION_JSON)
    public TermView create(@PathParam("taxonomy") String taxonomy, TermRequest request) {
        return TermView.of(terms.create(taxonomy, changes(request).build()));
    }

    @PUT
    @Path("/{taxonomy}/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    public TermView update(@PathParam("taxonomy") String taxonomy, @PathParam("id") long id, TermRequest request) {
        return TermView.of(
                terms.update(TermId.of(id), taxonomy, changes(request).build()));
    }

    @DELETE
    @Path("/{taxonomy}/{id}")
    public TermView delete(@PathParam("taxonomy") String taxonomy, @PathParam("id") long id) {
        return terms.delete(TermId.of(id), taxonomy).map(TermView::of).orElseThrow(NotFoundException::new);
    }

    @GET
    public List<TermView> query(
            @QueryParam("taxonomy") String taxonomy,
            @QueryParam("search") String search,
            @QueryParam("slug") String slug,
            @QueryParam("hideEmpty") @DefaultValue("true") boolean hideEmpty) {
        TermQuery.Builder query = TermQuery.builder().hideEmpty(hideEmpty);
        if (taxonomy != null) {
            query.taxonomies(taxonomy);
        }
        if (search != null) {
            query.search(search);
        }
        if (slug != null) {
            query.slugs(slug);
        }
        return terms.query(query.build()).stream().map(TermView::of).toList();
    }

    @ServerExceptionMapper
    public Response refused(TermException refused) {
        return Response.status(400)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("code", refused.code()))
                .build();
    }

    private static TermChanges.Builder changes(TermRequest request) {
        TermChanges.Builder changes = TermChanges.builder();
        if (request == null) {
            // an empty body changes nothing
            return changes;
        }
        if (request.name() != null) {
            changes.name(request.name());
        }
        if (request.slug() != null) {
            changes.slug(request.slug());
        }
        if (request.description() != null) {
            changes.description(request.description());
        }
        if (request.parent() != null) {
            changes.parent(request.parent());
        }
        return changes;
    }
}
