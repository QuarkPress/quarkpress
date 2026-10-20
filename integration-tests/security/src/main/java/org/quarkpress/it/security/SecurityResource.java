package org.quarkpress.it.security;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.Sites;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.PostFacts;
import org.quarkpress.security.PostTypeCapabilities;
import org.quarkpress.security.QuarkPressPrincipal;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.UserTarget;

@Path("/it")
@Produces(MediaType.APPLICATION_JSON)
public class SecurityResource {

    @Inject
    SecurityIdentity identity;

    @Inject
    Capabilities capabilities;

    @Inject
    UserProfiles profiles;

    @Inject
    Memberships memberships;

    @Inject
    SecuredService secured;

    @Inject
    Sites sites;

    @GET
    @Path("/me")
    public Map<String, Object> me() {
        Map<String, Object> me = new LinkedHashMap<>();
        Optional<QuarkPressPrincipal> principal = QuarkPressPrincipal.of(identity);
        me.put("anonymous", identity.isAnonymous());
        if (principal.isPresent()) {
            UserProfile profile = profiles.find(principal.get().user()).orElseThrow();
            me.put("user", profile.id().value());
            me.put("subject", profile.subject());
            me.put("nicename", profile.nicename());
            me.put("displayName", profile.displayName());
            me.put("superAdmin", capabilities.isSuperAdmin(profile.id()));
        }
        List<String> roles = new ArrayList<>(identity.getRoles());
        roles.sort(null);
        me.put("roles", roles);
        return me;
    }

    @GET
    @Path("/can/{capability}")
    public boolean can(@PathParam("capability") String capability) {
        return capabilities.can(capability);
    }

    @GET
    @Path("/can/{capability}/user/{target}")
    public boolean canForUser(@PathParam("capability") String capability, @PathParam("target") long target) {
        return capabilities.can(capability, new UserTarget(UserId.of(target)));
    }

    @POST
    @Path("/drafts")
    public String draft() {
        return secured.draft();
    }

    @POST
    @Path("/settings")
    public String settings() {
        return secured.settings();
    }

    @POST
    @Path("/posts/edit")
    @Consumes(MediaType.APPLICATION_JSON)
    public String edit(EditRequest request) {
        PostFacts post = PostFacts.of(
                PostId.of(1), PostTypeCapabilities.POST, request.status(), Optional.of(UserId.of(request.author())));
        return secured.edit(post);
    }

    @POST
    @Path("/memberships")
    @Consumes(MediaType.APPLICATION_JSON)
    public List<String> addMember(MembershipRequest request) {
        return memberships
                .add(SiteId.of(request.site()), UserId.of(request.user()), request.role())
                .roles();
    }

    @DELETE
    @Path("/memberships/{site}/{user}")
    public boolean removeMember(@PathParam("site") long site, @PathParam("user") long user) {
        return memberships.remove(SiteId.of(site), UserId.of(user));
    }

    @POST
    @Path("/super-admins/{user}")
    public boolean grant(@PathParam("user") long user) {
        return memberships.grantSuperAdmin(UserId.of(user));
    }

    @DELETE
    @Path("/super-admins/{user}")
    public boolean revoke(@PathParam("user") long user) {
        return memberships.revokeSuperAdmin(UserId.of(user));
    }

    @POST
    @Path("/network/sites")
    @Consumes(MediaType.APPLICATION_JSON)
    public long createSite(SiteRequest request) {
        return sites.create(NewSite.of(request.domain(), request.path(), request.title()))
                .id()
                .value();
    }
}
