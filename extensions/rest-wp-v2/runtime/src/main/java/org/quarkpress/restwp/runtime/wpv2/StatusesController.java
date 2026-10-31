package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostStatuses;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.Links;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RestUrls;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.CollectionItems;
import org.quarkpress.restwp.runtime.ContextFilter;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * WordPress's post statuses routes: {@code /wp/v2/statuses}, every status that is not internal and then trash, keyed
 * by name, each only when the caller may see it (a public status always; the others with {@code edit_posts} of some
 * type shown in REST; an internal one never), and {@code /wp/v2/statuses/<status>}, refused by its permission when the
 * status is unknown or not visible. The edit context of the collection needs {@code edit_posts} of some REST type.
 * Fields are selected by their exact names, as WordPress's controller tests them, and every status links to its posts.
 */
@ApplicationScoped
public class StatusesController implements RestRouteProvider {

    /** The item schema, held once: the request's field selection is kept per schema instance. */
    static final ObjectNode SCHEMA = TypesController.parse("""
            {
              "$schema": "http://json-schema.org/draft-04/schema#",
              "title": "status",
              "type": "object",
              "properties": {
                "name": {
                  "description": "The title for the status.",
                  "type": "string",
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "private": {
                  "description": "Whether posts with this status should be private.",
                  "type": "boolean",
                  "context": ["edit"],
                  "readonly": true
                },
                "protected": {
                  "description": "Whether posts with this status should be protected.",
                  "type": "boolean",
                  "context": ["edit"],
                  "readonly": true
                },
                "public": {
                  "description": "Whether posts of this status should be shown in the front end of the site.",
                  "type": "boolean",
                  "context": ["view", "edit"],
                  "readonly": true
                },
                "queryable": {
                  "description": "Whether posts with this status should be publicly-queryable.",
                  "type": "boolean",
                  "context": ["view", "edit"],
                  "readonly": true
                },
                "show_in_list": {
                  "description": "Whether to include posts in the edit listing for their post type.",
                  "type": "boolean",
                  "context": ["edit"],
                  "readonly": true
                },
                "slug": {
                  "description": "An alphanumeric identifier for the status.",
                  "type": "string",
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "date_floating": {
                  "description": "Whether posts of this status may have floating published dates.",
                  "type": "boolean",
                  "context": ["view", "edit"],
                  "readonly": true
                }
              }
            }
            """);

    @Inject
    PostStatuses statuses;

    @Inject
    PostTypes types;

    @Inject
    PostPermissions permissions;

    @Inject
    RestUrls urls;

    @Override
    public int order() {
        return 200;
    }

    @Override
    public void register(RouteRegistry routes) {
        routes.route("wp/v2", "/statuses")
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(SCHEMA))
                        .permission(this::mayList)
                        .handler(this::list)
                        .build())
                .register();
        routes.route("wp/v2", "/statuses/(?P<status>[\\w-]+)")
                .arg(TypesController.urlArg("status", "An alphanumeric identifier for the status."))
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(SCHEMA))
                        .permission(this::mayRead)
                        .handler(this::item)
                        .build())
                .register();
    }

    private PermissionResult mayList(RestRequest request) {
        if (request.isEditContext() && !permissions.canEditSomeRestType(request.identity())) {
            return PermissionResult.denied(RestError.of(
                    "rest_cannot_view", "Sorry, you are not allowed to manage post statuses.", request.authCode()));
        }
        return PermissionResult.allowed();
    }

    private PermissionResult mayRead(RestRequest request) {
        String name = TypesController.text(request.param("status"));
        if (statuses.find(name).isEmpty()) {
            return PermissionResult.denied(RestError.of("rest_status_invalid", "Invalid status.", 404));
        }
        if (!permissions.statusVisible(request.identity(), name)) {
            return PermissionResult.denied(
                    RestError.of("rest_cannot_read_status", "Cannot view status.", request.authCode()));
        }
        return PermissionResult.allowed();
    }

    private RestResponse list(RestRequest request) {
        Predicate<String> fields = Fields.forResponse(request, SCHEMA)::includesExactly;
        ObjectNode data = RestJson.NODES.objectNode();
        for (String name : permissions.restStatuses()) {
            Optional<PostStatusDefinition> status = statuses.find(name);
            if (status.isEmpty() || !permissions.statusVisible(request.identity(), name)) {
                continue;
            }
            data.set(name, CollectionItems.inline(prepare(status.get(), fields, request), request));
        }
        return RestResponse.ok(data.isEmpty() ? RestJson.NODES.arrayNode() : data);
    }

    private RestResponse item(RestRequest request) {
        Optional<PostStatusDefinition> status = statuses.find(TypesController.text(request.param("status")));
        if (status.isEmpty()) {
            throw new RestException(RestError.of("rest_status_invalid", "Invalid status.", 404));
        }
        return prepare(status.get(), Fields.forResponse(request, SCHEMA)::includesExactly, request);
    }

    /** The status's selected fields in WordPress's order, filtered by the context, with its {@code archives} link. */
    private RestResponse prepare(PostStatusDefinition status, Predicate<String> fields, RestRequest request) {
        ObjectNode data = RestJson.NODES.objectNode();
        if (fields.test("name")) {
            data.put("name", status.label());
        }
        if (fields.test("private")) {
            data.put("private", status.privateStatus());
        }
        if (fields.test("protected")) {
            data.put("protected", status.protectedStatus());
        }
        if (fields.test("public")) {
            data.put("public", status.publicly());
        }
        if (fields.test("queryable")) {
            data.put("queryable", status.publiclyQueryable());
        }
        if (fields.test("show_in_list")) {
            data.put("show_in_list", status.showInAdminAllList());
        }
        if (fields.test("slug")) {
            data.put("slug", status.name());
        }
        if (fields.test("date_floating")) {
            data.put("date_floating", status.dateFloating());
        }
        String archives = status.name().equals("publish")
                ? urls.route(postsRoute())
                : urls.route(postsRoute(), Map.of("status", status.name()));
        return RestResponse.ok(ContextFilter.apply(data, SCHEMA, request.context()))
                .withLinks(Links.none().add("archives", archives));
    }

    /** The REST collection of posts, which every status links to. */
    private String postsRoute() {
        String base = types.find("post")
                .flatMap(post -> post.restBase())
                .filter(rest -> !rest.isEmpty())
                .orElse("post");
        return "/" + TypeExtras.of("post").restNamespace() + "/" + base;
    }
}
