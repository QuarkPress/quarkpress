package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import org.quarkpress.core.runtime.PhpValues;

/** Stored option values as PHP code prints them. */
final class PhpOptionStrings {

    private PhpOptionStrings() {}

    /**
     * A text, number or boolean as PHP's string conversion writes it ({@code true} is "1", {@code false} is "",
     * {@code 1.5} is "1.5"); null for any other value.
     */
    static String of(JsonNode value) {
        if (value.isTextual()) {
            return value.textValue();
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "1" : "";
        }
        if (value.isIntegralNumber() && value.canConvertToLong()) {
            return Long.toString(value.longValue());
        }
        if (value.isNumber()) {
            return PhpValues.floatString(value.doubleValue());
        }
        return null;
    }
}
