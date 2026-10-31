package org.quarkpress.restwp.runtime;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.SiteStatus;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestWpV2Feature;

/**
 * Decides whether the request's site may be served at all, before any route is matched. A request no site resolved
 * for, or for a site with the REST feature off, answers 404 {@code rest_no_route}; a read (GET or HEAD) that arrived on
 * a redirecting alias domain answers 301 to the same path and query on the site's address; an archived, spam or deleted
 * site answers 410 to every caller with WordPress's site-check message, as a JSON {@code wp_die} object for a JSON
 * request and as an HTML page otherwise, with no-cache headers and none of the REST headers (WordPress checks the site
 * before its REST server runs).
 */
@ApplicationScoped
public class SiteGate {

    static final String ARCHIVED = "This site has been archived or suspended.";
    static final String GONE = "This site is no longer available.";

    private static final String HTML_TYPE = "text/html; charset=UTF-8";

    @Inject
    SiteContext site;

    /**
     * How the site decides a request before any route is matched.
     */
    public sealed interface Gate {

        /** A redirect to the site's address, sent with the REST server's fixed headers. */
        record Redirect(ServedResponse response) implements Gate {}

        /** Refused before the REST server runs, sent as it is: none of the REST server's headers. */
        record Died(ServedResponse response) implements Gate {}

        /** No site to serve, or the REST feature is off: the REST server answers {@code rest_no_route}. */
        record NoRoute() implements Gate {}

        /** The answer as the gate alone renders it. */
        default ServedResponse response() {
            return switch (this) {
                case Redirect redirect -> redirect.response();
                case Died died -> died.response();
                case NoRoute noRoute -> noRoute();
            };
        }
    }

    /** The answer that ends the request here, or empty when the request may go on to the routes. */
    public Optional<ServedResponse> check(IncomingRequest request) {
        return decide(request).map(Gate::response);
    }

    /** The site's decision, or empty when the request may go on to the routes. */
    public Optional<Gate> decide(IncomingRequest request) {
        if (!site.isResolved()) {
            return Optional.of(new Gate.NoRoute());
        }
        SiteInfo info;
        try {
            info = site.site();
        } catch (SiteNotResolvedException missing) {
            return Optional.of(new Gate.NoRoute());
        }
        String method = RestServer.method(request);
        Optional<URI> redirect = site.redirect();
        if (redirect.isPresent() && (method.equals("GET") || method.equals("HEAD"))) {
            return Optional.of(new Gate.Redirect(redirect(redirect.get(), request.target())));
        }
        SiteStatus status = info.status();
        if (status != SiteStatus.ACTIVE) {
            String message = status == SiteStatus.DELETED ? GONE : ARCHIVED;
            return Optional.of(new Gate.Died(died(410, "wp_die", message, isJson(request))));
        }
        if (!site.features().isEnabled(RestWpV2Feature.KEY)) {
            return Optional.of(new Gate.NoRoute());
        }
        return Optional.empty();
    }

    /** WordPress's JSON-request test, on the headers as PHP holds them (repeated values joined). */
    static boolean isJson(IncomingRequest request) {
        return JsonRequests.isJson(
                Optional.ofNullable(request.joinedHeader("Accept")),
                Optional.ofNullable(request.joinedHeader("Content-Type")));
    }

    private static ServedResponse noRoute() {
        return RestServer.render(RestServer.error(RestServer.NO_ROUTE));
    }

    /** A 301 to {@code target} (the request's path and query) below the site's address. */
    private static ServedResponse redirect(URI siteAddress, String target) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Location", List.of(location(siteAddress, target)));
        return new ServedResponse(301, headers, new byte[0]);
    }

    /**
     * The site's address followed by {@code target}, which always starts the path after the address's own: a target
     * without a leading slash gets one, so it can never extend the address's host or path segment.
     */
    static String location(URI siteAddress, String target) {
        String base = siteAddress.toString();
        String path = target.startsWith("/") ? target : "/" + target;
        return base.endsWith("/") ? base + path.substring(1) : base + path;
    }

    /**
     * WordPress's answer to a request it refuses before its REST server runs: for a JSON request, {@code {"code",
     * "message", "data": {"status"}, "additional_errors": []}} as {@code application/json}; otherwise a minimal error
     * page with the message. Both carry WordPress's no-cache headers and nothing else.
     */
    static ServedResponse died(int status, String code, String message, boolean json) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        byte[] body;
        if (json) {
            headers.put("Content-Type", List.of(ResponseHeaders.JSON_TYPE));
            ObjectNode error = RestError.of(code, message, status).toJson();
            error.putArray("additional_errors");
            body = RestJson.encode(error, false);
        } else {
            headers.put("Content-Type", List.of(HTML_TYPE));
            body = page(message, status == 410 ? "noindex, nofollow" : "noindex, follow");
        }
        headers.put("Expires", List.of("Wed, 11 Jan 1984 05:00:00 GMT"));
        headers.put("Cache-Control", List.of("no-cache, must-revalidate, max-age=0, no-store, private"));
        return new ServedResponse(status, headers, body);
    }

    private static byte[] page(String message, String robots) {
        String page = "<!DOCTYPE html>\n<html lang=\"en-US\">\n<head>\n"
                + "\t<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n"
                + "\t<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                + "\t<meta name='robots' content='" + robots + "' />\n"
                + "\t<title>Error</title>\n</head>\n<body id=\"error-page\">\n"
                + "\t<div class=\"wp-die-message\">" + message + "</div></body>\n</html>\n";
        return page.getBytes(StandardCharsets.UTF_8);
    }
}
