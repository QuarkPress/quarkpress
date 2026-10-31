package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.quarkpress.restwp.runtime.FieldsFilter;
import org.quarkpress.restwp.runtime.ServedRequest;

/**
 * WordPress's {@code _fields} as controllers use it. A controller asks once per request for the {@link Selection} of
 * fields it should compute ({@link #forResponse(RestRequest, ObjectNode)}), tests each field it could build with
 * {@link Selection#includes} (links too: {@code _links}, and {@code _embedded} with {@code _embed}, count as fields),
 * and the REST server then reduces the answer to the requested key tree.
 */
public final class Fields {

    private Fields() {}

    /**
     * Which fields a controller computes: every field, none, or the listed ones (with WordPress's parent and child
     * rule). Built once in work linear in the fields' length; each {@link #includes} check takes work linear in the
     * checked field's length, however many fields were requested.
     */
    public static final class Selection {

        /** What a selection selects. */
        public enum Kind {
            EVERYTHING,
            NOTHING,
            THESE
        }

        private static final Selection EVERYTHING = new Selection(Kind.EVERYTHING, List.of(), new Segment());
        private static final Selection NOTHING = new Selection(Kind.NOTHING, List.of(), new Segment());

        /** A trie of the fields' dot-separated segments; {@code listed} marks the end of a field. */
        private static final class Segment {
            final Map<String, Segment> next = new HashMap<>();
            boolean listed;
        }

        private final Kind kind;
        private final List<String> fields;
        private final Segment root;

        private Selection(Kind kind, List<String> fields, Segment root) {
            this.kind = kind;
            this.fields = fields;
            this.root = root;
        }

        public static Selection everything() {
            return EVERYTHING;
        }

        public static Selection nothing() {
            return NOTHING;
        }

        /** The listed fields; {@link #nothing()} when there are none. */
        public static Selection of(Collection<String> fields) {
            return of(fields, new long[1]);
        }

        static Selection of(Collection<String> fields, long[] work) {
            if (fields.isEmpty()) {
                return NOTHING;
            }
            Segment root = new Segment();
            for (String field : fields) {
                Segment node = root;
                int start = 0;
                while (true) {
                    int dot = field.indexOf('.', start);
                    int end = dot < 0 ? field.length() : dot;
                    work[0] += end - start + 1;
                    node = node.next.computeIfAbsent(field.substring(start, end), segment -> new Segment());
                    if (dot < 0) {
                        break;
                    }
                    start = dot + 1;
                }
                node.listed = true;
            }
            return new Selection(Kind.THESE, List.copyOf(fields), root);
        }

        public Kind kind() {
            return kind;
        }

        /** The listed fields, in their order with repeats (empty unless the kind is {@link Kind#THESE}). */
        public List<String> fields() {
            return fields;
        }

        /**
         * WordPress's {@code rest_is_field_included} against this selection: {@code field} is listed, or a listed
         * field is below it ("a.b" selects "a"), or it is below a listed field ("a" selects "a.b").
         */
        public boolean includes(String field) {
            return includes(field, new long[1]);
        }

        boolean includes(String field, long[] work) {
            if (kind != Kind.THESE) {
                return kind == Kind.EVERYTHING;
            }
            Segment node = root;
            int start = 0;
            while (true) {
                int dot = field.indexOf('.', start);
                int end = dot < 0 ? field.length() : dot;
                work[0] += end - start + 1;
                node = node.next.get(field.substring(start, end));
                if (node == null) {
                    return false;
                }
                if (dot < 0) {
                    // listed itself, or a listed field below it
                    return node.listed || !node.next.isEmpty();
                }
                if (node.listed) {
                    // below a listed field
                    return true;
                }
                start = dot + 1;
            }
        }

        @Override
        public String toString() {
            return kind == Kind.THESE ? fields.toString() : kind.toString();
        }
    }

    /**
     * The request's {@code _fields} (any source), split on commas and whitespace; empty = no filtering. A request the
     * REST server dispatched parses them once.
     */
    public static List<String> of(RestRequest request) {
        if (request instanceof ServedRequest served) {
            return served.requestedFields();
        }
        return request.param("_fields").map(Fields::parse).orElse(List.of());
    }

    /** The request's {@code _fields} as a selection: everything when there are none. */
    public static Selection requested(RestRequest request) {
        List<String> fields = of(request);
        return fields.isEmpty() ? Selection.everything() : Selection.of(fields);
    }

    /**
     * WordPress's parsing of a {@code _fields} value: text (or a number or boolean, as PHP prints it) split on runs of
     * commas and ASCII whitespace, empty pieces dropped; an array's scalar items as they are (not split), other items
     * dropped. Each field is then trimmed of spaces, tabs, line feeds, carriage returns, NUL and vertical tabs; a field
     * trimmed to nothing is kept.
     */
    public static List<String> parse(JsonNode value) {
        return FieldsFilter.parse(value, new long[1]);
    }

    /**
     * WordPress's {@code rest_is_field_included} over a plain list, as WordPress has it: an empty list includes
     * nothing. Controllers use {@link Selection#includes} on {@link #forResponse(RestRequest, ObjectNode)} instead.
     */
    public static boolean includes(List<String> fields, String field) {
        return Selection.of(fields).includes(field);
    }

    /**
     * What a controller computes (WordPress's {@code get_fields_for_response}) without {@code _embed}: see
     * {@link #forResponse(List, ObjectNode, String, boolean)}.
     */
    public static Selection forResponse(List<String> fields, ObjectNode itemSchema, String context) {
        return forResponse(fields, itemSchema, context, false);
    }

    /**
     * What a controller computes (WordPress's {@code get_fields_for_response}): the item schema's properties whose
     * {@code context} list is absent, empty or holds {@code context} (every property when {@code context} is null,
     * empty or {@code "0"}), then {@code _links}, then {@code _embedded} when the request has {@code _embed}. With
     * requested {@code fields}: those of them (in their order, repeats kept, then {@code id} when it is such a
     * property) that are one of these names or start with one followed by a dot; {@link Selection#nothing()} when
     * none is.
     */
    public static Selection forResponse(List<String> fields, ObjectNode itemSchema, String context, boolean embed) {
        JsonNode contextNode = context == null ? null : TextNode.valueOf(context);
        return select(fields, itemSchema, contextNode, embed, new long[1]);
    }

    /**
     * {@link #forResponse(List, ObjectNode, String, boolean)} for the request: its fields, its {@code context}
     * parameter (by PHP's truth: a false value filters nothing; a true value other than text keeps only properties
     * without a context list) and whether it has {@code _embed}. A request the REST server dispatched computes it
     * once per item schema; call it once per request and reuse the answer for every item.
     */
    public static Selection forResponse(RestRequest request, ObjectNode itemSchema) {
        if (request instanceof ServedRequest served) {
            return served.selection(itemSchema, () -> forRequest(request, itemSchema));
        }
        return forRequest(request, itemSchema);
    }

    private static Selection forRequest(RestRequest request, ObjectNode itemSchema) {
        return select(
                of(request),
                itemSchema,
                request.param("context").orElse(null),
                request.hasParam("_embed"),
                new long[1]);
    }

    /** The selection of {@link #forResponse}, adding to {@code work[0]} each character handled. */
    static Selection select(List<String> fields, ObjectNode itemSchema, JsonNode context, boolean embed, long[] work) {
        Set<String> names = new LinkedHashSet<>();
        JsonNode properties = itemSchema.get("properties");
        boolean filtered = context != null && !isPhpEmpty(context);
        if (properties != null && properties.isObject()) {
            for (Map.Entry<String, JsonNode> property : properties.properties()) {
                work[0]++;
                if (!filtered || inContext(property.getValue(), context)) {
                    names.add(property.getKey());
                }
            }
        }
        names.add("_links");
        if (embed) {
            names.add("_embedded");
        }
        if (fields.isEmpty()) {
            return Selection.of(names, work);
        }
        List<String> requested = new ArrayList<>(fields.size() + 1);
        requested.addAll(fields);
        if (names.contains("id")) {
            requested.add("id");
        }
        List<String> selected = new ArrayList<>();
        for (String field : requested) {
            int dot = field.indexOf('.');
            work[0] += (dot < 0 ? field.length() : dot) + 1;
            if (names.contains(field) || (dot >= 0 && names.contains(field.substring(0, dot)))) {
                selected.add(field);
            }
        }
        return Selection.of(selected, work);
    }

    /**
     * A property without a context list, or with an empty one, is in every context; otherwise the list must hold the
     * context as text (PHP's strict comparison: a context that is not text is in no list).
     */
    private static boolean inContext(JsonNode property, JsonNode context) {
        JsonNode contexts = property == null ? null : property.get("context");
        if (contexts == null || isPhpEmpty(contexts)) {
            return true;
        }
        if (!context.isTextual()) {
            return false;
        }
        for (JsonNode listed : contexts) {
            if (listed.isTextual() && listed.textValue().equals(context.textValue())) {
                return true;
            }
        }
        return false;
    }

    /** PHP's {@code empty}: null, false, 0, "", "0" or an empty array. */
    private static boolean isPhpEmpty(JsonNode value) {
        if (value.isNull()) {
            return true;
        }
        if (value.isContainerNode()) {
            return value.isEmpty();
        }
        if (value.isTextual()) {
            return value.textValue().isEmpty() || value.textValue().equals("0");
        }
        if (value.isBoolean()) {
            return !value.booleanValue();
        }
        return value.isNumber() && value.doubleValue() == 0.0;
    }
}
