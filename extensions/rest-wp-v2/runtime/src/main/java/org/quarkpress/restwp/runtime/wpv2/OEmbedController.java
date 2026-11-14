package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import io.quarkus.cache.Cache;
import io.quarkus.cache.CacheName;
import io.quarkus.cache.CaffeineCache;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.net.URISyntaxException;
import java.security.SecureRandom;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import org.jboss.logging.Logger;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteDirectory;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.runtime.SiteResolution;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.oembed.EmbedHandlers;
import org.quarkpress.restwp.runtime.oembed.OEmbedConfig;
import org.quarkpress.restwp.runtime.oembed.OEmbedFetch;
import org.quarkpress.restwp.runtime.oembed.OEmbedHtml;
import org.quarkpress.restwp.runtime.oembed.OEmbedProviders;

/**
 * WordPress's oEmbed proxy, {@code GET /oembed/1.0/proxy}, for the block editor's embed previews: a caller with
 * {@code edit_posts} asks for a URL's oEmbed data and gets, in this order, the cached answer for these parameters, a
 * refusal for a URL of this site or a sibling site (no local embed yet), the data of the first allow-listed provider
 * whose scheme matches (fetched through the safe fetcher, its {@code html} rebuilt, titled, filtered unless the
 * provider is trusted, and stripped of newlines; cached for a day), an embed handler's answer (audio, video and
 * YouTube embed URLs), or 404 {@code oembed_invalid_url}. The arguments are WordPress's: {@code maxwidth} and
 * {@code maxheight} are declared integers but never type-checked (a non-numeric value reads as 0); a zero or absent
 * size sends the provider the configured defaults. The route exists on a site only while its {@code oembed} feature
 * is on.
 */
@ApplicationScoped
public class OEmbedController implements RestRouteProvider {

    /** The per-site feature: off, every proxy request of the site answers as no route would. */
    public static final String FEATURE = "oembed";

    private static final Logger LOG = Logger.getLogger(OEmbedController.class);

    private static final String FORBIDDEN = "Sorry, you are not allowed to make proxied oEmbed requests.";

    private static final String SECRET_CHARS = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";

    @Inject
    SiteContext site;

    @Inject
    SiteResolution resolution;

    @Inject
    SiteDirectory sites;

    @Inject
    PostPermissions permissions;

    @Inject
    OEmbedProviders providers;

    @Inject
    OEmbedFetch fetch;

    @Inject
    OEmbedConfig config;

    @Inject
    @CacheName("oembed")
    Cache cache;

    /** Right after the index route: WordPress registers the oEmbed namespace before its {@code wp/v2} routes. */
    @Override
    public int order() {
        return 10;
    }

    @Override
    public void register(RouteRegistry routes) {
        ObjectNode url = RestJson.NODES.objectNode();
        url.put("description", "The URL of the resource for which to fetch oEmbed data.");
        url.put("type", "string");
        url.put("format", "uri");
        ObjectNode format = RestJson.NODES.objectNode();
        format.put("description", "The oEmbed format to use.");
        format.put("type", "string");
        format.put("default", "json");
        format.putArray("enum").add("json").add("xml");
        ObjectNode maxwidth = RestJson.NODES.objectNode();
        maxwidth.put("description", "The maximum width of the embed frame in pixels.");
        maxwidth.put("type", "integer");
        maxwidth.put("default", 600);
        ObjectNode maxheight = RestJson.NODES.objectNode();
        maxheight.put("description", "The maximum height of the embed frame in pixels.");
        maxheight.put("type", "integer");
        ObjectNode discover = RestJson.NODES.objectNode();
        discover.put("description", "Whether to perform an oEmbed discovery request for unsanctioned providers.");
        discover.put("type", "boolean");
        discover.put("default", true);
        routes.route("oembed/1.0", "/proxy")
                .endpoint(Endpoint.of("GET")
                        .arg(new Arg("url", url, true, Optional.empty(), Arg.Phase.VALIDATE, Optional.empty()))
                        .arg(new Arg(
                                "format",
                                format,
                                false,
                                Optional.of(TextNode.valueOf("json")),
                                Arg.Phase.VALIDATE,
                                Optional.empty()))
                        .arg(size("maxwidth", maxwidth, Optional.of(RestJson.NODES.numberNode(600))))
                        .arg(size("maxheight", maxheight, Optional.empty()))
                        .arg(new Arg(
                                "discover",
                                discover,
                                false,
                                Optional.of(BooleanNode.TRUE),
                                Arg.Phase.VALIDATE,
                                Optional.empty()))
                        .permission(this::mayProxy)
                        .handler(this::proxy)
                        .build())
                .register();
    }

    /** A size argument as WordPress declares it: an integer that is never type-checked, read as its absolute value. */
    private static Arg size(String name, ObjectNode schema, Optional<JsonNode> defaultValue) {
        return new Arg(
                name,
                schema,
                false,
                defaultValue,
                Arg.Phase.VALIDATE,
                Optional.of(value -> RestJson.NODES.numberNode(Math.abs(PhpValues.toLong(value)))),
                Optional.of(value -> true));
    }

    private PermissionResult mayProxy(RestRequest request) {
        if (!site.features().isEnabled(FEATURE)) {
            return PermissionResult.denied(
                    RestError.of("rest_no_route", "No route was found matching the URL and request method.", 404));
        }
        return permissions.canType(request.identity(), "edit_posts", "post")
                ? PermissionResult.allowed()
                : PermissionResult.denied(RestError.of("rest_forbidden", FORBIDDEN, request.authCode()));
    }

    private RestResponse proxy(RestRequest request) {
        String url = request.param("url").map(PhpValues::toPhpString).orElse("");
        long maxwidth = request.param("maxwidth").map(PhpValues::toLong).orElse(0L);
        OptionalLong maxheight = request.param("maxheight")
                .map(value -> OptionalLong.of(PhpValues.toLong(value)))
                .orElse(OptionalLong.empty());
        String key = cacheKey(request);
        CaffeineCache caffeine = cache.as(CaffeineCache.class);
        CompletableFuture<ObjectNode> cached = caffeine.getIfPresent(key);
        if (cached != null) {
            try {
                return RestResponse.ok(cached.get().deepCopy());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (ExecutionException e) {
                LOG.debugf("oEmbed cache entry failed: %s", e.getMessage());
            }
        }
        if (ownSite(url)) {
            throw notFound();
        }
        long width = maxwidth > 0 ? maxwidth : config.defaultWidth();
        long height =
                maxheight.isPresent() && maxheight.getAsLong() > 0 ? maxheight.getAsLong() : config.defaultHeight();
        Optional<ObjectNode> data = provider(url, width, height);
        if (data.isPresent()) {
            caffeine.put(key, CompletableFuture.completedFuture(data.get().deepCopy()));
            return RestResponse.ok(data.get());
        }
        OptionalInt handlerHeight = maxheight.isPresent() && maxheight.getAsLong() <= Integer.MAX_VALUE
                ? OptionalInt.of((int) maxheight.getAsLong())
                : OptionalInt.empty();
        Optional<EmbedHandlers.Handled> handled =
                EmbedHandlers.match(url, (int) Math.min(maxwidth, Integer.MAX_VALUE), handlerHeight);
        if (handled.isPresent()) {
            String html = switch (handled.get()) {
                case EmbedHandlers.Shortcode shortcode -> shortcode.html();
                case EmbedHandlers.YouTube youtube ->
                    provider(youtube.watchUrl(), width, height)
                            .map(answer -> answer.get("html"))
                            .filter(answer -> answer != null
                                    && answer.isTextual()
                                    && !answer.textValue().isEmpty())
                            .map(JsonNode::textValue)
                            .orElse(youtube.watchUrl());
            };
            ObjectNode answer = RestJson.NODES.objectNode();
            answer.put("provider_name", "Embed Handler");
            answer.put("html", html);
            answer.putArray("scripts");
            return RestResponse.ok(answer);
        }
        throw notFound();
    }

    /** The first matching provider's data for {@code url}, its {@code html} rebuilt and post-processed. */
    private Optional<ObjectNode> provider(String url, long width, long height) {
        Optional<OEmbedProviders.Match> match = providers.find(url);
        if (match.isEmpty()) {
            return Optional.empty();
        }
        Optional<ObjectNode> data = fetch.fetch(match.get().endpoint(), url, width, height);
        if (data.isEmpty()) {
            return Optional.empty();
        }
        ObjectNode object = data.get();
        Optional<String> html = OEmbedHtml.build(object, url);
        if (html.isPresent()) {
            String type = object.path("type").asText("");
            String text = html.get();
            if (type.equals("video") || type.equals("rich")) {
                text = OEmbedHtml.titled(
                        text,
                        object.path("title").isTextual() ? object.get("title").textValue() : "");
                if (!providers.trusted(match.get().provider())) {
                    html = OEmbedHtml.untrusted(text, secret());
                    text = html.orElse(null);
                }
            }
            if (text != null) {
                html = Optional.of(OEmbedHtml.stripNewlines(text));
            }
        }
        object.set("html", html.<JsonNode>map(TextNode::valueOf).orElse(BooleanNode.FALSE));
        return Optional.of(object);
    }

    /**
     * Whether {@code url} names this site (its domain, and a path under the site's) or another site of the network
     * (one the site resolvers answer for, whose domain is the URL's host): such URLs are not proxied.
     */
    private boolean ownSite(String url) {
        URI uri;
        try {
            uri = new URI(url);
        } catch (URISyntaxException e) {
            return false;
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            return false;
        }
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        SiteInfo current = site.site();
        if (host.equalsIgnoreCase(current.domain()) && (path + "/").startsWith(current.path())) {
            return true;
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(java.util.Locale.ROOT);
        Optional<SiteId> sibling = resolution.resolve(new SiteRequest(scheme, host, uri.getPort(), path));
        return sibling.flatMap(sites::find)
                .map(info -> host.equalsIgnoreCase(info.domain()))
                .orElse(false);
    }

    /** The site and every request parameter after defaults and sanitization but {@code _wpnonce}, by name. */
    private String cacheKey(RestRequest request) {
        StringBuilder key = new StringBuilder();
        key.append(site.siteId().value());
        for (String name : new TreeSet<>(request.paramNames())) {
            if (name.equals("_wpnonce")) {
                continue;
            }
            key.append('\n').append(name).append('=');
            request.param(name).ifPresent(value -> key.append(value.toString()));
        }
        return key.toString();
    }

    /** Created with the bean, not with the class: a generator in the image heap would repeat its seed. */
    private final SecureRandom random = new SecureRandom();

    private String secret() {
        StringBuilder secret = new StringBuilder(10);
        for (int i = 0; i < 10; i++) {
            secret.append(SECRET_CHARS.charAt(random.nextInt(SECRET_CHARS.length())));
        }
        return secret.toString();
    }

    private static RestException notFound() {
        return new RestException(RestError.of("oembed_invalid_url", "Not Found", 404));
    }
}
