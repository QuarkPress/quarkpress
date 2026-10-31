package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.SiteContext;
import org.quarkpress.persistence.Options;
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
import org.quarkpress.restwp.SettingDefinition;
import org.quarkpress.restwp.SettingsRegistry;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaSanitizer;
import org.quarkpress.restwp.runtime.SchemaValidator;
import org.quarkpress.restwp.runtime.ServedRequest;
import org.quarkpress.security.Capabilities;

/**
 * WordPress's {@code /wp/v2/settings} for the request's site: the registered settings shown in REST, read and written
 * by a caller with {@code manage_options} on the site only. A read answers each setting's option, or its default when
 * the option does not exist, as long as it fits the setting's schema (else null), converted to the setting's type. A
 * write takes the settings from every parameter source, refuses the whole request when one fails its schema, then
 * writes each sent setting in registration order through WordPress's option sanitizing (a refused value keeps the
 * stored one); null deletes the option, unless the option (PHP's false when it does not exist) fails the schema (500,
 * the settings before it written). A single site's {@code url} is the site's own address, read from the site record;
 * a write of it is checked like any other and then ignored. No other option is reachable.
 */
@ApplicationScoped
public class SettingsController implements RestRouteProvider {

    private static final String NAMESPACE = "wp/v2";

    /** The keywords a setting's property shows before its default; the others follow it. */
    private static final Set<String> LEADING = Set.of("type", "title", "description", "default");

    @Inject
    SettingsRegistry registry;

    @Inject
    SiteContext site;

    @Inject
    Options options;

    @Inject
    Capabilities capabilities;

    @Inject
    RestUrls urls;

    @Override
    public int order() {
        return 600;
    }

    @Override
    public void register(RouteRegistry routes) {
        List<SettingDefinition> settings = new ArrayList<>();
        for (SettingDefinition setting : registry.all()) {
            if (setting.showInRest()) {
                settings.add(setting);
            }
        }
        List<SettingDefinition> shown = List.copyOf(settings);
        ObjectNode schema = itemSchema(shown);
        List<Arg> args = new ArrayList<>();
        for (SettingDefinition setting : shown) {
            args.add(arg(setting));
        }
        routes.route(NAMESPACE, "/settings")
                .schema(() -> schema)
                .endpoint(Endpoint.of("GET")
                        .permission(this::mayManage)
                        .handler(request -> RestResponse.ok(read(shown)))
                        .build())
                .endpoint(Endpoint.of("POST", "PUT", "PATCH")
                        .args(args)
                        .permission(this::mayManage)
                        .handler(request -> update(shown, request))
                        .build())
                .register();
    }

    private PermissionResult mayManage(RestRequest request) {
        return capabilities.can(request.identity(), "manage_options", Optional.empty())
                ? PermissionResult.allowed()
                : PermissionResult.forbidden(request);
    }

    /** Every setting's value, in registration order. */
    private ObjectNode read(List<SettingDefinition> settings) {
        ObjectNode data = RestJson.NODES.objectNode();
        for (SettingDefinition setting : settings) {
            JsonNode value = stored(setting);
            ObjectNode schema = valueSchema(setting);
            data.set(
                    setting.restName(),
                    SchemaValidator.validate(value, schema, setting.restName()).isPresent()
                            ? RestJson.NODES.nullNode()
                            : SchemaSanitizer.sanitize(value, schema, setting.restName()));
        }
        return data;
    }

    /**
     * The setting's option as WordPress reads it; without the option, its default, else PHP's false (which only a
     * boolean schema accepts).
     */
    private JsonNode stored(SettingDefinition setting) {
        if (BuiltInSettings.isSiteAddress(setting)) {
            return TextNode.valueOf(siteAddress());
        }
        return site.options()
                .get(setting.optionName())
                .orElseGet(() -> setting.defaultValue().orElse(BooleanNode.FALSE));
    }

    /** The site's own address without trailing slashes, as the index's {@code url} gives it. */
    private String siteAddress() {
        String home = urls.home();
        int end = home.length();
        while (end > 0 && home.charAt(end - 1) == '/') {
            end--;
        }
        return home.substring(0, end);
    }

    private RestResponse update(List<SettingDefinition> settings, RestRequest request) {
        for (SettingDefinition setting : settings) {
            String name = setting.restName();
            boolean sent = request instanceof ServedRequest served ? served.sentParam(name) : request.hasParam(name);
            if (!sent || BuiltInSettings.isSiteAddress(setting)) {
                // the site's address is the site record's: a valid write of it is accepted and changes nothing
                continue;
            }
            Optional<JsonNode> value = request.param(name);
            if (value.isEmpty()) {
                // the option itself, without the default: an option that does not exist is PHP's false
                JsonNode raw = site.options().get(setting.optionName()).orElse(BooleanNode.FALSE);
                if (SchemaValidator.validate(raw, valueSchema(setting), name).isPresent()) {
                    throw new RestException(RestError.of(
                            "rest_invalid_stored_value",
                            "The " + name + " property has an invalid stored value, and cannot be updated to null.",
                            500));
                }
                options.delete(setting.optionName());
                continue;
            }
            if (OptionSanitizers.sanitize(setting.optionName(), value.get())
                    instanceof OptionSanitizers.Sanitized.Kept kept) {
                options.set(setting.optionName(), OptionSanitizers.stored(kept.value()));
            }
        }
        return RestResponse.ok(read(settings));
    }

    /** The update argument of a setting: its title, description, type and other keywords, never a default. */
    private static Arg arg(SettingDefinition setting) {
        ObjectNode definition = setting.schema();
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("title", text(definition, "title"));
        schema.put("description", text(definition, "description"));
        schema.put("type", setting.type());
        copyRest(definition, schema);
        ObjectNode checked = schema.deepCopy();
        String name = setting.restName();
        // a null is kept for the handler, which resets the setting
        return new Arg(
                name,
                schema,
                false,
                Optional.empty(),
                Arg.Phase.VALIDATE,
                Optional.of(value -> value.isNull() ? value : SchemaSanitizer.sanitize(value, checked, name)));
    }

    /** The route's item schema: one property per setting, with its type, title, description and default first. */
    private static ObjectNode itemSchema(List<SettingDefinition> settings) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("$schema", "http://json-schema.org/draft-04/schema#");
        schema.put("title", "settings");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        for (SettingDefinition setting : settings) {
            ObjectNode definition = setting.schema();
            ObjectNode property = properties.putObject(setting.restName());
            property.put("type", setting.type());
            property.put("title", text(definition, "title"));
            property.put("description", text(definition, "description"));
            property.set("default", setting.defaultValue().orElse(RestJson.NODES.nullNode()));
            copyRest(definition, property);
        }
        return schema;
    }

    /** The schema a setting's value is checked and converted with. */
    private static ObjectNode valueSchema(SettingDefinition setting) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("type", setting.type());
        copyRest(setting.schema(), schema);
        return schema;
    }

    private static void copyRest(ObjectNode from, ObjectNode to) {
        for (Iterator<Map.Entry<String, JsonNode>> it = from.fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> keyword = it.next();
            if (!LEADING.contains(keyword.getKey())) {
                to.set(keyword.getKey(), keyword.getValue().deepCopy());
            }
        }
    }

    private static String text(ObjectNode schema, String keyword) {
        JsonNode value = schema.get(keyword);
        return value != null && value.isTextual() ? value.textValue() : "";
    }
}
