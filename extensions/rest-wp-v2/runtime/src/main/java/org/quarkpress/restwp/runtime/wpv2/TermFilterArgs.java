package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.TaxonomyDefinition;
import org.quarkpress.posts.TaxClause;
import org.quarkpress.posts.TaxQuery;
import org.quarkpress.restwp.Arg;
import org.quarkpress.restwp.RestError;
import org.quarkpress.restwp.RestException;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.runtime.ArgChecks;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.restwp.runtime.SchemaValidator;

/**
 * The term filters of a post collection, as WordPress declares and reads them: {@code tax_relation} when the type has
 * a REST taxonomy, then per taxonomy (in registration order) {@code <base>} and {@code <base>_exclude}, each either a
 * term id list or a taxonomy query object ({@code terms}, {@code include_children} on hierarchical taxonomies only,
 * {@code operator} on the include form only). They map to one term query whose relation is {@code tax_relation} (AND
 * when absent): an include form with terms asks for posts with any of them (all of them with operator {@code AND}),
 * an exclude form for posts with none, children included only when the object form says so; an empty list or an
 * object without terms adds nothing. The {@code format} filter is one more clause, last: a group of alternatives over
 * the post format taxonomy. A term id list holds at most {@value ListArgs#MAX_ITEMS} ids; a longer one is refused as
 * a list over its bound.
 */
final class TermFilterArgs {

    static final String TAX_RELATION = "tax_relation";

    private static final String POST_FORMAT = "post_format";
    private static final String EXCLUDE = "_exclude";

    private TermFilterArgs() {}

    /** The arguments for the type's REST taxonomies; none when it has none. */
    static List<Arg> args(List<TaxonomyDefinition> taxonomies) {
        if (taxonomies.isEmpty()) {
            return List.of();
        }
        List<Arg> args = new ArrayList<>();
        ObjectNode relation = RestJson.NODES.objectNode();
        relation.put("description", "Limit result set based on relationship between multiple taxonomies.");
        relation.put("type", "string");
        relation.putArray("enum").add("AND").add("OR");
        args.add(arg(TAX_RELATION, relation));
        for (TaxonomyDefinition taxonomy : taxonomies) {
            String base = PostRoutes.base(taxonomy);
            args.add(arg(
                    base,
                    filter(
                            "Limit result set to items with specific terms assigned in the " + base + " taxonomy.",
                            taxonomy.hierarchical(),
                            true)));
            args.add(arg(
                    base + EXCLUDE,
                    filter(
                            "Limit result set to items except those with specific terms assigned in the " + base
                                    + " taxonomy.",
                            taxonomy.hierarchical(),
                            false)));
        }
        return List.copyOf(args);
    }

    private static ObjectNode filter(String description, boolean hierarchical, boolean include) {
        ObjectNode filter = RestJson.NODES.objectNode();
        filter.put("description", description);
        filter.putArray("type").add("object").add("array");
        ArrayNode oneOf = filter.putArray("oneOf");
        ObjectNode list = oneOf.addObject();
        list.put("title", "Term ID List");
        list.put("description", "Match terms with the listed IDs.");
        list.put("type", "array");
        list.putObject("items").put("type", "integer");
        ObjectNode query = oneOf.addObject();
        query.put("title", "Term ID Taxonomy Query");
        query.put("description", "Perform an advanced term query.");
        query.put("type", "object");
        ObjectNode properties = query.putObject("properties");
        ObjectNode terms = properties.putObject("terms");
        terms.put("description", "Term IDs.");
        terms.put("type", "array");
        terms.putObject("items").put("type", "integer");
        terms.putArray("default");
        if (hierarchical) {
            ObjectNode children = properties.putObject("include_children");
            children.put("description", "Whether to include child terms in the terms limiting the result set.");
            children.put("type", "boolean");
            children.put("default", false);
        }
        if (include) {
            ObjectNode operator = properties.putObject("operator");
            operator.put("description", "Whether items must be assigned all or any of the specified terms.");
            operator.put("type", "string");
            operator.putArray("enum").add("AND").add("OR");
            operator.put("default", "OR");
        }
        query.put("additionalProperties", false);
        return filter;
    }

    /** A query argument without a default, checked by its schema while the arguments are sanitized. */
    private static Arg arg(String name, ObjectNode schema) {
        return new Arg(name, schema, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    /**
     * The term query of the request's term filters and of {@code formats} (the {@code format} argument's slugs; empty
     * when the type has no formats); empty when they add no clause.
     *
     * @throws RestException a term id list over {@value ListArgs#MAX_ITEMS} ids, refused as a list over its bound
     */
    static Optional<TaxQuery> query(RestRequest request, List<TaxonomyDefinition> taxonomies, List<String> formats) {
        List<TaxClause> clauses = new ArrayList<>();
        for (TaxonomyDefinition taxonomy : taxonomies) {
            String base = PostRoutes.base(taxonomy);
            request.param(base)
                    .flatMap(value -> clause(base, taxonomy, value, true))
                    .ifPresent(clauses::add);
            request.param(base + EXCLUDE)
                    .flatMap(value -> clause(base + EXCLUDE, taxonomy, value, false))
                    .ifPresent(clauses::add);
        }
        if (!formats.isEmpty()) {
            List<TaxClause> alternatives = new ArrayList<>();
            List<String> slugs = new ArrayList<>();
            for (String format : formats) {
                if (format.equals("standard")) {
                    alternatives.add(new TaxClause.NotExists(POST_FORMAT));
                } else {
                    slugs.add("post-format-" + format);
                }
            }
            if (!slugs.isEmpty()) {
                alternatives.add(new TaxClause.Slugs(POST_FORMAT, slugs));
            }
            clauses.add(new TaxClause.Group(TaxClause.Relation.OR, alternatives));
        }
        if (clauses.isEmpty()) {
            return Optional.empty();
        }
        boolean or = ListArgs.scalar(request.param(TAX_RELATION).orElse(null)).equals("OR");
        return Optional.of(new TaxQuery(or ? TaxClause.Relation.OR : TaxClause.Relation.AND, clauses));
    }

    /**
     * The first term filter of the request whose object form lists terms and includes children, in the arguments'
     * order: the one an expansion past the taxonomy module's cap is reported against.
     */
    static Optional<String> expandingParam(RestRequest request, List<TaxonomyDefinition> taxonomies) {
        for (TaxonomyDefinition taxonomy : taxonomies) {
            if (!taxonomy.hierarchical()) {
                continue;
            }
            String base = PostRoutes.base(taxonomy);
            for (String name : List.of(base, base + EXCLUDE)) {
                Optional<JsonNode> value = request.param(name);
                if (value.isPresent()
                        && isQuery(value.get())
                        && PhpValues.toBoolean(value.get().get("include_children"))
                        && hasTerms(value.get())) {
                    return Optional.of(name);
                }
            }
        }
        return Optional.empty();
    }

    /** Whether the query object lists a term (one that lists none adds no clause, so it expands nothing). */
    private static boolean hasTerms(JsonNode query) {
        JsonNode terms = query.get("terms");
        return terms != null && !terms.isNull() && !items(terms).isEmpty();
    }

    private static Optional<TaxClause> clause(
            String name, TaxonomyDefinition taxonomy, JsonNode value, boolean include) {
        List<JsonNode> items;
        boolean children = false;
        boolean all = false;
        if (isQuery(value)) {
            JsonNode terms = value.get("terms");
            items = terms == null || terms.isNull() ? List.of() : items(terms);
            children = taxonomy.hierarchical() && PhpValues.toBoolean(value.get("include_children"));
            all = include && ListArgs.scalar(value.get("operator")).equals("AND");
        } else {
            items = items(value);
        }
        if (items.isEmpty()) {
            return Optional.empty();
        }
        requireBounded(name, items);
        List<Long> ids = new ArrayList<>(items.size());
        for (JsonNode item : items) {
            ids.add(PhpValues.toLong(item));
        }
        TaxClause.Operator operator =
                include ? (all ? TaxClause.Operator.AND : TaxClause.Operator.IN) : TaxClause.Operator.NOT_IN;
        return Optional.of(new TaxClause.Terms(taxonomy.key(), ids, children, operator));
    }

    /** Whether the value is the taxonomy query form: an object whose keys are not all list indexes. */
    private static boolean isQuery(JsonNode value) {
        return value instanceof ObjectNode object && !object.isEmpty() && !PhpValues.hasIntegerKeys(object);
    }

    /** A list's items in order, or a text's pieces split on commas and whitespace. */
    private static List<JsonNode> items(JsonNode value) {
        if (PhpValues.isPhpArray(value)) {
            return PhpValues.values(value);
        }
        List<JsonNode> items = new ArrayList<>();
        for (String piece : PhpValues.parseList(PhpValues.toPhpString(value))) {
            items.add(RestJson.NODES.textNode(piece));
            if (items.size() > ListArgs.MAX_ITEMS) {
                break;
            }
        }
        return items;
    }

    private static void requireBounded(String name, List<JsonNode> items) {
        if (items.size() <= ListArgs.MAX_ITEMS) {
            return;
        }
        ObjectNode bound = RestJson.NODES.objectNode();
        bound.put("type", "array");
        bound.put("maxItems", ListArgs.MAX_ITEMS);
        ArrayNode list = RestJson.NODES.arrayNode();
        items.forEach(list::add);
        RestError tooMany = SchemaValidator.validate(list, bound, name).orElseThrow();
        throw new RestException(ArgChecks.invalidParams(Map.of(name, tooMany)));
    }
}
