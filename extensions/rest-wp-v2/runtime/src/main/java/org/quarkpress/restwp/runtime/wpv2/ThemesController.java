package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Endpoint;
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
 * WordPress's themes routes over the one theme QuarkPress has, the {@link SyntheticTheme}: {@code /wp/v2/themes}
 * (one argument, {@code status}, a list of {@code active} and {@code inactive}, checked before the permission) and
 * {@code /wp/v2/themes/<stylesheet>} (one or two path segments, decoded once with the request path), GET only, no batching, no context
 * (the record is the same whatever context is asked for). A caller who switches themes or manages the network's sees
 * every list and item; any other caller who may edit posts of some type sees exactly {@code status=active} and the
 * active theme's item, and is refused anything else with {@code rest_cannot_view_themes}; everyone else is refused
 * with {@code rest_cannot_view_active_theme}. An item is looked up after the permission, so a theme manager's unknown
 * stylesheet is 404 {@code rest_theme_not_found}, with {@code Allow}. The collection answers {@code X-WP-Total} and
 * {@code X-WP-TotalPages} (always 1), never a pagination link.
 */
@ApplicationScoped
public class ThemesController implements RestRouteProvider {

    /** The theme schema as WordPress describes it, with the 24 features and their types. */
    static final ObjectNode SCHEMA = TypesController.parse("""
            {
              "$schema": "http://json-schema.org/draft-04/schema#",
              "title": "theme",
              "type": "object",
              "properties": {
                "stylesheet": {
                  "description": "The theme's stylesheet. This uniquely identifies the theme.",
                  "type": "string",
                  "readonly": true
                },
                "stylesheet_uri": {
                  "description": "The uri for the theme's stylesheet directory.",
                  "type": "string",
                  "format": "uri",
                  "readonly": true
                },
                "template": {
                  "description": "The theme's template. If this is a child theme, this refers to the parent theme, otherwise this is the same as the theme's stylesheet.",
                  "type": "string",
                  "readonly": true
                },
                "template_uri": {
                  "description": "The uri for the theme's template directory. If this is a child theme, this refers to the parent theme, otherwise this is the same as the theme's stylesheet directory.",
                  "type": "string",
                  "format": "uri",
                  "readonly": true
                },
                "author": {
                  "description": "The theme author.",
                  "type": "object",
                  "readonly": true,
                  "properties": {
                    "raw": {"description": "The theme author's name, as found in the theme header.", "type": "string"},
                    "rendered": {"description": "HTML for the theme author, transformed for display.", "type": "string"}
                  }
                },
                "author_uri": {
                  "description": "The website of the theme author.",
                  "type": "object",
                  "readonly": true,
                  "properties": {
                    "raw": {"description": "The website of the theme author, as found in the theme header.", "type": "string", "format": "uri"},
                    "rendered": {"description": "The website of the theme author, transformed for display.", "type": "string", "format": "uri"}
                  }
                },
                "description": {
                  "description": "A description of the theme.",
                  "type": "object",
                  "readonly": true,
                  "properties": {
                    "raw": {"description": "The theme description, as found in the theme header.", "type": "string"},
                    "rendered": {"description": "The theme description, transformed for display.", "type": "string"}
                  }
                },
                "is_block_theme": {
                  "description": "Whether the theme is a block-based theme.",
                  "type": "boolean",
                  "readonly": true
                },
                "name": {
                  "description": "The name of the theme.",
                  "type": "object",
                  "readonly": true,
                  "properties": {
                    "raw": {"description": "The theme name, as found in the theme header.", "type": "string"},
                    "rendered": {"description": "The theme name, transformed for display.", "type": "string"}
                  }
                },
                "requires_php": {
                  "description": "The minimum PHP version required for the theme to work.",
                  "type": "string",
                  "readonly": true
                },
                "requires_wp": {
                  "description": "The minimum WordPress version required for the theme to work.",
                  "type": "string",
                  "readonly": true
                },
                "screenshot": {
                  "description": "The theme's screenshot URL.",
                  "type": "string",
                  "format": "uri",
                  "readonly": true
                },
                "tags": {
                  "description": "Tags indicating styles and features of the theme.",
                  "type": "object",
                  "readonly": true,
                  "properties": {
                    "raw": {"description": "The theme tags, as found in the theme header.", "type": "array", "items": {"type": "string"}},
                    "rendered": {"description": "The theme tags, transformed for display.", "type": "string"}
                  }
                },
                "textdomain": {
                  "description": "The theme's text domain.",
                  "type": "string",
                  "readonly": true
                },
                "theme_supports": {
                  "description": "Features supported by this theme.",
                  "type": "object",
                  "readonly": true,
                  "properties": {
                    "align-wide": {"description": "Whether theme opts in to wide alignment CSS class.", "type": "boolean", "default": false},
                    "automatic-feed-links": {"description": "Whether posts and comments RSS feed links are added to head.", "type": "boolean", "default": false},
                    "block-templates": {"description": "Whether a theme uses block-based templates.", "type": "boolean", "default": false},
                    "block-template-parts": {"description": "Whether a theme uses block-based template parts.", "type": "boolean", "default": false},
                    "custom-background": {
                      "description": "Custom background if defined by the theme.",
                      "type": ["boolean", "object"],
                      "default": false,
                      "properties": {
                        "default-image": {"type": "string", "format": "uri"},
                        "default-preset": {"type": "string", "enum": ["default", "fill", "fit", "repeat", "custom"]},
                        "default-position-x": {"type": "string", "enum": ["left", "center", "right"]},
                        "default-position-y": {"type": "string", "enum": ["left", "center", "right"]},
                        "default-size": {"type": "string", "enum": ["auto", "contain", "cover"]},
                        "default-repeat": {"type": "string", "enum": ["repeat-x", "repeat-y", "repeat", "no-repeat"]},
                        "default-attachment": {"type": "string", "enum": ["scroll", "fixed"]},
                        "default-color": {"type": "string"}
                      },
                      "additionalProperties": false
                    },
                    "custom-header": {
                      "description": "Custom header if defined by the theme.",
                      "type": ["boolean", "object"],
                      "default": false,
                      "properties": {
                        "default-image": {"type": "string", "format": "uri"},
                        "random-default": {"type": "boolean"},
                        "width": {"type": "integer"},
                        "height": {"type": "integer"},
                        "flex-height": {"type": "boolean"},
                        "flex-width": {"type": "boolean"},
                        "default-text-color": {"type": "string"},
                        "header-text": {"type": "boolean"},
                        "uploads": {"type": "boolean"},
                        "video": {"type": "boolean"}
                      },
                      "additionalProperties": false
                    },
                    "custom-logo": {
                      "description": "Custom logo if defined by the theme.",
                      "type": ["boolean", "object"],
                      "default": false,
                      "properties": {
                        "width": {"type": "integer"},
                        "height": {"type": "integer"},
                        "flex-width": {"type": "boolean"},
                        "flex-height": {"type": "boolean"},
                        "header-text": {"type": "array", "items": {"type": "string"}},
                        "unlink-homepage-logo": {"type": "boolean"}
                      },
                      "additionalProperties": false
                    },
                    "customize-selective-refresh-widgets": {"description": "Whether the theme enables Selective Refresh for Widgets being managed with the Customizer.", "type": "boolean", "default": false},
                    "dark-editor-style": {"description": "Whether theme opts in to the dark editor style UI.", "type": "boolean", "default": false},
                    "disable-custom-colors": {"description": "Whether the theme disables custom colors.", "type": "boolean", "default": false},
                    "disable-custom-font-sizes": {"description": "Whether the theme disables custom font sizes.", "type": "boolean", "default": false},
                    "disable-custom-gradients": {"description": "Whether the theme disables custom gradients.", "type": "boolean", "default": false},
                    "disable-layout-styles": {"description": "Whether the theme disables generated layout styles.", "type": "boolean", "default": false},
                    "editor-color-palette": {
                      "description": "Custom color palette if defined by the theme.",
                      "type": ["boolean", "array"],
                      "default": false,
                      "items": {
                        "type": "object",
                        "properties": {"name": {"type": "string"}, "slug": {"type": "string"}, "color": {"type": "string"}},
                        "additionalProperties": false
                      }
                    },
                    "editor-font-sizes": {
                      "description": "Custom font sizes if defined by the theme.",
                      "type": ["boolean", "array"],
                      "default": false,
                      "items": {
                        "type": "object",
                        "properties": {"name": {"type": "string"}, "size": {"type": "number"}, "slug": {"type": "string"}},
                        "additionalProperties": false
                      }
                    },
                    "editor-gradient-presets": {
                      "description": "Custom gradient presets if defined by the theme.",
                      "type": ["boolean", "array"],
                      "default": false,
                      "items": {
                        "type": "object",
                        "properties": {"name": {"type": "string"}, "gradient": {"type": "string"}, "slug": {"type": "string"}},
                        "additionalProperties": false
                      }
                    },
                    "editor-spacing-sizes": {
                      "description": "Custom spacing sizes if defined by the theme.",
                      "type": ["boolean", "array"],
                      "default": false,
                      "items": {
                        "type": "object",
                        "properties": {"name": {"type": "string"}, "size": {"type": "string"}, "slug": {"type": "string"}},
                        "additionalProperties": false
                      }
                    },
                    "editor-styles": {"description": "Whether theme opts in to the editor styles CSS wrapper.", "type": "boolean", "default": false},
                    "html5": {
                      "description": "Allows use of HTML5 markup for search forms, comment forms, comment lists, gallery, and caption.",
                      "type": ["boolean", "array"],
                      "default": false,
                      "items": {"type": "string", "enum": ["search-form", "comment-form", "comment-list", "gallery", "caption", "script", "style"]}
                    },
                    "formats": {
                      "description": "Post formats supported.",
                      "type": "array",
                      "default": ["standard"],
                      "items": {
                        "type": "string",
                        "enum": {"standard": "standard", "aside": "aside", "chat": "chat", "gallery": "gallery", "link": "link", "image": "image", "quote": "quote", "status": "status", "video": "video", "audio": "audio"}
                      }
                    },
                    "post-thumbnails": {
                      "description": "The post types that support thumbnails or true if all post types are supported.",
                      "type": ["boolean", "array"],
                      "default": false,
                      "items": {"type": "string"}
                    },
                    "responsive-embeds": {"description": "Whether the theme supports responsive embedded content.", "type": "boolean", "default": false},
                    "title-tag": {"description": "Whether the theme can manage the document title tag.", "type": "boolean", "default": false},
                    "wp-block-styles": {"description": "Whether theme opts in to default WordPress block styles for viewing.", "type": "boolean", "default": false}
                  }
                },
                "theme_uri": {
                  "description": "The URI of the theme's webpage.",
                  "type": "object",
                  "readonly": true,
                  "properties": {
                    "raw": {"description": "The URI of the theme's webpage, as found in the theme header.", "type": "string", "format": "uri"},
                    "rendered": {"description": "The URI of the theme's webpage, transformed for display.", "type": "string", "format": "uri"}
                  }
                },
                "version": {
                  "description": "The theme's current version.",
                  "type": "string",
                  "readonly": true
                },
                "status": {
                  "description": "A named status for the theme.",
                  "type": "string",
                  "enum": ["inactive", "active"]
                },
                "default_template_types": {
                  "description": "A list of default template types.",
                  "type": "array",
                  "readonly": true,
                  "items": {
                    "type": "object",
                    "properties": {"slug": {"type": "string"}, "title": {"type": "string"}, "description": {"type": "string"}}
                  }
                },
                "default_template_part_areas": {
                  "description": "A list of allowed area values for template parts.",
                  "type": "array",
                  "readonly": true,
                  "items": {
                    "type": "object",
                    "properties": {"area": {"type": "string"}, "label": {"type": "string"}, "description": {"type": "string"}, "icon": {"type": "string"}, "area_tag": {"type": "string"}}
                  }
                }
              }
            }
            """);

    /** WordPress's route key of the item: one or two segments of characters other than {@code / : < > * ? " |}. */
    static final String ITEM = "/themes/(?P<stylesheet>[^\\/:<>\\*\\?\"\\|]+(?:\\/[^\\/:<>\\*\\?\"\\|]+)?)";

    private static final String CANNOT_VIEW = "Sorry, you are not allowed to view themes.";

    private static final String CANNOT_VIEW_ACTIVE = "Sorry, you are not allowed to view the active theme.";

    @Inject
    SyntheticTheme theme;

    @Inject
    RestUrls urls;

    /** Right after the settings route, where WordPress registers them. */
    @Override
    public int order() {
        return 650;
    }

    @Override
    public void register(RouteRegistry routes) {
        // the argument's schema in the order WordPress prints it
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("description", "Limit result set to themes assigned one or more statuses.");
        schema.put("type", "array");
        ObjectNode items = schema.putObject("items");
        items.putArray("enum").add("active").add("inactive");
        items.put("type", "string");
        Arg status = new Arg("status", schema, false, Optional.empty(), Arg.Phase.VALIDATE, Optional.empty());
        routes.route("wp/v2", "/themes")
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .arg(status)
                        .permission(this::mayList)
                        .handler(this::list)
                        .build())
                .register();
        routes.route("wp/v2", ITEM)
                .arg(TypesController.urlArg(
                        "stylesheet", "The theme's stylesheet. This uniquely identifies the theme."))
                .schema(() -> SCHEMA)
                .endpoint(Endpoint.of("GET")
                        .permission(this::mayRead)
                        .handler(this::item)
                        .build())
                .register();
    }

    private PermissionResult mayList(RestRequest request) {
        if (theme.managesThemes(request)) {
            return PermissionResult.allowed();
        }
        return onlyActive(request) ? activeOnly(request) : denied(request, "rest_cannot_view_themes", CANNOT_VIEW);
    }

    private PermissionResult mayRead(RestRequest request) {
        if (theme.managesThemes(request)) {
            return PermissionResult.allowed();
        }
        return stylesheet(request).equals(SyntheticTheme.STYLESHEET)
                ? activeOnly(request)
                : denied(request, "rest_cannot_view_themes", CANNOT_VIEW);
    }

    private PermissionResult activeOnly(RestRequest request) {
        return theme.mayViewActive(request)
                ? PermissionResult.allowed()
                : denied(request, "rest_cannot_view_active_theme", CANNOT_VIEW_ACTIVE);
    }

    private static PermissionResult denied(RestRequest request, String code, String message) {
        return PermissionResult.denied(RestError.of(code, message, request.authCode()));
    }

    /** Whether the sanitized {@code status} is exactly the one-element list {@code ["active"]}. */
    private static boolean onlyActive(RestRequest request) {
        JsonNode status = request.param("status").orElse(null);
        return status != null
                && status.isArray()
                && status.size() == 1
                && status.get(0).isTextual()
                && status.get(0).textValue().equals("active");
    }

    /** Whether the list asked for holds the active theme: no {@code status}, or one naming {@code active}. */
    private static boolean listsActive(RestRequest request) {
        JsonNode status = request.param("status").orElse(null);
        if (status == null || !status.isArray()) {
            return true;
        }
        for (JsonNode value : status) {
            if (value.isTextual() && value.textValue().equals("active")) {
                return true;
            }
        }
        return false;
    }

    /**
     * The requested stylesheet as the route matched it: the request path was decoded once already, and WordPress
     * decodes no further (a doubly encoded name is not found).
     */
    private static String stylesheet(RestRequest request) {
        return TypesController.text(request.param("stylesheet"));
    }

    private RestResponse list(RestRequest request) {
        ArrayNode data = RestJson.NODES.arrayNode();
        if (listsActive(request)) {
            data.add(CollectionItems.inline(prepare(), request));
        }
        return RestResponse.pagedAt(data, data.size(), 1, 1, request);
    }

    private RestResponse item(RestRequest request) {
        if (!stylesheet(request).equals(SyntheticTheme.STYLESHEET)) {
            throw new RestException(RestError.of("rest_theme_not_found", "Theme not found.", 404));
        }
        return prepare();
    }

    private RestResponse prepare() {
        return RestResponse.ok(theme.record(urls)).withLinks(theme.links(urls));
    }
}
