package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.ServedRequest;
import org.quarkpress.restwp.runtime.TextFields;

/**
 * The page of a collection a request asks for, and WordPress's common collection parameters.
 *
 * @param page the requested page, at least 1
 * @param perPage the items per page, at least 1
 * @param offset the number of items skipped before the page
 */
public record Paging(int page, int perPage, int offset) {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    public Paging {
        if (page < 1 || perPage < 1) {
            throw new IllegalArgumentException("page and per_page must be at least 1: " + page + ", " + perPage);
        }
    }

    /**
     * page and per_page as sanitized (1 and 10 when absent). offset is the offset param made non-negative (absint, as
     * WordPress's term and user queries take it) when the matched endpoint declares an {@code offset} argument and the
     * value is not 0; otherwise (page - 1) * perPage. An undeclared {@code offset} in the request is ignored, as
     * WordPress's collections read it only when registered. Values beyond the range of an {@code int} are held at its
     * ends.
     *
     * @param request a request the REST server dispatched (see {@link RestResponse#paged}); any other request is read
     *     as one whose endpoint declares no {@code offset}
     */
    public static Paging of(RestRequest request) {
        int page =
                Math.max(1, clamp(request.param("page").map(PhpValues::toLong).orElse(1L)));
        int perPage = Math.max(
                1, clamp(request.param("per_page").map(PhpValues::toLong).orElse(10L)));
        long offset = 0;
        if (request instanceof ServedRequest served && served.declaresArg("offset")) {
            long given = request.param("offset").map(PhpValues::toLong).orElse(0L);
            offset = given == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(given);
        }
        if (offset == 0) {
            offset = ((long) page - 1) * perPage;
        }
        return new Paging(page, perPage, clamp(offset));
    }

    /**
     * WordPress's collection params: context (enum from the item schema's contexts, descending; default view;
     * VALIDATE; sanitized as a key), page (integer &ge; 1, default 1), per_page (1&ndash;100, default 10), search
     * (string, text-field sanitized); offset ({@link #offsetArg()}) last only when {@code withOffset}. Each schema
     * holds its keywords in WordPress's order. WordPress's own collections declare offset later (tags and users put
     * it after {@code exclude} and {@code include}): their controllers ask for {@code withOffset} false and add
     * {@link #offsetArg()} where WordPress has it.
     */
    public static List<Arg> collectionArgs(ObjectNode itemSchema, boolean withOffset) {
        List<Arg> args = new ArrayList<>();
        args.add(contextArg(itemSchema));
        ObjectNode page = NODES.objectNode();
        page.put("description", "Current page of the collection.");
        page.put("type", "integer");
        page.put("default", 1);
        page.put("minimum", 1);
        args.add(arg("page", page, Paging::absint));
        ObjectNode perPage = NODES.objectNode();
        perPage.put("description", "Maximum number of items to be returned in result set.");
        perPage.put("type", "integer");
        perPage.put("default", 10);
        perPage.put("minimum", 1);
        perPage.put("maximum", 100);
        args.add(arg("per_page", perPage, Paging::absint));
        ObjectNode search = NODES.objectNode();
        search.put("description", "Limit results to those matching a string.");
        search.put("type", "string");
        args.add(arg("search", search, value -> TextNode.valueOf(TextFields.textField(scalarText(value)))));
        if (withOffset) {
            args.add(offsetArg());
        }
        return List.copyOf(args);
    }

    /**
     * WordPress's {@code context} param: a string whose enum lists the contexts the item schema's properties name
     * (each once, descending), default {@code view}, checked with the other arguments and sanitized as a key.
     */
    public static Arg contextArg(ObjectNode itemSchema) {
        ObjectNode context = NODES.objectNode();
        context.put("description", "Scope under which the request is made; determines fields present in response.");
        context.put("type", "string");
        List<String> contexts = contexts(itemSchema);
        if (!contexts.isEmpty()) {
            ArrayNode values = context.putArray("enum");
            contexts.forEach(values::add);
        }
        context.put("default", "view");
        return arg("context", context, Paging::sanitizeKey);
    }

    /**
     * WordPress's {@code offset} collection param: an integer, no default, checked and sanitized by its schema while
     * the arguments are sanitized. {@link #of} reads it only from an endpoint that declares it.
     */
    public static Arg offsetArg() {
        ObjectNode offset = NODES.objectNode();
        offset.put("description", "Offset the result set by a specific number of items.");
        offset.put("type", "integer");
        return new Arg("offset", offset, false, Optional.empty(), Arg.Phase.SANITIZE, Optional.empty());
    }

    /** The page pagination headers report: ceil(offset / perPage) + 1. */
    public static int currentPage(int offset, int perPage) {
        return Math.ceilDiv(offset, perPage) + 1;
    }

    private static Arg arg(String name, ObjectNode schema, UnaryOperator<JsonNode> sanitize) {
        return new Arg(
                name,
                schema,
                false,
                Optional.ofNullable(schema.get("default")).map(JsonNode::deepCopy),
                Arg.Phase.VALIDATE,
                Optional.of(sanitize));
    }

    /** The contexts named by the schema's properties, each once, in descending order. */
    private static List<String> contexts(ObjectNode itemSchema) {
        TreeSet<String> contexts = new TreeSet<>();
        JsonNode properties = itemSchema == null ? null : itemSchema.get("properties");
        if (properties == null || !properties.isObject()) {
            return List.of();
        }
        for (Iterator<JsonNode> it = properties.elements(); it.hasNext(); ) {
            JsonNode listed = it.next().get("context");
            if (listed != null && listed.isArray()) {
                listed.forEach(value -> contexts.add(value.asText()));
            }
        }
        return List.copyOf(contexts.descendingSet());
    }

    /** WordPress's absint: the absolute value of PHP's integer conversion. */
    private static JsonNode absint(JsonNode value) {
        long number = PhpValues.toLong(value);
        return LongNode.valueOf(number == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(number));
    }

    /** WordPress's sanitize_key: a scalar lower-cased (ASCII), keeping only a-z, 0-9, _ and -; anything else "". */
    private static JsonNode sanitizeKey(JsonNode value) {
        String text = scalarText(value);
        StringBuilder key = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                key.append(c);
            }
        }
        return TextNode.valueOf(key.toString());
    }

    private static String scalarText(JsonNode value) {
        return value == null || value.isContainerNode() ? "" : PhpValues.toPhpString(value);
    }

    private static int clamp(long value) {
        return (int) Math.max(Integer.MIN_VALUE, Math.min(Integer.MAX_VALUE, value));
    }
}
