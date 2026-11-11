package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.blocks.BlockType;
import org.quarkpress.blocks.BlockTypeRegistry;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
import org.quarkpress.restwp.Fields;
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
import org.quarkpress.restwp.runtime.RestJson;

/**
 * WordPress's block types routes over the registered block types: {@code /wp/v2/block-types} (every type, in
 * registration order), {@code /wp/v2/block-types/<namespace>} (the types of one namespace) and
 * {@code /wp/v2/block-types/<namespace>/<name>} (one type), GET only, without paging. Every route needs a caller who
 * may edit posts of some type; one type's route then needs the type to be registered under exactly that name (the
 * route itself matches ignoring case), else 404 from the permission check, so without {@code Allow}. The collections'
 * {@code namespace} parameter is read as WordPress reads it, from the query before the URL, so a query value wins over
 * the URL's namespace; one empty to PHP ({@code ""} or {@code "0"}) filters nothing. Every field is in every
 * context.
 */
@ApplicationScoped
public class BlockTypesController implements RestRouteProvider {

    static final String BASE = "/wp/v2/block-types";

    private static final String CANNOT_VIEW = "Sorry, you are not allowed to manage block types.";

    /** The item schema as WordPress describes it, held once: the request's field selection is kept per instance. */
    static final ObjectNode SCHEMA = TypesController.parse("""
            {
              "$schema": "http://json-schema.org/draft-04/schema#",
              "title": "block-type",
              "type": "object",
              "properties": {
                "api_version": {
                  "description": "Version of block API.",
                  "type": "integer",
                  "default": 1,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "title": {
                  "description": "Title of block type.",
                  "type": "string",
                  "default": "",
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "name": {
                  "description": "Unique name identifying the block type.",
                  "type": "string",
                  "pattern": "^[a-z][a-z0-9-]*/[a-z][a-z0-9-]*$",
                  "required": true,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "description": {
                  "description": "Description of block type.",
                  "type": "string",
                  "default": "",
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "icon": {
                  "description": "Icon of block type.",
                  "type": ["string", "null"],
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "attributes": {
                  "description": "Block attributes.",
                  "type": ["object", "null"],
                  "properties": [],
                  "default": null,
                  "additionalProperties": {
                    "type": "object"
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "provides_context": {
                  "description": "Context provided by blocks of this type.",
                  "type": "object",
                  "properties": [],
                  "additionalProperties": {
                    "type": "string"
                  },
                  "default": [],
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "uses_context": {
                  "description": "Context values inherited by blocks of this type.",
                  "type": "array",
                  "default": [],
                  "items": {
                    "type": "string"
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "selectors": {
                  "description": "Custom CSS selectors.",
                  "type": "object",
                  "default": [],
                  "properties": [],
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "supports": {
                  "description": "Block supports.",
                  "type": "object",
                  "default": [],
                  "properties": [],
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "category": {
                  "description": "Block category.",
                  "type": ["string", "null"],
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "is_dynamic": {
                  "description": "Is the block dynamically rendered.",
                  "type": "boolean",
                  "default": false,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "editor_script_handles": {
                  "description": "Editor script handles.",
                  "type": ["array"],
                  "default": [],
                  "items": {
                    "type": "string"
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "script_handles": {
                  "description": "Public facing and editor script handles.",
                  "type": ["array"],
                  "default": [],
                  "items": {
                    "type": "string"
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "view_script_handles": {
                  "description": "Public facing script handles.",
                  "type": ["array"],
                  "default": [],
                  "items": {
                    "type": "string"
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "view_script_module_ids": {
                  "description": "Public facing script module IDs.",
                  "type": ["array"],
                  "default": [],
                  "items": {
                    "type": "string"
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "editor_style_handles": {
                  "description": "Editor style handles.",
                  "type": ["array"],
                  "default": [],
                  "items": {
                    "type": "string"
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "style_handles": {
                  "description": "Public facing and editor style handles.",
                  "type": ["array"],
                  "default": [],
                  "items": {
                    "type": "string"
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "view_style_handles": {
                  "description": "Public facing style handles.",
                  "type": ["array"],
                  "default": [],
                  "items": {
                    "type": "string"
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "styles": {
                  "description": "Block style variations.",
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {
                        "description": "Unique name identifying the style.",
                        "type": "string",
                        "required": true
                      },
                      "label": {
                        "description": "The human-readable label for the style.",
                        "type": "string"
                      },
                      "inline_style": {
                        "description": "Inline CSS code that registers the CSS class required for the style.",
                        "type": "string"
                      },
                      "style_handle": {
                        "description": "Contains the handle that defines the block style.",
                        "type": "string"
                      }
                    }
                  },
                  "default": [],
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "variations": {
                  "description": "Block variations.",
                  "type": "array",
                  "items": {
                    "type": "object",
                    "properties": {
                      "name": {
                        "description": "The unique and machine-readable name.",
                        "type": "string",
                        "required": true
                      },
                      "title": {
                        "description": "A human-readable variation title.",
                        "type": "string",
                        "required": true
                      },
                      "description": {
                        "description": "A detailed variation description.",
                        "type": "string",
                        "required": false
                      },
                      "category": {
                        "description": "Block category.",
                        "type": ["string", "null"],
                        "default": null,
                        "context": ["embed", "view", "edit"],
                        "readonly": true
                      },
                      "icon": {
                        "description": "Icon of block type.",
                        "type": ["string", "null"],
                        "default": null,
                        "context": ["embed", "view", "edit"],
                        "readonly": true
                      },
                      "isDefault": {
                        "description": "Indicates whether the current variation is the default one.",
                        "type": "boolean",
                        "required": false,
                        "default": false
                      },
                      "attributes": {
                        "description": "The initial values for attributes.",
                        "type": "object"
                      },
                      "innerBlocks": {
                        "description": "The list of inner blocks used in the example.",
                        "type": "array",
                        "items": {
                          "type": "object",
                          "properties": {
                            "name": {
                              "description": "The name of the inner block.",
                              "type": "string",
                              "pattern": "^[a-z][a-z0-9-]*/[a-z][a-z0-9-]*$",
                              "required": true
                            },
                            "attributes": {
                              "description": "The attributes of the inner block.",
                              "type": "object"
                            },
                            "innerBlocks": {
                              "description": "A list of the inner block's own inner blocks. This is a recursive definition following the parent innerBlocks schema.",
                              "type": "array"
                            }
                          }
                        }
                      },
                      "example": {
                        "description": "Block example.",
                        "type": ["object", "null"],
                        "default": null,
                        "properties": {
                          "attributes": {
                            "description": "The attributes used in the example.",
                            "type": "object"
                          },
                          "innerBlocks": {
                            "description": "The list of inner blocks used in the example.",
                            "type": "array",
                            "items": {
                              "type": "object",
                              "properties": {
                                "name": {
                                  "description": "The name of the inner block.",
                                  "type": "string",
                                  "pattern": "^[a-z][a-z0-9-]*/[a-z][a-z0-9-]*$",
                                  "required": true
                                },
                                "attributes": {
                                  "description": "The attributes of the inner block.",
                                  "type": "object"
                                },
                                "innerBlocks": {
                                  "description": "A list of the inner block's own inner blocks. This is a recursive definition following the parent innerBlocks schema.",
                                  "type": "array"
                                }
                              }
                            }
                          }
                        },
                        "context": ["embed", "view", "edit"],
                        "readonly": true
                      },
                      "scope": {
                        "description": "The list of scopes where the variation is applicable. When not provided, it assumes all available scopes.",
                        "type": ["array", "null"],
                        "default": null,
                        "items": {
                          "type": "string",
                          "enum": ["block", "inserter", "transform"]
                        },
                        "readonly": true
                      },
                      "keywords": {
                        "description": "Block keywords.",
                        "type": "array",
                        "items": {
                          "type": "string"
                        },
                        "default": [],
                        "context": ["embed", "view", "edit"],
                        "readonly": true
                      }
                    }
                  },
                  "readonly": true,
                  "context": ["embed", "view", "edit"],
                  "default": null
                },
                "textdomain": {
                  "description": "Public text domain.",
                  "type": ["string", "null"],
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "parent": {
                  "description": "Parent blocks.",
                  "type": ["array", "null"],
                  "items": {
                    "type": "string",
                    "pattern": "^[a-z][a-z0-9-]*/[a-z][a-z0-9-]*$"
                  },
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "ancestor": {
                  "description": "Ancestor blocks.",
                  "type": ["array", "null"],
                  "items": {
                    "type": "string",
                    "pattern": "^[a-z][a-z0-9-]*/[a-z][a-z0-9-]*$"
                  },
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "allowed_blocks": {
                  "description": "Allowed child block types.",
                  "type": ["array", "null"],
                  "items": {
                    "type": "string",
                    "pattern": "^[a-z][a-z0-9-]*/[a-z][a-z0-9-]*$"
                  },
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "keywords": {
                  "description": "Block keywords.",
                  "type": "array",
                  "items": {
                    "type": "string"
                  },
                  "default": [],
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "example": {
                  "description": "Block example.",
                  "type": ["object", "null"],
                  "default": null,
                  "properties": {
                    "attributes": {
                      "description": "The attributes used in the example.",
                      "type": "object"
                    },
                    "innerBlocks": {
                      "description": "The list of inner blocks used in the example.",
                      "type": "array",
                      "items": {
                        "type": "object",
                        "properties": {
                          "name": {
                            "description": "The name of the inner block.",
                            "type": "string",
                            "pattern": "^[a-z][a-z0-9-]*/[a-z][a-z0-9-]*$",
                            "required": true
                          },
                          "attributes": {
                            "description": "The attributes of the inner block.",
                            "type": "object"
                          },
                          "innerBlocks": {
                            "description": "A list of the inner block's own inner blocks. This is a recursive definition following the parent innerBlocks schema.",
                            "type": "array"
                          }
                        }
                      }
                    }
                  },
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "block_hooks": {
                  "description": "This block is automatically inserted near any occurrence of the block types used as keys of this map, into a relative position given by the corresponding value.",
                  "type": "object",
                  "patternProperties": {
                    "^[a-z][a-z0-9-]*/[a-z][a-z0-9-]*$": {
                      "type": "string",
                      "enum": ["before", "after", "first_child", "last_child"]
                    }
                  },
                  "default": [],
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "editor_script": {
                  "description": "Editor script handle. DEPRECATED: Use `editor_script_handles` instead.",
                  "type": ["string", "null"],
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "script": {
                  "description": "Public facing and editor script handle. DEPRECATED: Use `script_handles` instead.",
                  "type": ["string", "null"],
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "view_script": {
                  "description": "Public facing script handle. DEPRECATED: Use `view_script_handles` instead.",
                  "type": ["string", "null"],
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "editor_style": {
                  "description": "Editor style handle. DEPRECATED: Use `editor_style_handles` instead.",
                  "type": ["string", "null"],
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                },
                "style": {
                  "description": "Public facing and editor style handle. DEPRECATED: Use `style_handles` instead.",
                  "type": ["string", "null"],
                  "default": null,
                  "context": ["embed", "view", "edit"],
                  "readonly": true
                }
              }
            }
            """);

    @Inject
    BlockTypeRegistry registry;

    @Inject
    PostTypes types;

    @Inject
    PostPermissions permissions;

    @Inject
    RestUrls urls;

    /** After the search routes and before the settings, where WordPress registers them. */
    @Override
    public int order() {
        return 570;
    }

    @Override
    public void register(RouteRegistry routes) {
        Arg namespace = TypesController.urlArg("namespace", "Block namespace.");
        routes.route("wp/v2", "/block-types")
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(SCHEMA))
                        .arg(namespace)
                        .permission(this::mayView)
                        .handler(this::list)
                        .build())
                .register();
        routes.route("wp/v2", "/block-types/(?P<namespace>[a-zA-Z0-9_-]+)")
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(SCHEMA))
                        .arg(namespace)
                        .permission(this::mayView)
                        .handler(this::list)
                        .build())
                .register();
        routes.route("wp/v2", "/block-types/(?P<namespace>[a-zA-Z0-9_-]+)/(?P<name>[a-zA-Z0-9_-]+)")
                .arg(TypesController.urlArg("name", "Block name."))
                .arg(namespace)
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(Paging.contextArg(SCHEMA))
                        .permission(this::mayRead)
                        .handler(this::item)
                        .build())
                .register();
    }

    private PermissionResult mayView(RestRequest request) {
        return EditorAccess.canEditAnyType(request, types, permissions)
                ? PermissionResult.allowed()
                : PermissionResult.denied(RestError.of("rest_block_type_cannot_view", CANNOT_VIEW, request.authCode()));
    }

    private PermissionResult mayRead(RestRequest request) {
        PermissionResult view = mayView(request);
        if (view instanceof PermissionResult.Denied) {
            return view;
        }
        return requested(request).isPresent() ? view : PermissionResult.denied(invalid());
    }

    private static RestError invalid() {
        return RestError.of("rest_block_type_invalid", "Invalid block type.", 404);
    }

    /** The type named by the request's namespace and name, looked up exactly as written. */
    private Optional<BlockType> requested(RestRequest request) {
        return registry.find(
                TypesController.text(request.param("namespace")) + "/" + TypesController.text(request.param("name")));
    }

    private RestResponse list(RestRequest request) {
        if (request.method().equals("HEAD")) {
            // no body is sent, so none is built
            return RestResponse.ok(RestJson.NODES.arrayNode());
        }
        String namespace = TypesController.text(request.param("namespace"));
        // empty to PHP ("" or "0") filters nothing
        boolean filtersNothing = namespace.isEmpty() || namespace.equals("0");
        Fields.Selection selection = Fields.forResponse(request, SCHEMA);
        ArrayNode data = RestJson.NODES.arrayNode();
        for (BlockType type : registry.all()) {
            if (filtersNothing || inNamespace(type.name(), namespace)) {
                data.add(CollectionItems.inline(prepare(type, selection), request));
            }
        }
        return RestResponse.ok(data);
    }

    private static boolean inNamespace(String name, String namespace) {
        return name.length() > namespace.length()
                && name.charAt(namespace.length()) == '/'
                && name.startsWith(namespace);
    }

    private RestResponse item(RestRequest request) {
        BlockType type = requested(request).orElseThrow(() -> new RestException(invalid()));
        return prepare(type, Fields.forResponse(request, SCHEMA));
    }

    /** The type's selected fields; its links only when {@code _links} or {@code _embedded} is selected. */
    private RestResponse prepare(BlockType type, Fields.Selection selection) {
        boolean dynamic = registry.isDynamic(type.name());
        RestResponse response =
                RestResponse.ok(BlockTypeFields.of(type, registry.attributes(type), dynamic, selection));
        if (selection.includes("_links") || selection.includes("_embedded")) {
            response = response.withLinks(BlockTypeFields.links(type, dynamic, urls));
        }
        return response;
    }
}
