package org.quarkword.front.runtime;

import io.quarkus.security.identity.CurrentIdentityAssociation;
import io.quarkus.security.identity.SecurityIdentity;
import io.smallrye.common.annotation.Blocking;
import io.vertx.ext.web.RoutingContext;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HEAD;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkword.core.QuarkWordClock;
import org.quarkword.core.SiteContext;
import org.quarkword.front.FrontAnswer;
import org.quarkword.front.FrontFeature;
import org.quarkword.front.FrontFlags;
import org.quarkword.front.FrontRenderer;
import org.quarkword.front.FrontRenderer.PasswordState;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.FrontResolver;
import org.quarkword.front.QueryVars;
import org.quarkword.front.Viewer;
import org.quarkword.posts.PostPasswords;
import org.quarkword.posts.PreviewTokens;

/**
 * The internal resource the catch-all route ({@link FrontRoute}) re-routes a front-end {@code GET} or {@code HEAD} to;
 * a request that reaches its path any other way, or that no site serves, is answered 404 with an empty body. In order:
 * an alias domain set to redirect: 301 to the site's address; an archived, spam or deleted site: 410 with WordPress's
 * message, unless the front end is on and the request carries a preview pair that is not the viewer's (WordPress
 * checks the pair before the site): 403; the site's {@code front} feature off: 404 with an empty body; the request's
 * bounds (a request outside them is answered 404 without reading anything); the viewer, from the preview session
 * cookie alone; the resolution as that viewer; a bad preview pair: 403 with WordPress's message; an {@code error} of
 * 403, 500, 502 or
 * 503: that status with an empty body; the old slug redirect, then the canonical redirect: 301; otherwise the page
 * through the {@link FrontRenderer}, with WordPress's headers. A {@code HEAD} request gets the headers only. The
 * request's identity is the viewer's for the whole answer and is put back afterwards.
 */
@Path(FrontPage.PATH)
@Blocking
public class FrontPage {

    /** The internal path (below the REST application path) front-end requests are re-routed to. */
    public static final String PATH = "/_quarkword/front";

    /** The method's path below {@link #PATH}. */
    public static final String PAGE = "page";

    @Inject
    SiteContext site;

    @Inject
    FrontSiteGate gate;

    @Inject
    PreviewViewer viewers;

    @Inject
    FrontResolver resolver;

    @Inject
    OldSlugRedirects oldSlugs;

    @Inject
    CanonicalRedirects canonical;

    @Inject
    FrontHeaders headers;

    @Inject
    FrontRenderer renderer;

    @Inject
    PreviewTokens tokens;

    @Inject
    CurrentIdentityAssociation identities;

    @Inject
    QuarkWordClock clock;

    @GET
    @Path(PAGE)
    public Response get(@Context RoutingContext context) {
        return serve(context, false);
    }

    @HEAD
    @Path(PAGE)
    public Response head(@Context RoutingContext context) {
        return serve(context, true);
    }

    private Response serve(RoutingContext context, boolean head) {
        if (!(context.get(FrontRoute.ENTRY) instanceof FrontRoute.Entry entry) || !site.isResolved()) {
            return Response.status(404).build();
        }
        String accept = context.request().getHeader("Accept");
        String contentType = context.request().getHeader("Content-Type");
        Optional<FrontSiteGate.Answer> gated = gate.decide(site, entry.method(), entry.target());
        if (gated.isPresent() && gated.get() instanceof FrontSiteGate.Moved moved) {
            return response(redirect(moved.redirect()), head);
        }
        boolean enabled = site.features().isEnabled(FrontFeature.KEY);
        // a request outside the bounds is answered without reading anything, the session cookie included
        boolean bounded = RequestLimits.check(entry.rawPath(), entry.rawQuery());
        Map<String, List<String>> query = bounded ? query(entry.rawQuery()) : Map.of();
        Optional<FrontSiteGate.Gone> gone =
                gated.filter(FrontSiteGate.Gone.class::isInstance).map(FrontSiteGate.Gone.class::cast);
        // WordPress checks the preview pair on init, before its site check: only a bad pair outranks a gone site
        boolean pairFirst = gone.isPresent() && enabled && bounded && DefaultFrontResolver.hasPair(query);
        if (gone.isPresent() && !pairFirst) {
            return response(died(gone.get().status(), gone.get().message(), accept, contentType), head);
        }
        if (!enabled) {
            return Response.status(404).build();
        }
        Viewer viewer = bounded ? viewers.of(context.request().headers().getAll("Cookie")) : Viewer.anonymous();
        SecurityIdentity previous = identities.getIdentity();
        identities.setIdentity(viewer.identity());
        try {
            if (pairFirst) {
                return response(
                        DefaultFrontResolver.validPair(query, viewer, tokens)
                                ? died(gone.get().status(), gone.get().message(), accept, contentType)
                                : died(403, FrontRequest.CANNOT_PREVIEW, accept, contentType),
                        head);
            }
            FrontRequest request = bounded
                    ? resolver.resolve(entry.rawPath(), query, viewer)
                    : FrontRequest.answered(QueryVars.empty(), FrontFlags.notFound(), 404, clock.now());
            return response(answer(entry, request, viewer, accept, contentType), head);
        } finally {
            identities.setIdentity(previous);
        }
    }

    private FrontAnswer answer(
            FrontRoute.Entry entry, FrontRequest request, Viewer viewer, String accept, String contentType) {
        if (request.message().isPresent()) {
            return died(request.status(), request.message().get(), accept, contentType);
        }
        boolean passwordProtected = request.flags().singular()
                && request.shownPost().map(PostPasswords::required).orElse(false);
        if (DefaultFrontResolver.ENDING.contains(request.status())) {
            return new FrontAnswer(request.status(), headers.of(request, viewer, passwordProtected), new byte[0]);
        }
        RequestUrl url = new RequestUrl(entry.method(), entry.scheme(), entry.host(), entry.target());
        Optional<Redirect> redirect = oldSlugs.decide(request, viewer);
        if (redirect.isEmpty()) {
            redirect = canonical.decide(request, url, viewer);
        }
        if (redirect.isPresent()) {
            return new FrontAnswer(
                    redirect.get().status(),
                    headers.redirect(request, viewer, passwordProtected, redirect.get()),
                    new byte[0]);
        }
        PasswordState password = passwordProtected ? PasswordState.REQUIRED : PasswordState.NOT_REQUIRED;
        return withComputedHeaders(
                headers.of(request, viewer, passwordProtected), renderer.render(request, viewer, password));
    }

    /**
     * The renderer's answer with the headers the front end computed added before its own; a header the renderer sets
     * replaces the computed header of the same name, ignoring case.
     */
    static FrontAnswer withComputedHeaders(Map<String, List<String>> computed, FrontAnswer page) {
        Map<String, List<String>> merged = new LinkedHashMap<>(computed);
        page.headers().forEach((name, values) -> {
            merged.keySet().removeIf(computedName -> computedName.equalsIgnoreCase(name));
            merged.put(name, values);
        });
        return new FrontAnswer(page.status(), merged, page.body());
    }

    /** WordPress's error page or JSON error; its robots meta follows the site's {@code blog_public} option. */
    private FrontAnswer died(int status, String message, String accept, String contentType) {
        return WpDie.died(status, message, accept, contentType, site.site().publicFlag() != 0);
    }

    private static FrontAnswer redirect(Redirect redirect) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Content-Type", List.of(FrontHeaders.HTML));
        headers.put("X-Redirect-By", List.of(FrontHeaders.REDIRECT_BY));
        headers.put("Location", List.of(redirect.location()));
        return new FrontAnswer(redirect.status(), headers, new byte[0]);
    }

    /**
     * The query string's parameters as PHP's {@code $_GET} holds them (names and values url-decoded, {@code +} a
     * space, dots and spaces in names {@code _}, a later value replacing an earlier one), read as UTF-8; a parameter
     * holding an array ({@code a[]=1}) names no public variable the front end reads and is left out.
     */
    static Map<String, List<String>> query(String rawQuery) {
        Map<String, List<String>> query = new LinkedHashMap<>();
        for (Map.Entry<String, Object> parameter :
                PhpUrls.parseStr(PhpUrls.bytes(rawQuery)).entrySet()) {
            if (parameter.getValue() instanceof String value) {
                query.put(PhpUrls.text(parameter.getKey()), List.of(PhpUrls.text(value)));
            }
        }
        return query;
    }

    private static Response response(FrontAnswer answer, boolean head) {
        Response.ResponseBuilder builder = Response.status(answer.status());
        String type = null;
        for (Map.Entry<String, List<String>> header : answer.headers().entrySet()) {
            if (header.getKey().equalsIgnoreCase("Content-Type")) {
                type = header.getValue().isEmpty() ? null : header.getValue().get(0);
                continue;
            }
            for (String value : header.getValue()) {
                builder.header(header.getKey(), value);
            }
        }
        byte[] body = answer.body();
        if (!head && body.length > 0) {
            builder.entity(body);
        }
        if (type != null) {
            builder.type(type);
        }
        return builder.build();
    }
}
