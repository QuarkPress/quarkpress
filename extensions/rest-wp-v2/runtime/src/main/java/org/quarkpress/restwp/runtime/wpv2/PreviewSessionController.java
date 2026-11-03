package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PreviewSessions;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.PreviewPage;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.Memberships;

/**
 * The preview session over REST, {@code /quarkpress/v1/preview/session}: the front-end preview page knows its viewer
 * by the {@code quarkpress_preview} cookie, which a browser cannot be given by the admin shell's Bearer token, so the
 * shell opens a session here first. {@code POST} opens one for the caller, a logged-in member of the site (or a super
 * admin): {@code Set-Cookie: quarkpress_preview=<token>; Max-Age=<lifetime>; Path=<the site's path>; HttpOnly;
 * SameSite=Lax} ({@code Secure} too over HTTPS) and {@code {"expires_in": <lifetime in seconds>}}, the lifetime being
 * {@code quarkpress.posts.preview-lifetime}. {@code DELETE} closes every session of the caller on the site and clears
 * the cookie ({@code Max-Age=0}), answering {@code {"closed": true}}. Anyone else is refused with
 * {@code rest_forbidden}. No REST route reads the cookie: the session is for the page alone.
 */
@ApplicationScoped
public class PreviewSessionController implements RestRouteProvider {

    private static final String NAMESPACE = "quarkpress/v1";

    private static final String SESSION = "/preview/session";

    @Inject
    PreviewSessions sessions;

    @Inject
    Memberships memberships;

    @Inject
    Capabilities capabilities;

    @Inject
    SiteContext site;

    @Override
    public int order() {
        return 1000;
    }

    @Override
    public void register(RouteRegistry routes) {
        routes.route(NAMESPACE, SESSION)
                .endpoint(Endpoint.of("POST")
                        .permission(this::member)
                        .handler(this::open)
                        .build())
                .endpoint(Endpoint.of("DELETE")
                        .permission(this::member)
                        .handler(this::close)
                        .build())
                .register();
    }

    /** A logged-in member of the site, or a super admin. */
    private PermissionResult member(RestRequest request) {
        Optional<UserId> user = request.user();
        if (user.isPresent()
                && (memberships.isMember(site.siteId(), user.get()) || capabilities.isSuperAdmin(user.get()))) {
            return PermissionResult.allowed();
        }
        return PermissionResult.denied(
                RestError.of("rest_forbidden", "Sorry, you are not allowed to do that.", request.authCode()));
    }

    private RestResponse open(RestRequest request) {
        PreviewSessions.Session session = sessions.open(request.user().orElseThrow());
        long seconds = session.lifetime().toSeconds();
        ObjectNode answer = RestJson.NODES.objectNode();
        answer.put("expires_in", seconds);
        return RestResponse.ok(answer).withHeader("Set-Cookie", cookie(session.token(), seconds, request.secure()));
    }

    private RestResponse close(RestRequest request) {
        sessions.closeAll(request.user().orElseThrow());
        ObjectNode answer = RestJson.NODES.objectNode();
        answer.put("closed", true);
        return RestResponse.ok(answer).withHeader("Set-Cookie", cookie("", 0, request.secure()));
    }

    /** The cookie for the site's path, kept from scripts and cross-site navigations; {@code Secure} over HTTPS. */
    private String cookie(String value, long maxAge, boolean secure) {
        StringBuilder cookie = new StringBuilder(PreviewPage.COOKIE)
                .append('=')
                .append(value)
                .append("; Max-Age=")
                .append(maxAge)
                .append("; Path=")
                .append(site.site().path())
                .append("; HttpOnly; SameSite=Lax");
        if (secure) {
            cookie.append("; Secure");
        }
        return cookie.toString();
    }
}
