package org.quarkpress.media;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.Objects;

/**
 * How a size is cut: not at all (the image is fitted into the box), from the centre, or at a gravity pair. A pair is
 * kept as given; a value other than {@code left}/{@code center}/{@code right} for x or {@code top}/{@code
 * center}/{@code bottom} for y means the centre when the size is made. A centre pair is not the same value as
 * {@link Hard}, so a registration keeps the form it was given.
 */
public sealed interface Crop {

    /** Fit into the box, keeping the aspect ratio. */
    record None() implements Crop {}

    /** Cut to the box from the centre. */
    record Hard() implements Crop {}

    /**
     * Cut to the box at a gravity: x {@code left}, {@code center} or {@code right}; y {@code top}, {@code center} or
     * {@code bottom}.
     */
    record At(String x, String y) implements Crop {

        public At {
            Objects.requireNonNull(x, "x");
            Objects.requireNonNull(y, "y");
        }
    }

    static Crop none() {
        return new None();
    }

    static Crop hard() {
        return new Hard();
    }

    /**
     * The crop an option value stands for, by PHP truthiness: {@code true}, a non-zero number or a text other than
     * {@code ""} and {@code "0"} cuts from the centre; {@code false}, zero, those two texts, null, an absent value
     * and an empty array do not cut. A two-element array is a gravity pair (its values as text); any other non-empty
     * array is a malformed pair, which cuts from the centre.
     */
    static Crop fromOption(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            return none();
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? hard() : none();
        }
        if (value.isNumber()) {
            return value.doubleValue() != 0 ? hard() : none();
        }
        if (value.isTextual()) {
            String text = value.textValue();
            return text.isEmpty() || text.equals("0") ? none() : hard();
        }
        if (value.isContainerNode()) {
            if (value.size() == 0) {
                return none();
            }
            if (value.size() == 2) {
                Iterator<JsonNode> elements = value.elements();
                return new At(elements.next().asText(), elements.next().asText());
            }
            return hard();
        }
        return hard();
    }
}
