package org.quarkpress.lib.wpkses;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

/** The Java tables in the JSON shape the driver's "tables" function gives for WordPress (WordPress mode). */
final class TablesJson {

    static final List<String> CONTEXTS = List.of(
            "post",
            "data",
            "user_description",
            "pre_user_description",
            "pre_term_description",
            "strip",
            "pre_comment_content",
            "title_save_pre");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private TablesJson() {}

    static ObjectNode javaTables() {
        ObjectNode out = NODES.objectNode();
        for (String context : CONTEXTS) {
            out.set(
                    "context:" + context,
                    allowlist(Contexts.resolve(KsesContext.named(context), Map.of(), List.of(Contexts.NOTE_MENTION))));
        }
        out.set("entities", strings(EntityNames.ALLOWED));
        out.set("xml_entities", strings(EntityNames.XML));
        out.set("uri_attributes", strings(WordPressTables.INSTANCE.uriAttributes()));
        out.set("protocols", strings(WordPressTables.INSTANCE.protocols()));
        out.set("safe_style_css", strings(WordPressTables.INSTANCE.safeStyleProperties()));
        return out;
    }

    /** PHP's json_encode of the allowlist array: [] when empty, else an object. */
    static JsonNode allowlist(Allowlist list) {
        if (list.elements().isEmpty()) {
            return NODES.arrayNode();
        }
        ObjectNode out = NODES.objectNode();
        list.elements().forEach((name, element) -> {
            if (element.attributes().isEmpty()) {
                out.set(name, NODES.arrayNode());
                return;
            }
            ObjectNode attributes = out.putObject(name);
            element.attributes().forEach((attribute, rule) -> attributes.set(attribute, rule(rule)));
        });
        return out;
    }

    static JsonNode rule(AttributeRule rule) {
        if (!rule.required() && rule.checks().isEmpty()) {
            return BooleanNode.TRUE;
        }
        ObjectNode out = NODES.objectNode();
        if (rule.required()) {
            out.put("required", true);
        }
        for (AttributeCheck check : rule.checks()) {
            switch (check) {
                case AttributeCheck.MaxLength c -> number(out, "maxlen", c.bytes());
                case AttributeCheck.MinLength c -> number(out, "minlen", c.bytes());
                case AttributeCheck.MaxValue c -> number(out, "maxval", c.max());
                case AttributeCheck.MinValue c -> number(out, "minval", c.min());
                case AttributeCheck.Valueless c -> out.put("valueless", c.flag());
                case AttributeCheck.Values c -> out.set("values", strings(c.allowed()));
                case AttributeCheck.PdfObjectUrl c -> out.put("value_callback", "_wp_kses_allow_pdf_objects");
                case AttributeCheck.Callback c -> throw new IllegalArgumentException("a callback has no JSON form");
            }
        }
        return out;
    }

    /** JSON integers that fit in an int compare equal to Jackson's IntNode, as PHP writes them. */
    private static void number(ObjectNode out, String key, long value) {
        if (value == (int) value) {
            out.put(key, (int) value);
        } else {
            out.put(key, value);
        }
    }

    private static ArrayNode strings(List<String> values) {
        ArrayNode out = NODES.arrayNode();
        values.forEach(out::add);
        return out;
    }
}
