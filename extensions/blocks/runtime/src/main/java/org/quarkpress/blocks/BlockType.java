package org.quarkpress.blocks;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A block type as WordPress's block type registry knows it: the keys of a {@code block.json} file and the script and
 * style handles of its assets. Every JSON node handed out is a copy.
 */
public final class BlockType {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final String name;
    private final String title;
    private final String description;
    private final int apiVersion;
    private final JsonNode icon;
    private final String category;
    private final List<String> keywords;
    private final List<String> parent;
    private final List<String> ancestor;
    private final List<String> allowedBlocks;
    private final ObjectNode attributes;
    private final ObjectNode providesContext;
    private final List<String> usesContext;
    private final ObjectNode selectors;
    private final ObjectNode supports;
    private final ArrayNode styles;
    private final String textdomain;
    private final JsonNode example;
    private final ArrayNode variations;
    private final ObjectNode blockHooks;
    private final List<String> editorScriptHandles;
    private final List<String> scriptHandles;
    private final List<String> viewScriptHandles;
    private final List<String> viewScriptModuleIds;
    private final List<String> editorStyleHandles;
    private final List<String> styleHandles;
    private final List<String> viewStyleHandles;

    private BlockType(Builder builder) {
        this.name = builder.name;
        this.title = builder.title;
        this.description = builder.description;
        this.apiVersion = builder.apiVersion;
        this.icon = builder.icon;
        this.category = builder.category;
        this.keywords = builder.keywords;
        this.parent = builder.parent;
        this.ancestor = builder.ancestor;
        this.allowedBlocks = builder.allowedBlocks;
        this.attributes = builder.attributes;
        this.providesContext = builder.providesContext;
        this.usesContext = builder.usesContext;
        this.selectors = builder.selectors;
        this.supports = builder.supports;
        this.styles = builder.styles;
        this.textdomain = builder.textdomain;
        this.example = builder.example;
        this.variations = builder.variations;
        this.blockHooks = builder.blockHooks;
        this.editorScriptHandles = builder.editorScriptHandles;
        this.scriptHandles = builder.scriptHandles;
        this.viewScriptHandles = builder.viewScriptHandles;
        this.viewScriptModuleIds = builder.viewScriptModuleIds;
        this.editorStyleHandles = builder.editorStyleHandles;
        this.styleHandles = builder.styleHandles;
        this.viewStyleHandles = builder.viewStyleHandles;
    }

    /**
     * A builder for the block type {@code name}.
     *
     * @throws IllegalArgumentException unless the name is {@code <namespace>/<name>}, each part starting with a
     *     lower-case ASCII letter and holding only lower-case ASCII letters, digits and dashes
     */
    public static Builder builder(String name) {
        return new Builder(name);
    }

    /**
     * A block type from the contents of a {@code block.json} file. The keys {@code apiVersion}, {@code name},
     * {@code title}, {@code category}, {@code parent}, {@code ancestor}, {@code allowedBlocks}, {@code icon},
     * {@code description}, {@code keywords}, {@code textdomain}, {@code attributes}, {@code providesContext},
     * {@code usesContext}, {@code selectors}, {@code supports}, {@code styles}, {@code example}, {@code variations}
     * and {@code blockHooks} are read; others (asset files among them) are ignored, as is a key whose value has
     * another JSON type than the key takes.
     *
     * @throws IllegalArgumentException when the name is missing or not a block type name
     */
    public static BlockType fromBlockJson(JsonNode blockJson) {
        JsonNode nameNode = blockJson.get("name");
        Builder builder =
                builder(nameNode != null && nameNode.isTextual() ? nameNode.textValue() : String.valueOf(nameNode));
        JsonNode value = blockJson.get("apiVersion");
        if (value != null && value.isIntegralNumber()) {
            builder.apiVersion(value.intValue());
        }
        text(blockJson, "title").ifPresent(builder::title);
        text(blockJson, "category").ifPresent(builder::category);
        strings(blockJson, "parent").ifPresent(builder::parent);
        strings(blockJson, "ancestor").ifPresent(builder::ancestor);
        strings(blockJson, "allowedBlocks").ifPresent(builder::allowedBlocks);
        value = blockJson.get("icon");
        if (value != null && !value.isNull()) {
            builder.icon(value);
        }
        text(blockJson, "description").ifPresent(builder::description);
        strings(blockJson, "keywords").ifPresent(builder::keywords);
        text(blockJson, "textdomain").ifPresent(builder::textdomain);
        object(blockJson, "attributes").ifPresent(builder::attributes);
        object(blockJson, "providesContext").ifPresent(builder::providesContext);
        strings(blockJson, "usesContext").ifPresent(builder::usesContext);
        object(blockJson, "selectors").ifPresent(builder::selectors);
        object(blockJson, "supports").ifPresent(builder::supports);
        value = blockJson.get("styles");
        if (value instanceof ArrayNode array) {
            builder.styles(array);
        }
        value = blockJson.get("example");
        if (value != null && !value.isNull()) {
            builder.example(value);
        }
        value = blockJson.get("variations");
        if (value instanceof ArrayNode array) {
            builder.variations(array);
        }
        object(blockJson, "blockHooks").ifPresent(builder::blockHooks);
        return builder.build();
    }

    private static Optional<String> text(JsonNode json, String key) {
        JsonNode value = json.get(key);
        return value != null && value.isTextual() ? Optional.of(value.textValue()) : Optional.empty();
    }

    private static Optional<ObjectNode> object(JsonNode json, String key) {
        return json.get(key) instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
    }

    private static Optional<List<String>> strings(JsonNode json, String key) {
        if (!(json.get(key) instanceof ArrayNode array)) {
            return Optional.empty();
        }
        List<String> list = new ArrayList<>(array.size());
        for (JsonNode item : array) {
            if (!item.isTextual()) {
                return Optional.empty();
            }
            list.add(item.textValue());
        }
        return Optional.of(list);
    }

    /** Whether {@code name} is {@code <namespace>/<name>} in lower-case ASCII letters, digits and dashes. */
    static boolean validName(String name) {
        int slash = name.indexOf('/');
        return slash > 0 && validPart(name, 0, slash) && validPart(name, slash + 1, name.length());
    }

    private static boolean validPart(String name, int from, int to) {
        if (from >= to || !lowerLetter(name.charAt(from))) {
            return false;
        }
        for (int i = from + 1; i < to; i++) {
            char c = name.charAt(i);
            if (!lowerLetter(c) && !(c >= '0' && c <= '9') && c != '-') {
                return false;
            }
        }
        return true;
    }

    private static boolean lowerLetter(char c) {
        return c >= 'a' && c <= 'z';
    }

    public String name() {
        return name;
    }

    /** The title; empty when none is set. */
    public String title() {
        return title;
    }

    /** The description; empty when none is set. */
    public String description() {
        return description;
    }

    /** The block API version; 1 when none is set. */
    public int apiVersion() {
        return apiVersion;
    }

    /** The icon: a Dashicon name, an SVG string or an object. */
    public Optional<JsonNode> icon() {
        return Optional.ofNullable(icon).map(JsonNode::deepCopy);
    }

    public Optional<String> category() {
        return Optional.ofNullable(category);
    }

    public List<String> keywords() {
        return keywords;
    }

    public Optional<List<String>> parent() {
        return Optional.ofNullable(parent);
    }

    public Optional<List<String>> ancestor() {
        return Optional.ofNullable(ancestor);
    }

    public Optional<List<String>> allowedBlocks() {
        return Optional.ofNullable(allowedBlocks);
    }

    /** The attributes the type declares, in declaration order (without those its supports add). */
    public ObjectNode declaredAttributes() {
        return attributes.deepCopy();
    }

    public ObjectNode providesContext() {
        return providesContext.deepCopy();
    }

    public List<String> usesContext() {
        return usesContext;
    }

    public ObjectNode selectors() {
        return selectors.deepCopy();
    }

    public ObjectNode supports() {
        return supports.deepCopy();
    }

    public ArrayNode styles() {
        return styles.deepCopy();
    }

    public Optional<String> textdomain() {
        return Optional.ofNullable(textdomain);
    }

    public Optional<JsonNode> example() {
        return Optional.ofNullable(example).map(JsonNode::deepCopy);
    }

    public ArrayNode variations() {
        return variations.deepCopy();
    }

    public ObjectNode blockHooks() {
        return blockHooks.deepCopy();
    }

    public List<String> editorScriptHandles() {
        return editorScriptHandles;
    }

    public List<String> scriptHandles() {
        return scriptHandles;
    }

    public List<String> viewScriptHandles() {
        return viewScriptHandles;
    }

    public List<String> viewScriptModuleIds() {
        return viewScriptModuleIds;
    }

    public List<String> editorStyleHandles() {
        return editorStyleHandles;
    }

    public List<String> styleHandles() {
        return styleHandles;
    }

    public List<String> viewStyleHandles() {
        return viewStyleHandles;
    }

    @Override
    public String toString() {
        return "BlockType[" + name + "]";
    }

    /** Builds a {@link BlockType}; every node passed in is copied. */
    public static final class Builder {

        private final String name;
        private String title = "";
        private String description = "";
        private int apiVersion = 1;
        private JsonNode icon;
        private String category;
        private List<String> keywords = List.of();
        private List<String> parent;
        private List<String> ancestor;
        private List<String> allowedBlocks;
        private ObjectNode attributes = NODES.objectNode();
        private ObjectNode providesContext = NODES.objectNode();
        private List<String> usesContext = List.of();
        private ObjectNode selectors = NODES.objectNode();
        private ObjectNode supports = NODES.objectNode();
        private ArrayNode styles = NODES.arrayNode();
        private String textdomain;
        private JsonNode example;
        private ArrayNode variations = NODES.arrayNode();
        private ObjectNode blockHooks = NODES.objectNode();
        private List<String> editorScriptHandles = List.of();
        private List<String> scriptHandles = List.of();
        private List<String> viewScriptHandles = List.of();
        private List<String> viewScriptModuleIds = List.of();
        private List<String> editorStyleHandles = List.of();
        private List<String> styleHandles = List.of();
        private List<String> viewStyleHandles = List.of();

        private Builder(String name) {
            if (name == null || !validName(name)) {
                throw new IllegalArgumentException("QuarkPress: a block type name is <namespace>/<name> in lower-case"
                        + " letters, digits and dashes: " + name);
            }
            this.name = name;
        }

        public Builder title(String title) {
            this.title = Objects.requireNonNull(title, "title");
            return this;
        }

        public Builder description(String description) {
            this.description = Objects.requireNonNull(description, "description");
            return this;
        }

        public Builder apiVersion(int apiVersion) {
            this.apiVersion = apiVersion;
            return this;
        }

        public Builder icon(JsonNode icon) {
            this.icon = icon.deepCopy();
            return this;
        }

        public Builder category(String category) {
            this.category = Objects.requireNonNull(category, "category");
            return this;
        }

        public Builder keywords(List<String> keywords) {
            this.keywords = List.copyOf(keywords);
            return this;
        }

        public Builder parent(List<String> parent) {
            this.parent = List.copyOf(parent);
            return this;
        }

        public Builder ancestor(List<String> ancestor) {
            this.ancestor = List.copyOf(ancestor);
            return this;
        }

        public Builder allowedBlocks(List<String> allowedBlocks) {
            this.allowedBlocks = List.copyOf(allowedBlocks);
            return this;
        }

        /** The declared attributes, in declaration order. */
        public Builder attributes(ObjectNode attributes) {
            this.attributes = attributes.deepCopy();
            return this;
        }

        public Builder providesContext(ObjectNode providesContext) {
            this.providesContext = providesContext.deepCopy();
            return this;
        }

        public Builder usesContext(List<String> usesContext) {
            this.usesContext = List.copyOf(usesContext);
            return this;
        }

        public Builder selectors(ObjectNode selectors) {
            this.selectors = selectors.deepCopy();
            return this;
        }

        public Builder supports(ObjectNode supports) {
            this.supports = supports.deepCopy();
            return this;
        }

        public Builder styles(ArrayNode styles) {
            this.styles = styles.deepCopy();
            return this;
        }

        public Builder textdomain(String textdomain) {
            this.textdomain = Objects.requireNonNull(textdomain, "textdomain");
            return this;
        }

        public Builder example(JsonNode example) {
            this.example = example.deepCopy();
            return this;
        }

        public Builder variations(ArrayNode variations) {
            this.variations = variations.deepCopy();
            return this;
        }

        public Builder blockHooks(ObjectNode blockHooks) {
            this.blockHooks = blockHooks.deepCopy();
            return this;
        }

        public Builder editorScriptHandles(List<String> handles) {
            this.editorScriptHandles = List.copyOf(handles);
            return this;
        }

        public Builder scriptHandles(List<String> handles) {
            this.scriptHandles = List.copyOf(handles);
            return this;
        }

        public Builder viewScriptHandles(List<String> handles) {
            this.viewScriptHandles = List.copyOf(handles);
            return this;
        }

        public Builder viewScriptModuleIds(List<String> ids) {
            this.viewScriptModuleIds = List.copyOf(ids);
            return this;
        }

        public Builder editorStyleHandles(List<String> handles) {
            this.editorStyleHandles = List.copyOf(handles);
            return this;
        }

        public Builder styleHandles(List<String> handles) {
            this.styleHandles = List.copyOf(handles);
            return this;
        }

        public Builder viewStyleHandles(List<String> handles) {
            this.viewStyleHandles = List.copyOf(handles);
            return this;
        }

        public BlockType build() {
            return new BlockType(this);
        }
    }
}
