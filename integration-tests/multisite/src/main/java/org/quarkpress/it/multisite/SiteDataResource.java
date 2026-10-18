package org.quarkpress.it.multisite;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.SiteOptions;
import org.quarkpress.persistence.Options;

@Path("/it/data")
@Produces(MediaType.APPLICATION_JSON)
public class SiteDataResource {

    @Inject
    SiteContext context;

    @Inject
    Options options;

    @Inject
    Notes notes;

    @PUT
    @Path("/options/{name}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Boolean> setOption(@PathParam("name") String name, JsonNode value) {
        return Map.of("changed", options.set(name, value));
    }

    /**
     * The option of the request's site; 404 with the text {@code no site} when the library refuses the read for want
     * of a site (the resource asks the library without checking first, so the library's own refusal is what is tested).
     */
    @GET
    @Path("/options/{name}")
    public Response option(@PathParam("name") String name) {
        SiteOptions siteOptions;
        try {
            siteOptions = context.options();
        } catch (SiteNotResolvedException noSite) {
            return Response.status(Response.Status.NOT_FOUND)
                    .type(MediaType.TEXT_PLAIN)
                    .entity("no site")
                    .build();
        }
        return siteOptions
                .get(name)
                .map(value -> Response.ok(value).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    @POST
    @Path("/notes/{text}")
    public Map<String, Long> addNote(@PathParam("text") String text) {
        return Map.of("id", notes.add(text));
    }

    @GET
    @Path("/notes")
    public List<String> notes() {
        return notes.texts();
    }

    @GET
    @Path("/notes/{id}")
    public Response note(@PathParam("id") long id) {
        return notes.textOf(id)
                .map(text -> Response.ok(Map.of("text", text)).build())
                .orElseGet(() -> Response.status(Response.Status.NOT_FOUND).build());
    }

    /**
     * {@code stored}; {@code refused} when the database's composite key to the note refused the tag; any other failure
     * is reported with its messages, so that an unrelated error never passes as isolation.
     */
    @POST
    @Path("/notes/{id}/tags/{label}")
    @Produces(MediaType.TEXT_PLAIN)
    public String tag(@PathParam("id") long id, @PathParam("label") String label) {
        try {
            notes.tag(id, label);
            return "stored";
        } catch (RuntimeException failure) {
            return mentions(failure, "it_note_tag_note_fk") ? "refused" : "failed otherwise: " + messages(failure);
        }
    }

    private static boolean mentions(Throwable failure, String needle) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static String messages(Throwable failure) {
        StringBuilder text = new StringBuilder();
        for (Throwable t = failure; t != null; t = t.getCause()) {
            text.append(t.getClass().getSimpleName())
                    .append(": ")
                    .append(t.getMessage())
                    .append(" | ");
        }
        return text.toString();
    }
}
