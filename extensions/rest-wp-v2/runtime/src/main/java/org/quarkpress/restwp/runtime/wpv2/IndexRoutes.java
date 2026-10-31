package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteOptions;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.RestServer;
import org.quarkpress.restwp.runtime.RouteData;
import org.quarkpress.restwp.runtime.RouteTable;

/**
 * WordPress's API indexes: the site index at {@code /} (the site's data, the namespaces, the authentication schemes,
 * every route's data, then the site logo and icon), and the endpoint of each namespace's index at
 * {@code /<namespace>}, which the route table registers right before the namespace's first route. Both are public and
 * take untyped {@code context} (and {@code namespace}) arguments, so {@code ?context=help} adds every route's schema
 * and {@code ?namespace=} on a namespace index answers another namespace's.
 */
@ApplicationScoped
public class IndexRoutes implements RestRouteProvider, RouteTable.NamespaceIndex {

    static final String HELP = "https://developer.wordpress.org/rest-api/";

    private static final String FEATURED_MEDIA = "https://api.w.org/featuredmedia";

    @Inject
    RestServer server;

    @Inject
    RestUrls urls;

    @Inject
    SiteContext site;

    @ConfigProperty(name = "quarkus.oidc.enabled", defaultValue = "true")
    boolean oidcEnabled;

    @ConfigProperty(name = "quarkus.oidc.tenant-enabled", defaultValue = "true")
    boolean tenantEnabled;

    @ConfigProperty(name = "quarkus.oidc.auth-server-url")
    Optional<String> authServerUrl;

    @Override
    public int order() {
        return 0;
    }

    @Override
    public void register(RouteRegistry routes) {
        routes.route("", "/")
                .endpoint(Endpoint.of("GET")
                        .arg(untyped("context", "view"))
                        .handler(this::siteIndex)
                        .build())
                .register();
    }

    /** The namespace index's endpoint for {@code namespace}: its own namespace by default, any other on request. */
    @Override
    public Endpoint endpoint(String namespace) {
        return Endpoint.of("GET")
                .arg(untyped("namespace", namespace))
                .arg(untyped("context", "view"))
                .handler(this::namespaceIndex)
                .build();
    }

    /**
     * The authentication schemes the index lists: the issuer of the default OpenID Connect tenant when it is enabled
     * with a server URL, else none (an empty list, as WordPress prints an empty array).
     */
    static JsonNode authentication(boolean enabled, Optional<String> issuer) {
        if (!enabled || issuer.isEmpty() || issuer.get().isEmpty()) {
            return RestJson.NODES.arrayNode();
        }
        ObjectNode schemes = RestJson.NODES.objectNode();
        schemes.putObject("oidc").put("issuer", issuer.get());
        return schemes;
    }

    private RestResponse siteIndex(RestRequest request) {
        RouteTable table = server.table();
        SiteOptions options = site.options();
        ObjectNode data = RestJson.NODES.objectNode();
        data.set("name", text(options, "blogname"));
        data.set("description", text(options, "blogdescription"));
        String home = withoutTrailingSlash(urls.home());
        data.put("url", home);
        data.put("home", home);
        data.set("gmt_offset", text(options, "gmt_offset"));
        data.set("timezone_string", text(options, "timezone_string"));
        data.put("page_for_posts", integer(options, "page_for_posts"));
        data.put("page_on_front", integer(options, "page_on_front"));
        data.set("show_on_front", text(options, "show_on_front"));
        ArrayNode namespaces = data.putArray("namespaces");
        table.namespaces().forEach(namespaces::add);
        data.set("authentication", authentication(oidcEnabled && tenantEnabled, authServerUrl));
        data.set("routes", routeData(table.routes(), null, request.context()));
        // the logo and icon come with the links: by default, or when _links or _embedded is selected; otherwise only
        // what _fields names
        List<String> fields = new ArrayList<>(Fields.of(request));
        if (fields.isEmpty()) {
            fields.add("_links");
        }
        if (request.hasParam("_embed")) {
            fields.add("_embedded");
        }
        Fields.Selection selected = Fields.Selection.of(fields);
        Links links = Links.none();
        if (selected.includes("_links") || selected.includes("_embedded")) {
            links = links.add("help", HELP);
            links = siteLogo(data, options, links);
            links = siteIcon(data, options, links);
        } else {
            if (selected.includes("site_logo")) {
                links = siteLogo(data, options, links);
            }
            if (selected.includes("site_icon") || selected.includes("site_icon_url")) {
                links = siteIcon(data, options, links);
            }
        }
        return RestResponse.ok(data).withLinks(links);
    }

    private Links siteLogo(ObjectNode data, SiteOptions options, Links links) {
        return image(data, "site_logo", integer(options, "site_logo"), links);
    }

    private Links siteIcon(ObjectNode data, SiteOptions options, Links links) {
        Links withIcon = image(data, "site_icon", integer(options, "site_icon"), links);
        // there is no media yet, so no icon URL
        data.put("site_icon_url", "");
        return withIcon;
    }

    /** The image's attachment id as {@code type}, and an embeddable link to the attachment when one is set. */
    private Links image(ObjectNode data, String type, long id, Links links) {
        data.put(type, id);
        if (id == 0) {
            return links;
        }
        Map<String, JsonNode> attributes = new LinkedHashMap<>();
        attributes.put("embeddable", BooleanNode.TRUE);
        attributes.put("type", TextNode.valueOf(type));
        return links.add(FEATURED_MEDIA, urls.route("/wp/v2/media/" + id), attributes);
    }

    private RestResponse namespaceIndex(RestRequest request) {
        RouteTable table = server.table();
        JsonNode asked = request.param("namespace").orElse(null);
        String namespace = asked == null || asked.isContainerNode() ? null : PhpValues.toPhpString(asked);
        if (namespace == null || !table.namespaces().contains(namespace)) {
            throw new RestException(
                    RestError.of("rest_invalid_namespace", "The specified namespace could not be found.", 404));
        }
        ObjectNode data = RestJson.NODES.objectNode();
        data.put("namespace", namespace);
        data.set("routes", routeData(table.routes(), namespace, request.context()));
        return RestResponse.ok(data).withLinks(Links.none().add("up", urls.route("/")));
    }

    /** The data of every route (of {@code namespace} when it is not null) that shows an endpoint in the index. */
    private ObjectNode routeData(List<RouteTable.Route> routes, String namespace, String context) {
        ObjectNode data = RestJson.NODES.objectNode();
        for (RouteTable.Route route : routes) {
            if (namespace != null && !route.namespace().equals(namespace)) {
                continue;
            }
            ObjectNode described = RouteData.of(route, context, urls);
            if (described != null) {
                data.set(route.key(), described);
            }
        }
        return data;
    }

    /** An argument without a type: any value is accepted; {@code value} when the request has none. */
    private static Arg untyped(String name, String value) {
        return Arg.of(name, null).defaultValue(TextNode.valueOf(value)).build();
    }

    /**
     * An option as WordPress reads it back: a stored scalar as text (WordPress stores every scalar as text), a
     * stored list or map as it is, {@code false} when the option is not set.
     */
    private static JsonNode text(SiteOptions options, String name) {
        Optional<JsonNode> value = options.get(name);
        if (value.isEmpty()) {
            return BooleanNode.FALSE;
        }
        JsonNode stored = value.get();
        return stored.isValueNode() && !stored.isNull() ? TextNode.valueOf(PhpValues.toPhpString(stored)) : stored;
    }

    /** An option as PHP's {@code (int)} reads it; 0 when it is not set. */
    private static long integer(SiteOptions options, String name) {
        return options.get(name).map(PhpValues::toLong).orElse(0L);
    }

    private static String withoutTrailingSlash(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
