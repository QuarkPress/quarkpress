package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.TextFields;

/**
 * The item schema of a taxonomy's terms and the arguments of its term routes, as WordPress's terms controller declares
 * them: properties in its order with its descriptions, contexts and flags ({@code parent} only for a hierarchical
 * taxonomy), the collection's query arguments ({@code offset} only for a flat taxonomy, {@code parent} only for a
 * hierarchical one) and the write arguments taken from the schema's writable properties.
 */
final class TermSchema {

    static final String ID = "Unique identifier for the term.";

    private static final List<String> ALL = List.of("view", "embed", "edit");
    private static final List<String> VIEW_EDIT = List.of("view", "edit");

    private TermSchema() {}

    /** The schema's title: {@code category}, {@code tag} for {@code post_tag}, else the taxonomy's key. */
    static String title(TaxonomyDefinition taxonomy) {
        return taxonomy.key().equals("post_tag") ? "tag" : taxonomy.key();
    }

    /** The item schema of the taxonomy's terms. */
    static ObjectNode of(TaxonomyDefinition taxonomy) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("$schema", "http://json-schema.org/draft-04/schema#");
        schema.put("title", title(taxonomy));
        schema.put("type", "object");
        ObjectNode properties = schema.putObject("properties");
        property(properties, "id", ID, "integer", ALL).put("readonly", true);
        property(properties, "count", "Number of published posts for the term.", "integer", VIEW_EDIT)
                .put("readonly", true);
        property(properties, "description", "HTML description of the term.", "string", VIEW_EDIT);
        ObjectNode link = RestJson.NODES.objectNode();
        link.put("description", "URL of the term.");
        link.put("type", "string");
        link.put("format", "uri");
        contexts(link, ALL);
        link.put("readonly", true);
        properties.set("link", link);
        property(properties, "name", "HTML title for the term.", "string", ALL).put("required", true);
        property(properties, "slug", "An alphanumeric identifier for the term unique to its type.", "string", ALL);
        ObjectNode type = RestJson.NODES.objectNode();
        type.put("description", "Type attribution for the term.");
        type.put("type", "string");
        type.putArray("enum").add(taxonomy.key());
        contexts(type, ALL);
        type.put("readonly", true);
        properties.set("taxonomy", type);
        if (taxonomy.hierarchical()) {
            property(properties, "parent", "The parent term ID.", "integer", VIEW_EDIT);
        }
        property(properties, "meta", "Meta fields.", "object", VIEW_EDIT).putArray("properties");
        return schema;
    }

    /**
     * The collection's arguments: context, page, per_page, search, exclude, include, offset (flat taxonomies), order,
     * orderby, hide_empty, parent (hierarchical taxonomies), post and slug. Those after search are checked while the
     * arguments are sanitized, by their schemas.
     */
    static List<Arg> collectionArgs(TaxonomyDefinition taxonomy, ObjectNode schema) {
        List<Arg> args = new ArrayList<>(Paging.collectionArgs(schema, false));
        args.add(idList("exclude", "Ensure result set excludes specific IDs."));
        args.add(idList("include", "Limit result set to specific IDs."));
        if (!taxonomy.hierarchical()) {
            args.add(Paging.offsetArg());
        }
        ObjectNode order = RestJson.NODES.objectNode();
        order.put("description", "Order sort attribute ascending or descending.");
        order.put("type", "string");
        order.put("default", "asc");
        order.putArray("enum").add("asc").add("desc");
        args.add(query("order", order));
        ObjectNode orderby = RestJson.NODES.objectNode();
        orderby.put("description", "Sort collection by term attribute.");
        orderby.put("type", "string");
        orderby.put("default", "name");
        ArrayNode keys = orderby.putArray("enum");
        for (String key :
                List.of("id", "include", "name", "slug", "include_slugs", "term_group", "description", "count")) {
            keys.add(key);
        }
        args.add(query("orderby", orderby));
        ObjectNode hideEmpty = RestJson.NODES.objectNode();
        hideEmpty.put("description", "Whether to hide terms not assigned to any posts.");
        hideEmpty.put("type", "boolean");
        hideEmpty.put("default", false);
        args.add(query("hide_empty", hideEmpty));
        if (taxonomy.hierarchical()) {
            ObjectNode parent = RestJson.NODES.objectNode();
            parent.put("description", "Limit result set to terms assigned to a specific parent.");
            parent.put("type", "integer");
            args.add(query("parent", parent));
        }
        ObjectNode post = RestJson.NODES.objectNode();
        post.put("description", "Limit result set to terms assigned to a specific post.");
        post.put("type", "integer");
        // printed as WordPress declares it; the argument itself has no default
        post.set("default", NullNode.getInstance());
        args.add(new Arg("post", post, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty()));
        ObjectNode slug = RestJson.NODES.objectNode();
        slug.put("description", "Limit result set to terms with one or more specific slugs.");
        slug.put("type", "array");
        slug.putObject("items").put("type", "string");
        args.add(query("slug", slug));
        return List.copyOf(args);
    }

    /**
     * The write arguments, from the schema's writable properties in its order: description, name (required when
     * {@code create}; sanitized as a text field by its own sanitizer, so only the value that wins is type-checked),
     * slug (sanitized as a slug by {@code slug}), parent (hierarchical taxonomies) and meta. Each is checked with the
     * other arguments, before any is sanitized.
     */
    static List<Arg> writeArgs(TaxonomyDefinition taxonomy, boolean create, UnaryOperator<JsonNode> slug) {
        List<Arg> args = new ArrayList<>();
        args.add(write("description", "HTML description of the term.", "string", false, null));
        args.add(write("name", "HTML title for the term.", "string", create, TermSchema::textField));
        args.add(write("slug", "An alphanumeric identifier for the term unique to its type.", "string", false, slug));
        if (taxonomy.hierarchical()) {
            args.add(write("parent", "The parent term ID.", "integer", false, null));
        }
        ObjectNode meta = RestJson.NODES.objectNode();
        meta.put("description", "Meta fields.");
        meta.put("type", "object");
        meta.putArray("properties");
        // WordPress's meta field only checks that the value is an array (a JSON object or list) and sanitizes nothing;
        // with no term meta shown in REST, the keys it holds are ignored
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
        id.put("description", ID);
        id.put("type", "integer");
        return new Arg("id", id, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    /** The delete endpoint's {@code force}: a boolean, default false. */
    static Arg forceArg() {
        ObjectNode force = RestJson.NODES.objectNode();
        force.put("type", "boolean");
        force.put("default", false);
        force.put("description", "Required to be true, as terms do not support trashing.");
        return new Arg("force", force, false, Optional.of(BooleanNode.FALSE), Arg.Phase.SANITIZE, Optional.empty());
    }

    /**
     * WordPress's text-field sanitizing of a request value: a scalar as PHP prints it (null as ""), anything else "".
     */
    static JsonNode textField(JsonNode value) {
        return TextNode.valueOf(TextFields.textField(scalar(value)));
    }

    /** A scalar request value as PHP prints it (null as ""); a list or an object as "". */
    static String scalar(JsonNode value) {
        return value == null || value.isContainerNode() ? "" : PhpValues.toPhpString(value);
    }

    private static ObjectNode property(
            ObjectNode properties, String name, String description, String type, List<String> contexts) {
        ObjectNode property = properties.putObject(name);
        property.put("description", description);
        property.put("type", type);
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

    private static Arg query(String name, ObjectNode schema) {
        return new Arg(
                name,
                schema,
                false,
                Optional.ofNullable(schema.get("default")).map(JsonNode::deepCopy),
                Arg.Phase.SANITIZE,
                Optional.empty());
    }

    private static Arg write(
            String name, String description, String type, boolean required, UnaryOperator<JsonNode> sanitize) {
        ObjectNode schema = RestJson.NODES.objectNode();
        schema.put("description", description);
        schema.put("type", type);
        return new Arg(name, schema, required, Optional.empty(), Arg.Phase.VALIDATE, Optional.ofNullable(sanitize));
    }
}
