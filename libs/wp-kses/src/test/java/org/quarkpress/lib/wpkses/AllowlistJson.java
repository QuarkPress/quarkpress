package org.quarkpress.lib.wpkses;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * An {@link Allowlist} from the JSON form of a WordPress allowlist array, read the way WordPress reads the PHP
 * array the oracle driver decodes from it. Callbacks have no JSON form.
 */
final class AllowlistJson {

    private AllowlistJson() {}

    static Allowlist fromPhp(JsonNode node) {
        Allowlist.Builder builder = Allowlist.builder();
        if (node.isArray()) {
            if (!node.isEmpty()) {
                throw new IllegalArgumentException("a JSON list is not an allowlist: " + node);
            }
            return builder.build();
        }
        for (Map.Entry<String, JsonNode> element : node.properties()) {
            JsonNode spec = element.getValue();
            if (spec.isNull()) {
                continue; // isset() is false for null: the element is not allowed
            }
            builder.element(element.getKey());
            if (!spec.isObject()) {
                continue; // true, false, 0, "", [] and the like: no attributes
            }
            for (Map.Entry<String, JsonNode> attribute : spec.properties()) {
                if (attribute.getKey().equals("data-*") && phpEmpty(attribute.getValue())) {
                    continue; // empty() of the wildcard's value: no data- attributes at all
                }
                AttributeRule rule = rule(attribute.getValue());
                if (rule != null) {
                    builder.attribute(element.getKey(), attribute.getKey(), rule);
                }
            }
        }
        return builder.build();
    }

    /** PHP's empty() for a JSON-decoded value: null, false, 0, 0.0, "", "0" and empty arrays. */
    private static boolean phpEmpty(JsonNode value) {
        return value.isNull()
                || (value.isBoolean() && !value.booleanValue())
                || (value.isNumber() && value.asDouble() == 0)
                || (value.isTextual()
                        && (value.textValue().isEmpty() || value.textValue().equals("0")))
                || (value.isContainerNode() && value.isEmpty());
    }

    /**
     * Null where WordPress treats the attribute as not allowed under its own name: null and "" (an attribute
     * given "" falls through to the data-* wildcard).
     */
    private static AttributeRule rule(JsonNode spec) {
        if (spec.isNull() || (spec.isTextual() && spec.textValue().isEmpty())) {
            return null;
        }
        if (!spec.isObject()) {
            return AttributeRule.allowed();
        }
        boolean required = false;
        List<AttributeCheck> checks = new ArrayList<>();
        for (Map.Entry<String, JsonNode> check : spec.properties()) {
            JsonNode value = check.getValue();
            if (check.getKey().equals("required") && value.isBoolean() && value.booleanValue()) {
                required = true;
                continue;
            }
            switch (TextUtils.asciiLower(check.getKey())) {
                case "maxlen" -> checks.add(AttributeCheck.maxLength(value.asLong()));
                case "minlen" -> checks.add(AttributeCheck.minLength(value.asLong()));
                case "maxval" -> checks.add(AttributeCheck.maxValue(value.asLong()));
                case "minval" -> checks.add(AttributeCheck.minValue(value.asLong()));
                case "valueless" -> checks.add(AttributeCheck.valueless(value.asText()));
                case "values" -> checks.add(AttributeCheck.values(Examples.strings(value)));
                case "value_callback" -> throw new IllegalArgumentException("callbacks have no JSON form");
                default -> {
                    // WordPress ignores unknown checks
                }
            }
        }
        AttributeCheck[] array = checks.toArray(AttributeCheck[]::new);
        if (required) {
            return AttributeRule.required(array);
        }
        return array.length == 0 ? AttributeRule.allowed() : AttributeRule.of(array);
    }
}
