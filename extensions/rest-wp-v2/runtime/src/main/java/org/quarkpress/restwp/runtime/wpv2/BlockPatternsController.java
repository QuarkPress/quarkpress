package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Optional;
import org.quarkpress.blocks.BlockPattern;
import org.quarkpress.blocks.BlockPatternCategory;
import org.quarkpress.blocks.BlockPatternCategoryRegistry;
import org.quarkpress.blocks.BlockPatternRegistry;
import org.quarkpress.blocks.PatternResolver;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * WordPress's block pattern routes over the registered patterns and pattern categories:
 * {@code /wp/v2/block-patterns/patterns} and {@code /wp/v2/block-patterns/categories}, GET only, each a plain list in
 * registration order without paging or links. Both need a caller who may edit posts of some type. They declare no
 * arguments, so any {@code context} is accepted and chooses the fields as WordPress chooses them: every field is in
 * the contexts {@code view}, {@code edit} and {@code embed}, so another context answers one empty record ({@code []})
 * per pattern or category.
 *
 * <p>A pattern's fields are, in WordPress's order and each only when the pattern sets it: {@code name},
 * {@code title}, {@code content} (its nested patterns resolved, see {@link PatternResolver}), {@code description},
 * {@code viewport_width}, {@code inserter}, {@code categories} (the old names {@code buttons}, {@code columns} and
 * {@code query} answered as {@code call-to-action}, {@code text} and {@code posts}), {@code keywords},
 * {@code block_types}, {@code post_types}, {@code template_types} and {@code source}. A category's are {@code name},
 * {@code label} and {@code description}, the last two when set.
 */
@ApplicationScoped
public class BlockPatternsController implements RestRouteProvider {

    private static final String PROPERTY = """
            {"description": "%s", "type": "%s", "readonly": true, "context": ["view", "edit", "embed"]}""";

    /** The pattern schema as WordPress describes it. */
    static final ObjectNode PATTERN_SCHEMA = TypesController.parse("""
            {
              "$schema": "http://json-schema.org/draft-04/schema#",
              "title": "block-pattern",
              "type": "object",
              "properties": {
                "name": %s,
                "title": %s,
                "content": %s,
                "description": %s,
                "viewport_width": %s,
                "inserter": %s,
                "categories": %s,
                "keywords": %s,
                "block_types": %s,
                "post_types": %s,
                "template_types": %s,
                "source": {
                  "description": "Where the pattern comes from e.g. core",
                  "type": "string",
                  "readonly": true,
                  "context": ["view", "edit", "embed"],
                  "enum": ["core", "plugin", "theme", "pattern-directory/core", "pattern-directory/theme",
                    "pattern-directory/featured"]
                }
              }
            }
            """.formatted(
                    property("The pattern name.", "string"),
                    property("The pattern title, in human readable format.", "string"),
                    property("The pattern content.", "string"),
                    property("The pattern detailed description.", "string"),
                    property("The pattern viewport width for inserter preview.", "number"),
                    property("Determines whether the pattern is visible in inserter.", "boolean"),
                    property("The pattern category slugs.", "array"),
                    property("The pattern keywords.", "array"),
                    property("Block types that the pattern is intended to be used with.", "array"),
                    property("An array of post types that the pattern is restricted to be used with.", "array"),
                    property("An array of template types where the pattern fits.", "array")));

    /** The pattern category schema as WordPress describes it. */
    static final ObjectNode CATEGORY_SCHEMA = TypesController.parse("""
            {
              "$schema": "http://json-schema.org/draft-04/schema#",
              "title": "block-pattern-category",
              "type": "object",
              "properties": {
                "name": %s,
                "label": %s,
                "description": %s
              }
            }
            """.formatted(
                    property("The category name.", "string"),
                    property("The category label, in human readable format.", "string"),
                    property("The category description, in human readable format.", "string")));

    @Inject
    BlockPatternRegistry patterns;

    @Inject
    BlockPatternCategoryRegistry categories;

    @Inject
    PatternResolver resolver;

    @Inject
    PostTypes types;

    @Inject
    PostPermissions permissions;

    private static String property(String description, String type) {
        return PROPERTY.formatted(description, type);
    }

    /** After the themes routes, where WordPress registers them. */
    @Override
    public int order() {
        return 700;
    }

    @Override
    public void register(RouteRegistry routes) {
        routes.route("wp/v2", "/block-patterns/patterns")
                .schema(() -> PATTERN_SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .permission(request ->
                                mayView(request, "Sorry, you are not allowed to view the registered block patterns."))
                        .handler(this::patterns)
                        .build())
                .register();
        routes.route("wp/v2", "/block-patterns/categories")
                .schema(() -> CATEGORY_SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .permission(request -> mayView(
                                request, "Sorry, you are not allowed to view the registered block pattern categories."))
                        .handler(this::categories)
                        .build())
                .register();
    }

    private PermissionResult mayView(RestRequest request, String message) {
        return EditorAccess.canEditAnyType(request, types, permissions)
                ? PermissionResult.allowed()
                : PermissionResult.denied(RestError.of("rest_cannot_view", message, request.authCode()));
    }

    private RestResponse patterns(RestRequest request) {
        Fields.Selection selection = Fields.forResponse(request, PATTERN_SCHEMA);
        ArrayNode data = RestJson.NODES.arrayNode();
        for (BlockPattern pattern : patterns.all()) {
            ObjectNode record = RestJson.NODES.objectNode();
            put(record, selection, "name", RestJson.NODES.textNode(pattern.name()));
            put(record, selection, "title", RestJson.NODES.textNode(pattern.title()));
            if (selection.includes("content")) {
                record.put("content", resolver.content(pattern));
            }
            put(record, selection, "description", pattern.description().map(RestJson.NODES::textNode));
            put(record, selection, "viewport_width", pattern.viewportWidth().map(RestJson.NODES::numberNode));
            put(record, selection, "inserter", pattern.inserter().map(RestJson.NODES::booleanNode));
            put(record, selection, "categories", pattern.categories().map(BlockPatternsController::migrated));
            put(record, selection, "keywords", pattern.keywords().map(BlockPatternsController::list));
            put(record, selection, "block_types", pattern.blockTypes().map(BlockPatternsController::list));
            put(record, selection, "post_types", pattern.postTypes().map(BlockPatternsController::list));
            put(record, selection, "template_types", pattern.templateTypes().map(BlockPatternsController::list));
            put(record, selection, "source", pattern.source().map(RestJson.NODES::textNode));
            data.add(asPhpArray(record));
        }
        return RestResponse.ok(data);
    }

    private RestResponse categories(RestRequest request) {
        if (request.method().equals("HEAD")) {
            // no body is sent, so none is built
            return RestResponse.ok(RestJson.NODES.arrayNode());
        }
        Fields.Selection selection = Fields.forResponse(request, CATEGORY_SCHEMA);
        ArrayNode data = RestJson.NODES.arrayNode();
        for (BlockPatternCategory category : categories.all()) {
            ObjectNode record = RestJson.NODES.objectNode();
            put(record, selection, "name", RestJson.NODES.textNode(category.name()));
            put(record, selection, "label", category.label().map(RestJson.NODES::textNode));
            put(record, selection, "description", category.description().map(RestJson.NODES::textNode));
            data.add(asPhpArray(record));
        }
        return RestResponse.ok(data);
    }

    private static void put(ObjectNode record, Fields.Selection selection, String field, JsonNode value) {
        if (selection.includes(field)) {
            record.set(field, value);
        }
    }

    private static void put(
            ObjectNode record, Fields.Selection selection, String field, Optional<? extends JsonNode> value) {
        value.ifPresent(present -> put(record, selection, field, present));
    }

    /** An emptied record prints as PHP prints an empty array: {@code []}. */
    private static JsonNode asPhpArray(ObjectNode record) {
        return record.isEmpty() ? RestJson.NODES.arrayNode() : record;
    }

    private static JsonNode list(List<String> values) {
        ArrayNode list = RestJson.NODES.arrayNode(values.size());
        values.forEach(list::add);
        return list;
    }

    /** The categories with the three names WordPress renamed answered by their new names, in place. */
    private static JsonNode migrated(List<String> categories) {
        ArrayNode list = RestJson.NODES.arrayNode(categories.size());
        for (String category : categories) {
            list.add(
                    switch (category) {
                        case "buttons" -> "call-to-action";
                        case "columns" -> "text";
                        case "query" -> "posts";
                        default -> category;
                    });
        }
        return list;
    }
}
