package org.quarkpress.restwp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/**
 * An argument of an endpoint: its JSON schema (as WordPress's {@code args} entries hold it), whether it is required,
 * its default and when it is checked.
 *
 * @param defaultValue the value used when the request has none; checked and sanitized like any other value
 * @param sanitize the argument's own sanitizer, which replaces the schema's
 * @param validate the argument's own check, which replaces the schema's in the phase that checks it: a value it
 *     refuses is reported as "Invalid parameter." without details (WordPress's answer to a validate callback that
 *     returns false)
 */
public record Arg(
        String name,
        ObjectNode schema,
        boolean required,
        Optional<JsonNode> defaultValue,
        Phase phase,
        Optional<UnaryOperator<JsonNode>> sanitize,
        Optional<Predicate<JsonNode>> validate) {

    /** When the argument's value is checked against its schema. */
    public enum Phase {
        /** With the other arguments' checks, before any is sanitized. */
        VALIDATE,
        /** While the arguments are sanitized. */
        SANITIZE
    }

    public Arg {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(defaultValue, "defaultValue");
        Objects.requireNonNull(phase, "phase");
        Objects.requireNonNull(sanitize, "sanitize");
        Objects.requireNonNull(validate, "validate");
        if (defaultValue.isPresent() && defaultValue.get().isNull()) {
            // WordPress treats a null default as no default
            defaultValue = Optional.empty();
        }
    }

    /** An argument checked by its schema (or not at all, when it is untyped). */
    public Arg(
            String name,
            ObjectNode schema,
            boolean required,
            Optional<JsonNode> defaultValue,
            Phase phase,
            Optional<UnaryOperator<JsonNode>> sanitize) {
        this(name, schema, required, defaultValue, phase, sanitize, Optional.empty());
    }

    /** An argument of JSON schema type {@code type}; {@code null} leaves it untyped. */
    public static Builder of(String name, String type) {
        return new Builder(name, type);
    }

    public static final class Builder {

        private final String name;
        private final ObjectNode schema = JsonNodeFactory.instance.objectNode();
        private boolean required;
        private JsonNode defaultValue;
        private Phase phase = Phase.VALIDATE;
        private UnaryOperator<JsonNode> sanitize;
        private Predicate<JsonNode> validate;

        private Builder(String name, String type) {
            this.name = Objects.requireNonNull(name, "name");
            if (type != null) {
                schema.put("type", type);
            }
        }

        public Builder description(String description) {
            schema.put("description", description);
            return this;
        }

        public Builder enumOf(String... values) {
            var array = schema.putArray("enum");
            for (String value : values) {
                array.add(value);
            }
            return this;
        }

        public Builder minimum(long minimum) {
            schema.put("minimum", minimum);
            return this;
        }

        public Builder maximum(long maximum) {
            schema.put("maximum", maximum);
            return this;
        }

        public Builder format(String format) {
            schema.put("format", format);
            return this;
        }

        public Builder items(ObjectNode items) {
            schema.set("items", items);
            return this;
        }

        public Builder defaultValue(JsonNode value) {
            this.defaultValue = value;
            schema.set("default", value);
            return this;
        }

        public Builder required(boolean required) {
            this.required = required;
            return this;
        }

        public Builder required() {
            return required(true);
        }

        public Builder phase(Phase phase) {
            this.phase = Objects.requireNonNull(phase, "phase");
            return this;
        }

        public Builder sanitize(UnaryOperator<JsonNode> sanitize) {
            this.sanitize = Objects.requireNonNull(sanitize, "sanitize");
            return this;
        }

        public Builder validate(Predicate<JsonNode> validate) {
            this.validate = Objects.requireNonNull(validate, "validate");
            return this;
        }

        public Arg build() {
            return new Arg(
                    name,
                    schema.deepCopy(),
                    required,
                    Optional.ofNullable(defaultValue),
                    phase,
                    Optional.ofNullable(sanitize),
                    Optional.ofNullable(validate));
        }
    }
}
