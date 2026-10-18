package org.quarkpress.it.multisite;

import com.example.audit.RowAudit;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.List;
import java.util.Map;
import org.quarkpress.core.SiteId;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.SiteDomains;
import org.quarkpress.multisite.SiteFlag;
import org.quarkpress.multisite.Sites;

@Path("/it/network")
@Produces(MediaType.APPLICATION_JSON)
public class NetworkResource {

    @Inject
    Sites sites;

    @Inject
    SiteDomains domains;

    @Inject
    CreatedLog created;

    @Inject
    RowAudit audit;

    @POST
    @Path("/sites")
    @Consumes(MediaType.APPLICATION_JSON)
    public SiteView create(NewSiteRequest request) {
        Map<String, Boolean> features = request.features() == null ? Map.of() : request.features();
        return SiteView.of(sites.create(new NewSite(request.domain(), request.path(), request.title(), 1, features)));
    }

    @PUT
    @Path("/sites/{id}/flags/{flag}/{value}")
    public SiteView flag(
            @PathParam("id") long id, @PathParam("flag") SiteFlag flag, @PathParam("value") boolean value) {
        return SiteView.of(sites.setFlag(SiteId.of(id), flag, value));
    }

    @POST
    @Path("/sites/{id}/domains")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, String> alias(@PathParam("id") long id, AliasRequest request) {
        return Map.of(
                "domain",
                domains.add(SiteId.of(id), request.domain(), request.redirect()).domain());
    }

    @DELETE
    @Path("/sites/{id}")
    public void delete(@PathParam("id") long id) {
        sites.delete(SiteId.of(id));
    }

    @GET
    @Path("/sites/{id}/rows")
    public Map<String, Long> rows(@PathParam("id") long id) {
        return audit.count(id);
    }

    @GET
    @Path("/created/all")
    public List<Long> createdAll() {
        return created.allIds();
    }

    @GET
    @Path("/created")
    public List<Long> created() {
        return created.ids();
    }
}
