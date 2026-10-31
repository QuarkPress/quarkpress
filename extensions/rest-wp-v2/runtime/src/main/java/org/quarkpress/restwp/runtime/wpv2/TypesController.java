package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.PhpObjectNode;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.CollectionItems;
import org.quarkpress.restwp.runtime.ContextFilter;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.taxonomy.Taxonomies;

/**
 * WordPress's post types routes: {@code /wp/v2/types}, every type shown in REST keyed by its name, and
 * {@code /wp/v2/types/<type>}. Both are public in the view and embed contexts; the edit context needs the type's
 * {@code edit_posts} (the collection skips the types whose {@code edit_posts} the caller lacks and refuses a caller who
 * has none). Labels and capabilities are PHP objects in WordPress, so {@code _fields} keeps them whole. There is no
 * {@code self} link.
 */
@ApplicationScoped
public class TypesController implements RestRouteProvider {

    /** The item schema, held once: the request's field selection is kept per schema instance. */
    static final ObjectNode SCHEMA = parse("""
            {
              "$schema": "http://json-schema.org/draft-04/schema#",
              "title": "type",
              "type": "object",
              "properties": {
                "capabilities": {
                  "description": "All capabilities used by the post type.",
                  "type": "object",
                  "context": ["edit"],
                  "readonly": true
                },
                "description": {
                  "description": "A human-readable description of the post type.",
                  "type": "string",
                  "context": ["view", "edit"],
                  "readonly": true
                },
                "hierarchical": {
                  "description": "Whether or not the post type should have children.",
                  "type": "boolean",
                  "context": ["view", "edit"],
                  "readonly": true
                },
                "viewable": {
                  "description": "Whether or not the post type can be viewed.",
                  "type": "boolean",
                  "context": ["edit"],
                  "readonly": true
                },
                "labels": {
                  "description": "Human-readable labels for the post type for various contexts.",
                  "type": "object",
                  "context": ["edit"],
                  "readonly": true
                },
                "name": {
                  "description": "The title for the post type.",
                  "type": "string",
                  "context": ["view", "edit", "embed"],
                  "readonly": true
                },
                "slug": {
                  "description": "An alphanumeric identifier for the post type.",
                  "type": "string",
                  "context": ["view", "edit", "embed"],
                  "readonly": true
                },
                "supports": {
                  "description": "All features, supported by the post type.",
                  "type": "object",
                  "context": ["edit"],
                  "readonly": true
                },
                "has_archive": {
                  "description": "If the value is a string, the value will be used as the archive slug. If the \
            value is false the post type has no archive.",
                  "type": ["string", "boolean"],
                  "context": ["view", "edit"],
                  "readonly": true
                },
                "taxonomies": {
                  "description": "Taxonomies associated with post type.",
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "context": ["view", "edit"],
                  "readonly": true
                },
                "rest_base": {
                  "description": "REST base route for the post type.",
                  "type": "string",
                  "context": ["view", "edit", "embed"],
                  "readonly": true
                },
                "rest_namespace": {
                  "description": "REST route's namespace for the post type.",
                  "type": "string",
                  "context": ["view", "edit", "embed"],
                  "readonly": true
                },
                "visibility": {
                  "description": "The visibility settings for the post type.",
                  "type": "object",
                  "context": ["edit"],
                  "readonly": true,
                  "properties": {
                    "show_ui": {
                      "description": "Whether to generate a default UI for managing this post type.",
                      "type": "boolean"
                    },
                    "show_in_nav_menus": {
                      "description": "Whether to make the post type available for selection in navigation menus.",
                      "type": "boolean"
                    }
                  }
                },
                "icon": {
                  "description": "The icon for the post type.",
                  "type": ["string", "null"],
                  "context": ["view", "edit", "embed"],
                  "readonly": true
                },
                "template": {
                  "type": ["array"],
                  "description": "The block template associated with the post type.",
                  "readonly": true,
                  "context": ["view", "edit", "embed"]
                },
                "template_lock": {
                  "type": ["string", "boolean"],
                  "enum": ["all", "insert", "contentOnly", false],
                  "description": "The template_lock associated with the post type, or false if none.",
                  "readonly": true,
                  "context": ["view", "edit", "embed"]
                }
              }
            }
            """);

    private static final String NOT_ALLOWED = "Sorry, you are not allowed to edit posts in this post type.";

    /** WordPress's default post type labels, in its order. */
    private static final List<String> LABEL_KEYS = List.of(
            "name",
            "singular_name",
            "add_new",
            "add_new_item",
            "edit_item",
            "new_item",
            "view_item",
            "view_items",
            "search_items",
            "not_found",
            "not_found_in_trash",
            "parent_item_colon",
            "all_items",
            "archives",
            "attributes",
            "insert_into_item",
            "uploaded_to_this_item",
            "featured_image",
            "set_featured_image",
            "remove_featured_image",
            "use_featured_image",
            "filter_items_list",
            "filter_by_date",
            "items_list_navigation",
            "items_list",
            "item_published",
            "item_published_privately",
            "item_reverted_to_draft",
            "item_trashed",
            "item_scheduled",
            "item_updated",
            "item_link",
            "item_link_description",
            "menu_name");

    @Inject
    PostTypes types;

    @Inject
    Taxonomies taxonomies;

    @Inject
    PostPermissions permissions;

    @Inject
    RestUrls urls;

    @Override
    public int order() {
        return 100;
    }

    @Override
    public void register(RouteRegistry routes) {
        routes.route("wp/v2", "/types")
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(SCHEMA))
                        .permission(this::mayList)
                        .handler(this::list)
                        .build())
                .register();
        routes.route("wp/v2", "/types/(?P<type>[\\w-]+)")
                .arg(urlArg("type", "An alphanumeric identifier for the post type."))
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(SCHEMA))
                        .handler(this::item)
                        .build())
                .register();
    }

    /** The edit context needs {@code edit_posts} of some type shown in REST. */
    private PermissionResult mayList(RestRequest request) {
        if (isEdit(request) && !permissions.canEditSomeRestType(request.identity())) {
            return PermissionResult.denied(RestError.of("rest_cannot_view", NOT_ALLOWED, request.authCode()));
        }
        return PermissionResult.allowed();
    }

    private RestResponse list(RestRequest request) {
        if (request.method().equals("HEAD")) {
            // no body is sent, so none is built
            return RestResponse.ok(RestJson.NODES.arrayNode());
        }
        boolean edit = isEdit(request);
        ObjectNode data = RestJson.NODES.objectNode();
        for (PostTypeDefinition type : types.all()) {
            if (!type.showInRest() || (edit && !permissions.canType(request.identity(), "edit_posts", type.key()))) {
                continue;
            }
            data.set(type.key(), CollectionItems.inline(prepare(type, request), request));
        }
        // WordPress prints an empty result as an empty list
        return RestResponse.ok(data.isEmpty() ? RestJson.NODES.arrayNode() : data);
    }

    private RestResponse item(RestRequest request) {
        String key = text(request.param("type"));
        Optional<PostTypeDefinition> found = types.find(key);
        if (found.isEmpty()) {
            throw new RestException(RestError.of("rest_type_invalid", "Invalid post type.", 404));
        }
        PostTypeDefinition type = found.get();
        if (!type.showInRest()) {
            throw new RestException(
                    RestError.of("rest_cannot_read_type", "Cannot view post type.", request.authCode()));
        }
        if (isEdit(request) && !permissions.canType(request.identity(), "edit_posts", type.key())) {
            throw new RestException(RestError.of("rest_forbidden_context", NOT_ALLOWED, request.authCode()));
        }
        if (request.method().equals("HEAD")) {
            return RestResponse.ok(RestJson.NODES.arrayNode());
        }
        return prepare(type, request);
    }

    /** The type's fields the request selects, in WordPress's order, filtered by the context; links when selected. */
    private RestResponse prepare(PostTypeDefinition type, RestRequest request) {
        Fields.Selection fields = Fields.forResponse(request, SCHEMA);
        TypeExtras extras = TypeExtras.of(type.key());
        String base = type.restBase().filter(rest -> !rest.isEmpty()).orElse(type.key());
        ObjectNode data = RestJson.NODES.objectNode();
        if (fields.includes("capabilities")) {
            data.set("capabilities", capabilities(type));
        }
        if (fields.includes("description")) {
            data.put("description", type.description());
        }
        if (fields.includes("hierarchical")) {
            data.put("hierarchical", type.hierarchical());
        }
        if (fields.includes("has_archive")) {
            data.put("has_archive", type.hasArchive());
        }
        if (fields.includes("visibility")) {
            ObjectNode visibility = data.putObject("visibility");
            visibility.put("show_in_nav_menus", type.showInNavMenus());
            visibility.put("show_ui", type.showUi());
        }
        if (fields.includes("viewable")) {
            data.put("viewable", PostPermissions.typeViewable(type));
        }
        if (fields.includes("labels")) {
            data.set("labels", labels(type.labels(), LABEL_KEYS));
        }
        if (fields.includes("name")) {
            data.set("name", label(type.labels().get("name")));
        }
        if (fields.includes("slug")) {
            data.put("slug", type.key());
        }
        if (fields.includes("icon")) {
            data.set("icon", label(extras.icon()));
        }
        if (fields.includes("supports")) {
            data.set("supports", supports(type, extras));
        }
        if (fields.includes("taxonomies")) {
            var names = data.putArray("taxonomies");
            for (TaxonomyDefinition taxonomy : taxonomies.forType(type.key())) {
                if (taxonomy.showInRest()) {
                    names.add(taxonomy.key());
                }
            }
        }
        if (fields.includes("rest_base")) {
            data.put("rest_base", base);
        }
        if (fields.includes("rest_namespace")) {
            data.put("rest_namespace", extras.restNamespace());
        }
        if (fields.includes("template")) {
            data.set("template", extras.template());
        }
        if (fields.includes("template_lock")) {
            data.set("template_lock", extras.templateLock());
        }
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, SCHEMA, request.context()));
        if (fields.includes("_links") || fields.includes("_embedded")) {
            response = response.withLinks(Links.none()
                    .add("collection", urls.route("/wp/v2/types"))
                    .add("https://api.w.org/items", urls.route("/" + extras.restNamespace() + "/" + base)));
        }
        return response;
    }

    /**
     * The type's capability names, in WordPress's order, as the security module enforces them: a PHP object.
     */
    private PhpObjectNode capabilities(PostTypeDefinition type) {
        PhpObjectNode capabilities = new PhpObjectNode();
        types.capabilities(type.key()).orElseThrow().names().forEach(capabilities::put);
        return capabilities;
    }

    /**
     * The labels as WordPress's PHP object holds them: every default label in WordPress's order (null where the
     * registration leaves one unset, such as a flat type's {@code parent_item_colon}), then the others in their order.
     */
    static PhpObjectNode labels(Map<String, String> labels, List<String> defaultKeys) {
        PhpObjectNode object = new PhpObjectNode();
        for (String key : defaultKeys) {
            object.set(key, label(labels.get(key)));
        }
        labels.forEach((key, value) -> {
            if (!object.has(key)) {
                object.set(key, label(value));
            }
        });
        return object;
    }

    /**
     * WordPress's {@code get_all_post_type_supports}: each supported feature with {@code true} or the arguments it
     * was registered with; an empty array (a list) when there is none.
     */
    private static JsonNode supports(PostTypeDefinition type, TypeExtras extras) {
        if (type.supports().isEmpty()) {
            return RestJson.NODES.arrayNode();
        }
        ObjectNode supports = RestJson.NODES.objectNode();
        for (String feature : type.supports()) {
            JsonNode arguments = extras.supportArguments().get(feature);
            supports.set(feature, arguments == null ? BooleanNode.TRUE : arguments);
        }
        return supports;
    }

    private static JsonNode label(String value) {
        return value == null ? NullNode.getInstance() : TextNode.valueOf(value);
    }

    /** Whether the request's context is {@code edit} (WordPress compares the sanitized text strictly). */
    static boolean isEdit(RestRequest request) {
        return request.param("context")
                .filter(JsonNode::isTextual)
                .map(JsonNode::textValue)
                .filter("edit"::equals)
                .isPresent();
    }

    /** A request parameter as PHP text; "" when absent. */
    static String text(Optional<JsonNode> value) {
        return value.filter(JsonNode::isValueNode).map(PhpValues::toPhpString).orElse("");
    }

    /**
     * A route-level string argument naming the route's item, as WordPress declares it: checked and sanitized as a
     * string while the arguments are sanitized.
     */
    static Arg urlArg(String name, String description) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("description", description);
        schema.put("type", "string");
        return new Arg(name, schema, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    /** A schema written as WordPress's JSON. */
    static ObjectNode parse(String json) {
        try {
            return (ObjectNode) RestJson.READER.readTree(json);
        } catch (IOException invalid) {
            throw new UncheckedIOException(invalid);
        }
    }
}
