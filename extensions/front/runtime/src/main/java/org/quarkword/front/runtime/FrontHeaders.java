package org.quarkword.front.runtime;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.PostTypeDefinition;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteOptions;
import org.quarkword.core.TaxonomyDefinition;
import org.quarkword.front.FrontRequest;
import org.quarkword.front.FrontUrls;
import org.quarkword.front.QueriedObject;
import org.quarkword.front.Viewer;
import org.quarkword.posts.PostTypes;
import org.quarkword.taxonomy.Taxonomies;

/**
 * The headers of a front-end answer, as WordPress sends them (no {@code X-Pingback}: QuarkWord has no XML-RPC).
 *
 * <p>Every answer is {@code text/html; charset=UTF-8}. WordPress's no-cache set ({@code Expires} in 1984 and {@code
 * Cache-Control: no-cache, must-revalidate, max-age=0, no-store, private}) goes with an answer to a logged-in viewer,
 * a preview, a 404, a request the 404 decision found missing (even when an {@code error} status then replaced the
 * 404), and a singular request whose post has a password. A preview adds {@code X-Robots-Tag: noindex, nofollow} and
 * {@code Content-Security-Policy: frame-ancestors 'self'}; a redirect is no preview (WordPress redirects a preview
 * only once it stopped being one), so it gets neither, nor the no-cache set for being one. Any other answer carries {@code quarkword.front.cache-control} as
 * its {@code Cache-Control} when that is set. A page (not a redirect, and not an {@code error} status that ends the
 * request) carries the REST API's {@code Link}, then the REST route of the queried post, term or author when its type
 * or taxonomy is in the REST API ({@code rel="alternate"}), then a singular request's shortlink ({@code home/?p=ID},
 * or the home for the static front page) when its type is public. A redirect carries the answer's headers without
 * the links, then {@code X-Redirect-By: QuarkWord} and its {@code Location}.
 */
@RequestScoped
public class FrontHeaders {

    static final String HTML = "text/html; charset=UTF-8";
    static final String EXPIRES = "Wed, 11 Jan 1984 05:00:00 GMT";
    static final String NO_CACHE = "no-cache, must-revalidate, max-age=0, no-store, private";
    static final String REDIRECT_BY = "QuarkWord";

    @Inject
    FrontUrls urls;

    @Inject
    FrontConfig config;

    @Inject
    SiteContext site;

    @Inject
    PostTypes types;

    @Inject
    Taxonomies taxonomies;

    /**
     * The headers of the page answering {@code request}; {@code passwordProtected} tells whether the shown post has a
     * password.
     */
    public Map<String, List<String>> of(FrontRequest request, Viewer viewer, boolean passwordProtected) {
        Map<String, List<String>> headers = base(
                request,
                viewer,
                config.cacheControl(),
                passwordProtected,
                request.flags().preview());
        if (DefaultFrontResolver.ENDING.contains(request.status())) {
            return headers;
        }
        List<String> links = new ArrayList<>();
        links.add("<" + urls.rest("/") + ">; rel=\"https://api.w.org/\"");
        route(request, types, taxonomies)
                .ifPresent(route -> links.add(
                        "<" + urls.rest(route) + ">; rel=\"alternate\"; title=\"JSON\"; type=\"application/json\""));
        shortlink(request, urls, site.options(), types).ifPresent(link -> links.add("<" + link + ">; rel=shortlink"));
        headers.put("Link", links);
        return headers;
    }

    /** The headers of {@code redirect} answering {@code request}. */
    public Map<String, List<String>> redirect(
            FrontRequest request, Viewer viewer, boolean passwordProtected, Redirect redirect) {
        Objects.requireNonNull(redirect, "redirect");
        // a redirect answers no preview: WordPress redirects a preview only once it is no longer one (a published post
        // asked for without a valid pair), the other previews being exempt from the canonical redirect
        Map<String, List<String>> headers = base(request, viewer, config.cacheControl(), passwordProtected, false);
        headers.put("X-Redirect-By", List.of(REDIRECT_BY));
        headers.put("Location", List.of(redirect.location()));
        return headers;
    }

    private static Map<String, List<String>> base(
            FrontRequest request,
            Viewer viewer,
            Optional<String> cacheControl,
            boolean passwordProtected,
            boolean preview) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(cacheControl, "cacheControl");
        Map<String, List<String>> headers = new LinkedHashMap<>();
        boolean noCache = viewer.user().isPresent()
                || preview
                || request.status() == 404
                || (request.flags().is404() && request.query().isPresent())
                || (request.flags().singular() && passwordProtected);
        if (noCache) {
            headers.put("Expires", List.of(EXPIRES));
            headers.put("Cache-Control", List.of(NO_CACHE));
        }
        headers.put("Content-Type", List.of(HTML));
        if (preview) {
            headers.put("X-Robots-Tag", List.of("noindex, nofollow"));
            headers.put("Content-Security-Policy", List.of("frame-ancestors 'self'"));
        }
        if (!noCache && cacheControl.isPresent() && !cacheControl.get().isBlank()) {
            headers.put("Cache-Control", List.of(cacheControl.get()));
        }
        return headers;
    }

    /** The REST route of the queried object: a post or page of a REST type, a REST taxonomy's term, an author. */
    private static Optional<String> route(FrontRequest request, PostTypes types, Taxonomies taxonomies) {
        Optional<QueriedObject> queried = request.queriedObject();
        if (queried.isEmpty() || request.flags().is404()) {
            return Optional.empty();
        }
        if (request.flags().singular() && queried.get() instanceof QueriedObject.Post post) {
            return types.find(post.post().type())
                    .filter(PostTypeDefinition::showInRest)
                    .map(type -> "/wp/v2/" + type.restBase().orElse(type.key()) + "/" + post.id());
        }
        if ((request.flags().category()
                        || request.flags().tag()
                        || request.flags().tax())
                && queried.get() instanceof QueriedObject.Term term) {
            return taxonomies
                    .find(term.term().taxonomy())
                    .filter(TaxonomyDefinition::showInRest)
                    .map(taxonomy -> "/wp/v2/" + taxonomy.restBase().orElse(taxonomy.key()) + "/" + term.id());
        }
        if (request.flags().author() && queried.get() instanceof QueriedObject.Author author) {
            return Optional.of("/wp/v2/users/" + author.id());
        }
        return Optional.empty();
    }

    /** WordPress's shortlink of a singular request: the home for the static front page, else {@code ?p=ID}. */
    private static Optional<String> shortlink(
            FrontRequest request, FrontUrls urls, SiteOptions options, PostTypes types) {
        if (!request.flags().singular() || !(request.queriedObject().orElse(null) instanceof QueriedObject.Post post)) {
            return Optional.empty();
        }
        long id = post.id();
        if (id <= 0) {
            return Optional.empty();
        }
        if (post.post().type().equals("page")
                && options.string("show_on_front", "posts").equals("page")
                && options.number("page_on_front", 0) == id) {
            return Optional.of(urls.home("/"));
        }
        boolean publicType =
                types.find(post.post().type()).map(PostTypeDefinition::publicly).orElse(false);
        return publicType ? Optional.of(urls.home("?p=" + id)) : Optional.empty();
    }
}
