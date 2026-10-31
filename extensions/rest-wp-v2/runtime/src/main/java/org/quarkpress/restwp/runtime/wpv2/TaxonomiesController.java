package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import org.quarkpress.core.TaxonomyDefinition;
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
import org.quarkpress.taxonomy.TermPermissions;

/**
 * WordPress's taxonomies routes: {@code /wp/v2/taxonomies}, the taxonomies shown in REST (of the post type
 * {@code type} names, when it names one) keyed by name, and {@code /wp/v2/taxonomies/<taxonomy>}. Both are public in
 * the view and embed contexts; the edit context needs the taxonomy's {@code assign_terms} (the collection skips the
 * others and refuses a caller who may assign none). A registered taxonomy not shown in REST is refused by the item's
 * permission; an unknown one by its handler. Fields are selected by their exact names, as WordPress's controller tests
 * them; labels and capabilities are PHP objects. An empty collection prints {@code {}}.
 */
@ApplicationScoped
public class TaxonomiesController implements RestRouteProvider {

    /** The item schema, held once: the request's field selection is kept per schema instance. */
    static final ObjectNode SCHEMA = TypesController.parse("""
            {
              "$schema": "http://json-schema.org/draft-04/schema#",
              "title": "taxonomy",
              "type": "object",
              "properties": {
                "capabilities": {
                  "description": "All capabilities used by the taxonomy.",
                  "type": "object",
                  "context": ["edit"],
                  "readonly": true
                },
                "description": {
                  "description": "A human-readable description of the taxonomy.",
                  "type": "string",
                  "context": ["view", "edit"],
                  "readonly": true
                },
                "hierarchical": {
                  "description": "Whether or not the taxonomy should have children.",
                  "type": "boolean",
                  "context": ["view", "edit"],
                  "readonly": true
                },
                "labels": {
                  "description": "Human-readable labels for the taxonomy for various contexts.",
                  "type": "object",
                  "context": ["edit"],
                  "readonly": true
                },
                "name": {
                  "description": "The title for the taxonomy.",
                  "type": "string",
                  "context": ["view", "edit", "embed"],
                  "readonly": true
                },
                "slug": {
                  "description": "An alphanumeric identifier for the taxonomy.",
                  "type": "string",
                  "context": ["view", "edit", "embed"],
                  "readonly": true
                },
                "show_cloud": {
                  "description": "Whether or not the term cloud should be displayed.",
                  "type": "boolean",
                  "context": ["edit"],
                  "readonly": true
                },
                "types": {
                  "description": "Types associated with the taxonomy.",
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "context": ["view", "edit"],
                  "readonly": true
                },
                "rest_base": {
                  "description": "REST base route for the taxonomy.",
                  "type": "string",
                  "context": ["view", "edit", "embed"],
                  "readonly": true
                },
                "rest_namespace": {
                  "description": "REST namespace route for the taxonomy.",
                  "type": "string",
                  "context": ["view", "edit", "embed"],
                  "readonly": true
                },
                "visibility": {
                  "description": "The visibility settings for the taxonomy.",
                  "type": "object",
                  "context": ["edit"],
                  "readonly": true,
                  "properties": {
                    "public": {
                      "description": "Whether a taxonomy is intended for use publicly either via the admin \
            interface or by front-end users.",
                      "type": "boolean"
                    },
                    "publicly_queryable": {
                      "description": "Whether the taxonomy is publicly queryable.",
                      "type": "boolean"
                    },
                    "show_ui": {
                      "description": "Whether to generate a default UI for managing this taxonomy.",
                      "type": "boolean"
                    },
                    "show_admin_column": {
                      "description": "Whether to allow automatic creation of taxonomy columns on associated \
            post-types table.",
                      "type": "boolean"
                    },
                    "show_in_nav_menus": {
                      "description": "Whether to make the taxonomy available for selection in navigation menus.",
                      "type": "boolean"
                    },
                    "show_in_quick_edit": {
                      "description": "Whether to show the taxonomy in the quick/bulk edit panel.",
                      "type": "boolean"
                    }
                  }
                }
              }
            }
            """);

    private static final String NOT_ALLOWED = "Sorry, you are not allowed to manage terms in this taxonomy.";

    private static final String NAMESPACE = "wp/v2";

    /** WordPress's default taxonomy labels, in its order. */
    private static final List<String> LABEL_KEYS = List.of(
            "name",
            "singular_name",
            "search_items",
            "popular_items",
            "all_items",
            "parent_item",
            "parent_item_colon",
            "name_field_description",
            "slug_field_description",
            "parent_field_description",
            "desc_field_description",
            "edit_item",
            "view_item",
            "update_item",
            "add_new_item",
            "new_item_name",
            "separate_items_with_commas",
            "add_or_remove_items",
            "choose_from_most_used",
            "not_found",
            "no_terms",
            "filter_by_item",
            "items_list_navigation",
            "items_list",
            "most_used",
            "back_to_items",
            "item_link",
            "item_link_description",
            "menu_name");

    @Inject
    Taxonomies taxonomies;

    @Inject
    TermPermissions permissions;

    @Inject
    RestUrls urls;

    @Override
    public int order() {
        return 300;
    }

    @Override
    public void register(RouteRegistry routes) {
        ObjectNode type = RestJson.NODES.objectNode();
        type.put("description", "Limit results to taxonomies associated with a specific post type.");
        type.put("type", "string");
        routes.route("wp/v2", "/taxonomies")
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(SCHEMA))
                        .arg(new Arg("type", type, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty()))
                        .permission(this::mayList)
                        .handler(this::list)
                        .build())
                .register();
        routes.route("wp/v2", "/taxonomies/(?P<taxonomy>[\\w-]+)")
                .arg(TypesController.urlArg("taxonomy", "An alphanumeric identifier for the taxonomy."))
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(SCHEMA))
                        .permission(this::mayRead)
                        .handler(this::item)
                        .build())
                .register();
    }

    /**
     * WordPress's test of a field by its exact name (its {@code in_array} over the selected fields), where a requested
     * {@code labels.name} does not select {@code labels}.
     */
    static Predicate<String> exactFields(Fields.Selection selection) {
        return switch (selection.kind()) {
            case EVERYTHING -> field -> true;
            case NOTHING -> field -> false;
            case THESE -> {
                Set<String> names = new HashSet<>(selection.fields());
                yield names::contains;
            }
        };
    }

    /** The taxonomies the collection considers: those of the requested post type, or all. */
    private List<TaxonomyDefinition> considered(RestRequest request) {
        Optional<JsonNode> type = request.param("type");
        if (type.isPresent() && !PhpValues.isEmpty(type.get())) {
            return taxonomies.forType(TypesController.text(type));
        }
        return taxonomies.all();
    }

    private PermissionResult mayList(RestRequest request) {
        if (!request.isEditContext()) {
            return PermissionResult.allowed();
        }
        for (TaxonomyDefinition taxonomy : considered(request)) {
            if (taxonomy.showInRest() && permissions.canAssign(request.identity(), taxonomy.key())) {
                return PermissionResult.allowed();
            }
        }
        return PermissionResult.denied(RestError.of("rest_cannot_view", NOT_ALLOWED, request.authCode()));
    }

    private PermissionResult mayRead(RestRequest request) {
        Optional<TaxonomyDefinition> taxonomy = taxonomies.find(TypesController.text(request.param("taxonomy")));
        if (taxonomy.isEmpty()) {
            // the handler answers that it does not exist
            return PermissionResult.allowed();
        }
        if (!taxonomy.get().showInRest()) {
            return PermissionResult.forbidden(request);
        }
        if (request.isEditContext()
                && !permissions.canAssign(request.identity(), taxonomy.get().key())) {
            return PermissionResult.denied(RestError.of("rest_forbidden_context", NOT_ALLOWED, request.authCode()));
        }
        return PermissionResult.allowed();
    }

    private RestResponse list(RestRequest request) {
        if (request.method().equals("HEAD")) {
            // no body is sent, so none is built
            return RestResponse.ok(RestJson.NODES.arrayNode());
        }
        boolean edit = request.isEditContext();
        Fields.Selection selection = Fields.forResponse(request, SCHEMA);
        Predicate<String> fields = exactFields(selection);
        ObjectNode data = RestJson.NODES.objectNode();
        for (TaxonomyDefinition taxonomy : considered(request)) {
            if (!taxonomy.showInRest() || (edit && !permissions.canAssign(request.identity(), taxonomy.key()))) {
                continue;
            }
            data.set(taxonomy.key(), CollectionItems.inline(prepare(taxonomy, selection, fields, request), request));
        }
        // WordPress keeps an empty result an object
        return RestResponse.ok(data.isEmpty() ? new PhpObjectNode() : data);
    }

    private RestResponse item(RestRequest request) {
        Optional<TaxonomyDefinition> taxonomy = taxonomies.find(TypesController.text(request.param("taxonomy")));
        if (taxonomy.isEmpty()) {
            throw new RestException(RestError.of("rest_taxonomy_invalid", "Invalid taxonomy.", 404));
        }
        if (request.method().equals("HEAD")) {
            return RestResponse.ok(RestJson.NODES.arrayNode());
        }
        Fields.Selection selection = Fields.forResponse(request, SCHEMA);
        return prepare(taxonomy.get(), selection, exactFields(selection), request);
    }

    /** The taxonomy's selected fields in WordPress's order, filtered by the context; links when selected. */
    private RestResponse prepare(
            TaxonomyDefinition taxonomy, Fields.Selection selection, Predicate<String> fields, RestRequest request) {
        String base = taxonomy.restBase().filter(rest -> !rest.isEmpty()).orElse(taxonomy.key());
        ObjectNode data = RestJson.NODES.objectNode();
        if (fields.test("name")) {
            data.set("name", text(taxonomy.labels().get("name")));
        }
        if (fields.test("slug")) {
            data.put("slug", taxonomy.key());
        }
        if (fields.test("capabilities")) {
            PhpObjectNode capabilities = new PhpObjectNode();
            taxonomy.capabilities().forEach(capabilities::put);
            data.set("capabilities", capabilities);
        }
        if (fields.test("description")) {
            data.put("description", taxonomy.description());
        }
        if (fields.test("labels")) {
            data.set("labels", TypesController.labels(taxonomy.labels(), LABEL_KEYS));
        }
        if (fields.test("types")) {
            var types = data.putArray("types");
            taxonomy.objectTypes().forEach(types::add);
        }
        if (fields.test("show_cloud")) {
            data.put("show_cloud", taxonomy.showTagcloud());
        }
        if (fields.test("hierarchical")) {
            data.put("hierarchical", taxonomy.hierarchical());
        }
        if (fields.test("rest_base")) {
            data.put("rest_base", base);
        }
        if (fields.test("rest_namespace")) {
            data.put("rest_namespace", NAMESPACE);
        }
        if (fields.test("visibility")) {
            ObjectNode visibility = data.putObject("visibility");
            visibility.put("public", taxonomy.publicly());
            visibility.put("publicly_queryable", taxonomy.publiclyQueryable());
            visibility.put("show_admin_column", taxonomy.showAdminColumn());
            visibility.put("show_in_nav_menus", taxonomy.showInNavMenus());
            visibility.put("show_in_quick_edit", taxonomy.showInQuickEdit());
            visibility.put("show_ui", taxonomy.showUi());
        }
        RestResponse response = RestResponse.ok(ContextFilter.apply(data, SCHEMA, request.context()));
        if (selection.includes("_links") || selection.includes("_embedded")) {
            response = response.withLinks(Links.none()
                    .add("collection", urls.route("/wp/v2/taxonomies"))
                    .add("https://api.w.org/items", urls.route("/" + NAMESPACE + "/" + base)));
        }
        return response;
    }

    private static JsonNode text(String value) {
        return value == null ? NullNode.getInstance() : TextNode.valueOf(value);
    }
}
