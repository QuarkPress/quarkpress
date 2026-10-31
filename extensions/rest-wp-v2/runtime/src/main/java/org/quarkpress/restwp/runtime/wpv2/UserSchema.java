package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * The item schema of users and the arguments of the users routes, as WordPress's users controller declares them:
 * properties in its order with its descriptions, contexts and flags (the write-only {@code password} included, the one
 * registered meta key {@code persisted_preferences} under {@code meta}), the collection's query arguments, the write
 * arguments taken from the schema's writable properties, and the delete arguments.
 */
final class UserSchema {

    /** The schema properties a write may name, in the schema's order. */
    static final List<String> WRITE_FIELDS = List.of(
            "username",
            "name",
            "first_name",
            "last_name",
            "email",
            "url",
            "description",
            "locale",
            "nickname",
            "slug",
            "roles",
            "password",
            "meta");

    /** The search columns the collection's {@code search_columns} may name. */
    static final List<String> SEARCH_COLUMNS = List.of("email", "name", "id", "username", "slug");

    private static final List<String> ALL = List.of("embed", "view", "edit");
    private static final List<String> EDIT = List.of("edit");
    private static final List<String> VIEW_EDIT = List.of("view", "edit");

    private UserSchema() {}

    /** The item schema of users. */
    static ObjectNode of() {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("$schema", "http://json-schema.org/draft-04/schema#");
        schema.put("title", "user");
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        property(properties, "id", "Unique identifier for the user.", "integer", ALL)
                .put("readonly", true);
        property(properties, "username", "Login name for the user.", "string", EDIT)
                .put("required", true);
        property(properties, "name", "Display name for the user.", "string", ALL);
        property(properties, "first_name", "First name for the user.", "string", EDIT);
        property(properties, "last_name", "Last name for the user.", "string", EDIT);
        formatted(properties, "email", "The email address for the user.", "email", EDIT)
                .put("required", true);
        formatted(properties, "url", "URL of the user.", "uri", ALL);
        property(properties, "description", "Description of the user.", "string", ALL);
        formatted(properties, "link", "Author URL of the user.", "uri", ALL).put("readonly", true);
        ObjectNode locale = properties.putObject("locale");
        locale.put("description", "Locale for the user.");
        locale.put("type", "string");
        locale.putArray("enum").add("").add("en_US");
        contexts(locale, EDIT);
        property(properties, "nickname", "The nickname for the user.", "string", EDIT);
        property(properties, "slug", "An alphanumeric identifier for the user.", "string", ALL);
        formatted(properties, "registered_date", "Registration date for the user.", "date-time", EDIT)
                .put("readonly", true);
        ObjectNode roles = properties.putObject("roles");
        roles.put("description", "Roles assigned to the user.");
        roles.put("type", "array");
        roles.putObject("items").put("type", "string");
        contexts(roles, EDIT);
        property(properties, "password", "Password for the user (never included).", "string", List.of())
                .put("required", true);
        property(properties, "capabilities", "All capabilities assigned to the user.", "object", EDIT)
                .put("readonly", true);
        property(properties, "extra_capabilities", "Any extra capabilities assigned to the user.", "object", EDIT)
                .put("readonly", true);
        ObjectNode avatars = property(properties, "avatar_urls", "Avatar URLs for the user.", "object", ALL);
        avatars.put("readonly", true);
        ObjectNode sizes = avatars.putObject("properties");
        for (String size : List.of("24", "48", "96")) {
            formatted(sizes, size, "Avatar URL with image size of " + size + " pixels.", "uri", ALL);
        }
        ObjectNode meta = property(properties, "meta", "Meta fields.", "object", VIEW_EDIT);
        meta.putObject("properties").set("persisted_preferences", preferences());
        return schema;
    }

    /** The schema of the {@code persisted_preferences} meta: an object of any keys, with a {@code _modified} date. */
    static ObjectNode preferences() {
        ObjectNode preferences = RestJson.NODES.objectNode();
        preferences.put("type", "object");
        preferences.put("title", "");
        preferences.put("description", "");
        preferences.putArray("default");
        contexts(preferences, EDIT);
        ObjectNode modified = preferences.putObject("properties").putObject("_modified");
        modified.put("description", "The date and time the preferences were updated.");
        modified.put("type", "string");
        modified.put("format", "date-time");
        modified.put("readonly", false);
        preferences.put("additionalProperties", true);
        return preferences;
    }

    /**
     * The collection's arguments: context, page, per_page, search, exclude, include, offset, order, orderby, slug,
     * roles, capabilities, who, has_published_posts (its items limited to {@code restTypes}, listed as WordPress lists
     * them, by key) and search_columns. Those after search are checked while the arguments are sanitized.
     */
    static List<Arg> collectionArgs(ObjectNode schema, List<String> restTypes) {
        List<Arg> args = new ArrayList<>(Paging.collectionArgs(schema, false));
        args.add(idList("exclude", "Ensure result set excludes specific IDs."));
        args.add(idList("include", "Limit result set to specific IDs."));
        args.add(Paging.offsetArg());
        ObjectNode order = RestJson.NODES.objectNode();
        order.put("default", "asc");
        order.put("description", "Order sort attribute ascending or descending.");
        order.putArray("enum").add("asc").add("desc");
        order.put("type", "string");
        args.add(query("order", order));
        ObjectNode orderby = RestJson.NODES.objectNode();
        orderby.put("default", "name");
        orderby.put("description", "Sort collection by user attribute.");
        ArrayNode keys = orderby.putArray("enum");
        for (String key :
                List.of("id", "include", "name", "registered_date", "slug", "include_slugs", "email", "url")) {
            keys.add(key);
        }
        orderby.put("type", "string");
        args.add(query("orderby", orderby));
        args.add(textList("slug", "Limit result set to users with one or more specific slugs."));
        args.add(textList(
                "roles",
                "Limit result set to users matching at least one specific role provided. Accepts csv list or single"
                        + " role."));
        args.add(textList(
                "capabilities",
                "Limit result set to users matching at least one specific capability provided. Accepts csv list or"
                        + " single capability."));
        ObjectNode who = RestJson.NODES.objectNode();
        who.put("description", "Limit result set to users who are considered authors.");
        who.put("type", "string");
        who.putArray("enum").add("authors");
        args.add(query("who", who));
        ObjectNode published = RestJson.NODES.objectNode();
        published.put("description", "Limit result set to users who have published posts.");
        published.putArray("type").add("boolean").add("array");
        ObjectNode items = published.putObject("items");
        items.put("type", "string");
        ObjectNode types = items.putObject("enum");
        restTypes.forEach(type -> types.put(type, type));
        args.add(query("has_published_posts", published));
        ObjectNode columns = RestJson.NODES.objectNode();
        columns.putArray("default");
        columns.put("description", "Array of column names to be searched.");
        columns.put("type", "array");
        ObjectNode column = columns.putObject("items");
        ArrayNode names = column.putArray("enum");
        SEARCH_COLUMNS.forEach(names::add);
        column.put("type", "string");
        args.add(query("search_columns", columns));
        return List.copyOf(args);
    }

    /**
     * The write arguments, from the schema's writable properties in its order; {@code username}, {@code email} and
     * {@code password} are required when {@code create}. Each is checked with the other arguments, before any is
     * sanitized. {@code meta} is only checked to be an array or object, as WordPress's meta field checks it; its keys
     * are checked when they are written.
     */
    static List<Arg> writeArgs(boolean create) {
        List<Arg> args = new ArrayList<>();
        args.add(write("username", "Login name for the user.", "string", create, null));
        args.add(write("name", "Display name for the user.", "string", false, null));
        args.add(write("first_name", "First name for the user.", "string", false, null));
        args.add(write("last_name", "Last name for the user.", "string", false, null));
        args.add(write("email", "The email address for the user.", "string", create, "email"));
        args.add(write("url", "URL of the user.", "string", false, "uri"));
        args.add(write("description", "Description of the user.", "string", false, null));
        ObjectNode locale = RestJson.NODES.objectNode();
        locale.put("description", "Locale for the user.");
        locale.put("type", "string");
        locale.putArray("enum").add("").add("en_US");
        args.add(new Arg("locale", locale, false, Optional.empty(), Arg.Phase.VALIDATE, Optional.empty()));
        args.add(write("nickname", "The nickname for the user.", "string", false, null));
        args.add(write("slug", "An alphanumeric identifier for the user.", "string", false, null));
        ObjectNode roles = RestJson.NODES.objectNode();
        roles.put("description", "Roles assigned to the user.");
        roles.put("type", "array");
        roles.putObject("items").put("type", "string");
        args.add(new Arg("roles", roles, false, Optional.empty(), Arg.Phase.VALIDATE, Optional.empty()));
        args.add(write("password", "Password for the user (never included).", "string", create, null));
        ObjectNode meta = RestJson.NODES.objectNode();
        meta.put("description", "Meta fields.");
        meta.put("type", "object");
        meta.putObject("properties").set("persisted_preferences", preferences());
        args.add(new Arg(
                "meta",
                meta,
                false,
                Optional.empty(),
                Arg.Phase.VALIDATE,
                Optional.of(UnaryOperator.identity()),
                Optional.of(PhpValues::isPhpArray)));
        return List.copyOf(args);
    }

    /** The item route's {@code id}: an integer, checked while the arguments are sanitized. */
    static Arg idArg() {
        ObjectNode id = RestJson.NODES.objectNode();
        id.put("description", "Unique identifier for the user.");
        id.put("type", "integer");
        return new Arg("id", id, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    /**
     * The delete arguments: {@code force} (a boolean, default false) and the required {@code reassign}, which, as in
     * WordPress, has a sanitizer and no type check: a number is kept, an empty value, false or "false" means no one,
     * and anything else is refused with "Invalid user parameter(s)." while the arguments are sanitized (before the
     * permission check).
     */
    static List<Arg> deleteArgs() {
        ObjectNode force = RestJson.NODES.objectNode();
        force.put("type", "boolean");
        force.put("default", false);
        force.put("description", "Required to be true, as users do not support trashing.");
        ObjectNode reassign = RestJson.NODES.objectNode();
        reassign.put("type", "integer");
        reassign.put("description", "Reassign the deleted user's posts and links to this user ID.");
        return List.of(
                new Arg("force", force, false, Optional.of(BooleanNode.FALSE), Arg.Phase.SANITIZE, Optional.empty()),
                new Arg(
                        "reassign",
                        reassign,
                        true,
                        Optional.empty(),
                        Arg.Phase.SANITIZE,
                        Optional.of(UserSchema::reassign)));
    }

    /** WordPress's reassign check: a number as it is; empty, false or "false" as false; anything else refused. */
    static JsonNode reassign(JsonNode value) {
        if (PhpValues.isNumeric(value)) {
            return value;
        }
        if (PhpValues.isEmpty(value) || (value.isTextual() && value.textValue().equals("false"))) {
            return BooleanNode.FALSE;
        }
        throw new RestException(RestError.of("rest_invalid_param", "Invalid user parameter(s).", 400));
    }

    private static ObjectNode property(
            ObjectNode properties, String name, String description, String type, List<String> contexts) {
        ObjectNode property = properties.putObject(name);
        property.put("description", description);
        property.put("type", type);
        contexts(property, contexts);
        return property;
    }

    private static ObjectNode formatted(
            ObjectNode properties, String name, String description, String format, List<String> contexts) {
        ObjectNode property = properties.putObject(name);
        property.put("description", description);
        property.put("type", "string");
        property.put("format", format);
        contexts(property, contexts);
        return property;
    }

    private static void contexts(ObjectNode property, List<String> contexts) {
        ArrayNode list = property.putArray("context");
        contexts.forEach(list::add);
    }

    private static Arg idList(String name, String description) {
        ObjectNode list = RestJson.NODES.objectNode();
        list.put("description", description);
        list.put("type", "array");
        list.putObject("items").put("type", "integer");
        list.putArray("default");
        return query(name, list);
    }

    private static Arg textList(String name, String description) {
        ObjectNode list = RestJson.NODES.objectNode();
        list.put("description", description);
        list.put("type", "array");
        list.putObject("items").put("type", "string");
        return query(name, list);
    }

    private static Arg query(String name, ObjectNode schema) {
        return new Arg(
                name,
                schema,
                false,
                Optional.ofNullable(schema.get("default")).map(JsonNode::deepCopy),
                Arg.Phase.SANITIZE,
                Optional.empty());
    }

    private static Arg write(String name, String description, String type, boolean required, String format) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("description", description);
        schema.put("type", type);
        if (format != null) {
            schema.put("format", format);
        }
        return new Arg(name, schema, required, Optional.empty(), Arg.Phase.VALIDATE, Optional.empty());
    }
}
