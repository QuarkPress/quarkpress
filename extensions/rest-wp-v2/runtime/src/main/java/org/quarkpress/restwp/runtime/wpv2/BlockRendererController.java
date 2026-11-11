package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import org.quarkpress.blocks.BlockRenderContext;
import org.quarkpress.blocks.BlockRenderer;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeRegistry;
import org.quarkpress.core.PostId;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.PermissionResult;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;
import org.quarkpress.restwp.RestRouteProvider;
import org.quarkpress.restwp.RouteRegistry;
import org.quarkpress.restwp.runtime.ArgChecks;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaSanitizer;
import org.quarkpress.restwp.runtime.SchemaValidator;

/**
 * WordPress's block renderer route {@code /wp/v2/block-renderer/<namespace>/<name>} over the registered block renderer
 * beans, for GET and POST alike. WordPress's checks, in its order:
 *
 * <ol>
 *   <li>the arguments, defaults included, so a request without {@code context=edit} fails for every caller (the only
 *       context is {@code edit}, the default {@code view}). A registered type's {@code attributes} must be an object of
 *       its attributes, unknown ones refused, the errors named from an empty path; an unregistered type's may be
 *       anything and becomes {@code true};
 *   <li>the permission: with a {@code post_id} above 0, {@code edit_post} on that post of this site (a missing post is
 *       refused as one the caller may not edit); otherwise {@code edit_posts};
 *   <li>the lookup, case-sensitive: a type that is not registered, or has no renderer, is 404 {@code block_invalid}
 *       (with {@code Allow}, since the permission passed).
 * </ol>
 *
 * The answer is {@code {"rendered": <html>}} from the renderer, given the sanitized attributes with the declared
 * defaults of those missing, and the post above 0; a renderer answering null renders as {@code ""}, and so does a block
 * whose {@code metadata.blockVisibility} is {@code false}. WordPress's other render filters (the classes and styles
 * its block supports add) are not applied. The answer is not reduced by context.
 */
@ApplicationScoped
public class BlockRendererController implements RestRouteProvider {

    static final String ROUTE = "/block-renderer/(?P<name>[a-z0-9-]+/[a-z0-9-]+)";

    static final ObjectNode SCHEMA = TypesController.parse("""
            {
              "$schema": "http://json-schema.org/schema#",
              "title": "rendered-block",
              "type": "object",
              "properties": {
                "rendered": {
                  "description": "The rendered block.",
                  "type": "string",
                  "required": true,
                  "context": ["edit"]
                }
              }
            }
            """);

    private static final String OF_THIS_POST = "Sorry, you are not allowed to read blocks of this post.";

    private static final String AS_THIS_USER = "Sorry, you are not allowed to read blocks as this user.";

    @Inject
    BlockTypeRegistry registry;

    @Inject
    PostPermissions permissions;

    /** Right after the search routes and before the block types, where WordPress registers it. */
    @Override
    public int order() {
        return 560;
    }

    @Override
    public void register(RouteRegistry routes) {
        routes.route("wp/v2", ROUTE)
                .arg(TypesController.urlArg("name", "Unique registered name for the block."))
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET", "POST")
                        .arg(Paging.contextArg(SCHEMA))
                        .arg(attributesArg())
                        .arg(postIdArg())
                        .permission(this::mayRender)
                        .handler(this::render)
                        .build())
                .register();
    }

    private Arg attributesArg() {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("description", "Attributes for the block.");
        schema.put("type", "object");
        // WordPress's empty array, printed []
        schema.putArray("default");
        return new Arg(
                "attributes",
                schema,
                false,
                Optional.of(RestJson.NODES.arrayNode()),
                Arg.Phase.VALIDATE,
                Optional.of(this::sanitizeAttributes),
                Optional.of(this::validAttributes));
    }

    private static Arg postIdArg() {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("description", "ID of the post context.");
        schema.put("type", "integer");
        return new Arg("post_id", schema, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    /** The type the request being checked names, as WordPress reads the name (a query value before the URL's). */
    private Optional<BlockType> typeWhileChecking() {
        return ArgChecks.checking().flatMap(request -> registry.find(name(request)));
    }

    private static String name(RestRequest request) {
        return TypesController.text(request.param("name"));
    }

    /** The object a registered type's attributes must be: its attributes, and no others. */
    private ObjectNode attributesSchema(BlockType type) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("type", "object");
        schema.set("properties", registry.attributes(type));
        schema.put("additionalProperties", false);
        return schema;
    }

    private boolean validAttributes(JsonNode value) {
        Optional<BlockType> type = typeWhileChecking();
        if (type.isEmpty()) {
            // anything goes for a type that is not registered: the lookup answers for it
            return true;
        }
        Optional<RestError> invalid = SchemaValidator.validate(value, attributesSchema(type.get()), "");
        if (invalid.isPresent()) {
            throw new RestException(invalid.get());
        }
        return true;
    }

    private JsonNode sanitizeAttributes(JsonNode value) {
        Optional<BlockType> type = typeWhileChecking();
        if (type.isEmpty()) {
            return BooleanNode.TRUE;
        }
        return SchemaSanitizer.sanitize(value, attributesSchema(type.get()), "");
    }

    /** The {@code post_id} as PHP's integer cast reads it; 0 when absent. */
    private static long postId(RestRequest request) {
        return request.param("post_id").map(PhpValues::toLong).orElse(0L);
    }

    private PermissionResult mayRender(RestRequest request) {
        long post = postId(request);
        if (post > 0) {
            return permissions.can(request.identity(), "edit_post", PostId.of(post))
                    ? PermissionResult.allowed()
                    : PermissionResult.denied(RestError.of("block_cannot_read", OF_THIS_POST, request.authCode()));
        }
        return permissions.canType(request.identity(), "edit_posts", "post")
                ? PermissionResult.allowed()
                : PermissionResult.denied(RestError.of("block_cannot_read", AS_THIS_USER, request.authCode()));
    }

    private RestResponse render(RestRequest request) {
        String name = name(request);
        Optional<BlockType> type = registry.find(name);
        Optional<BlockRenderer> renderer = type.flatMap(found -> registry.renderer(found.name()));
        if (renderer.isEmpty()) {
            throw new RestException(RestError.of("block_invalid", "Invalid block.", 404));
        }
        long post = postId(request);
        ObjectNode attributes = withDefaults(request.param("attributes"), registry.attributes(type.get()));
        String html = renderer.get()
                .render(new BlockRenderContext(
                        name, attributes.deepCopy(), post > 0 ? OptionalLong.of(post) : OptionalLong.empty()));
        ObjectNode body = RestJson.NODES.objectNode();
        // a block hidden through its metadata renders as nothing, whatever its renderer answered
        body.put("rendered", html == null || hidden(attributes) ? "" : html);
        return RestResponse.ok(body);
    }

    /** Whether the attributes hide the block: {@code metadata.blockVisibility} is exactly {@code false}. */
    private static boolean hidden(ObjectNode attributes) {
        JsonNode visibility = attributes.path("metadata").path("blockVisibility");
        return visibility.isBoolean() && !visibility.booleanValue();
    }

    /**
     * The sanitized attributes, then each declared default (other than null) of an attribute they lack, in
     * declaration order.
     */
    private static ObjectNode withDefaults(Optional<JsonNode> sanitized, ObjectNode declared) {
        ObjectNode attributes = sanitized
                .filter(JsonNode::isObject)
                .map(value -> ((ObjectNode) value).deepCopy())
                .orElseGet(RestJson.NODES::objectNode);
        for (Map.Entry<String, JsonNode> attribute : declared.properties()) {
            JsonNode fallback = attribute.getValue().get("default");
            if (fallback != null && !fallback.isNull() && !attributes.has(attribute.getKey())) {
                attributes.set(attribute.getKey(), fallback.deepCopy());
            }
        }
        return attributes;
    }
}
