package org.quarkpress.posts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.posts.runtime.MetaRows;

/**
 * A registered post meta key, as WordPress's {@code register_post_meta} describes one. {@code subtype} is the post
 * type the key belongs to, or "" for every type. {@code schema} is the key's JSON schema ({@code type}, {@code title}
 * as the label, {@code description}, and what the registration overlays: {@code items}, {@code properties},
 * {@code enum}, {@code default} and so on), stored as given; a key that is not {@code single} is shown in REST as
 * {@code {type: array, items: schema}}. {@code showInRest} makes the key part of the REST {@code meta} field under
 * {@link #restKey()}. The sanitizer runs on every value written through the registry; the auth decides, together with
 * {@code edit_post}, whether a caller may write the key (a protected key is refused without one).
 */
public record MetaKeyDefinition(
        String key,
        String subtype,
        ObjectNode schema,
        boolean single,
        boolean showInRest,
        Optional<String> restName,
        Optional<MetaSanitizer> sanitizer,
        boolean revisionsEnabled,
        Optional<MetaAuth> auth) {

    /** The value types a registered key may have: those the REST API accepts. */
    public static final List<String> TYPES = List.of("string", "boolean", "integer", "number", "array", "object");

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    public MetaKeyDefinition {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(subtype, "subtype");
        Objects.requireNonNull(schema, "schema");
        Objects.requireNonNull(restName, "restName");
        Objects.requireNonNull(sanitizer, "sanitizer");
        Objects.requireNonNull(auth, "auth");
        if (!MetaRows.validKey(key)) {
            throw new IllegalArgumentException("a meta key cannot be empty or \"0\"");
        }
        JsonNode type = schema.get("type");
        if (type == null || !type.isTextual() || !TYPES.contains(type.textValue())) {
            throw new IllegalArgumentException("the meta key " + key + " needs one of the types " + TYPES);
        }
        schema = schema.deepCopy();
    }

    /** The schema, as a copy. */
    @Override
    public ObjectNode schema() {
        return schema.deepCopy();
    }

    /** The value type, one of {@link #TYPES}. */
    public String type() {
        return schema.get("type").textValue();
    }

    /** The name the REST {@code meta} field uses: the REST name, or the key. */
    public String restKey() {
        return restName.orElse(key);
    }

    /** Whether the key is protected: its first printable character is an underscore. */
    public boolean isProtected() {
        return MetaRows.protectedKey(key);
    }

    /**
     * The value an absent key reads as: the declared default, else the type's empty value ("" for a string, false,
     * 0, 0.0, [] for an array and {} for an object).
     */
    public JsonNode emptyValue() {
        JsonNode declared = schema.get("default");
        if (declared != null) {
            return declared.deepCopy();
        }
        return switch (type()) {
            case "boolean" -> BooleanNode.FALSE;
            case "integer" -> IntNode.valueOf(0);
            case "number" -> DoubleNode.valueOf(0.0);
            case "array" -> NODES.arrayNode();
            case "object" -> NODES.objectNode();
            default -> TextNode.valueOf("");
        };
    }

    public static Builder builder(String key) {
        return new Builder(key);
    }

    /** Builds a definition with WordPress's registration defaults: type string, multi-valued, not in REST. */
    public static final class Builder {

        private final String key;
        private String subtype = "";
        private String type = "string";
        private String label = "";
        private String description = "";
        private JsonNode defaultValue;
        private ObjectNode overlay;
        private boolean single;
        private boolean showInRest;
        private String restName;
        private MetaSanitizer sanitizer;
        private boolean revisions;
        private MetaAuth auth;

        private Builder(String key) {
            this.key = Objects.requireNonNull(key, "key");
        }

        /** The post type the key belongs to; not given, every type. */
        public Builder subtype(String subtype) {
            this.subtype = Objects.requireNonNull(subtype, "subtype");
            return this;
        }

        /** The value type, one of {@link #TYPES}; not given, string. */
        public Builder type(String type) {
            this.type = Objects.requireNonNull(type, "type");
            return this;
        }

        /** The label, shown as the schema's title. */
        public Builder label(String label) {
            this.label = Objects.requireNonNull(label, "label");
            return this;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        /** The default an absent key reads as. */
        public Builder defaultValue(JsonNode value) {
            this.defaultValue = Objects.requireNonNull(value, "value").deepCopy();
            return this;
        }

        /** Schema entries laid over the built ones: {@code items}, {@code properties}, {@code enum} and so on. */
        public Builder schema(ObjectNode overlay) {
            this.overlay = Objects.requireNonNull(overlay, "overlay").deepCopy();
            return this;
        }

        /** One value per post; not given, the key holds a list of values. */
        public Builder single() {
            this.single = true;
            return this;
        }

        public Builder showInRest() {
            this.showInRest = true;
            return this;
        }

        /** The name the REST {@code meta} field uses instead of the key. */
        public Builder restName(String restName) {
            this.restName = Objects.requireNonNull(restName, "restName");
            return this;
        }

        public Builder sanitizer(MetaSanitizer sanitizer) {
            this.sanitizer = Objects.requireNonNull(sanitizer, "sanitizer");
            return this;
        }

        /** The key is copied into revisions, compared by the change check and restored. */
        public Builder revisions() {
            this.revisions = true;
            return this;
        }

        public Builder auth(MetaAuth auth) {
            this.auth = Objects.requireNonNull(auth, "auth");
            return this;
        }

        public MetaKeyDefinition build() {
            ObjectNode schema = NODES.objectNode();
            schema.put("type", type);
            schema.put("title", label);
            schema.put("description", description);
            if (defaultValue != null) {
                schema.set("default", defaultValue);
            }
            if (overlay != null) {
                for (Map.Entry<String, JsonNode> entry : overlay.properties()) {
                    schema.set(entry.getKey(), entry.getValue());
                }
            }
            return new MetaKeyDefinition(
                    key,
                    subtype,
                    schema,
                    single,
                    showInRest,
                    Optional.ofNullable(restName),
                    Optional.ofNullable(sanitizer),
                    revisions,
                    Optional.ofNullable(auth));
        }
    }
}
