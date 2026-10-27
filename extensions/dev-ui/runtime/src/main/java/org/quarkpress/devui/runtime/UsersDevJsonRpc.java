package org.quarkpress.devui.runtime;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.quarkpress.core.UserId;
import org.quarkpress.devui.runtime.DevRealm.RealmUser;
import org.quarkpress.devui.runtime.DevViews.DevUserView;
import org.quarkpress.devui.runtime.DevViews.SiteRolesView;
import org.quarkpress.security.Membership;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;

/**
 * The Dev UI's JSON-RPC method of the security module: the users of the dev realms, each with what QuarkPress knows of
 * it. The realms are those Dev Services for Keycloak imports, read from the application's class path or the file
 * system whether or not Keycloak runs.
 */
public class UsersDevJsonRpc {

    @Inject
    DevCalls calls;

    @Inject
    UserProfiles profiles;

    @Inject
    Memberships memberships;

    @ConfigProperty(name = "quarkus.keycloak.devservices.realm-path", defaultValue = "quarkpress-dev-realm.json")
    List<String> realmPaths;

    /**
     * The realms' users by user name (a subject listed by several realms once), each joined with its profile (found by
     * subject, the realm's user id), its memberships (roles per site, by site id) and whether it is a network
     * administrator. A user without a profile has no site roles and is no network administrator. A realm that is
     * missing or unreadable adds no users.
     */
    public List<DevUserView> devUsers() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        List<RealmUser> realmUsers =
                DevRealm.loadAll(loader == null ? UsersDevJsonRpc.class.getClassLoader() : loader, realmPaths);
        return calls.asSystem(() -> join(
                realmUsers,
                subject -> profiles.findBySubject(subject).map(UserProfile::id),
                memberships::sitesOf,
                memberships.superAdmins()));
    }

    /**
     * Joins {@code realmUsers}, in their order, with what QuarkPress knows of them: {@code profileOf} finds the profile
     * of a subject, {@code membershipsOf} lists a profile's memberships by site id, {@code networkAdmins} are the
     * network administrators. A user without a profile gets no site roles and is no network administrator.
     */
    static List<DevUserView> join(
            List<RealmUser> realmUsers,
            Function<String, Optional<UserId>> profileOf,
            Function<UserId, List<Membership>> membershipsOf,
            Collection<UserId> networkAdmins) {
        List<DevUserView> views = new ArrayList<>();
        for (RealmUser user : realmUsers) {
            Optional<UserId> profile = profileOf.apply(user.id());
            List<SiteRolesView> siteRoles = new ArrayList<>();
            if (profile.isPresent()) {
                for (Membership membership : membershipsOf.apply(profile.get())) {
                    siteRoles.add(new SiteRolesView(membership.site().value(), membership.roles()));
                }
            }
            views.add(new DevUserView(
                    user.username(),
                    user.id(),
                    user.realmRoles(),
                    profile.isPresent(),
                    profile.isPresent() && networkAdmins.contains(profile.get()),
                    siteRoles));
        }
        return views;
    }
}
