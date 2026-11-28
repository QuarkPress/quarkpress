package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Turns a block style object into declarations and class names, one definition of the table at a time, as
 * WordPress's style engine does. Values are passed through as they are: text, numbers, booleans and nested arrays
 * all reach the declarations, and nested values are never walked.
 */
final class BlockStyles {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final String BACKGROUND_IMAGE = "background-image";

    private BlockStyles() {}

    /** The declarations (raw, in order) and the class names (duplicates kept) of a block style object. */
    record Parsed(LinkedHashMap<String, JsonNode> declarations, List<String> classnames) {}

    static Parsed parse(JsonNode blockStyles, boolean convertVarsToClassnames) {
        LinkedHashMap<String, JsonNode> declarations = new LinkedHashMap<>();
        List<String> classnames = new ArrayList<>();
        if (Php.isEmpty(blockStyles) || !Php.isArray(blockStyles)) {
            return new Parsed(declarations, classnames);
        }
        String group = null;
        boolean skipGroup = false;
        for (Definition d : Definitions.all()) {
            if (!d.group().equals(group)) {
                group = d.group();
                skipGroup = Php.isEmpty(Php.get(blockStyles, group));
            }
            if (skipGroup) {
                continue;
            }
            JsonNode value = valueAt(blockStyles, d.path());
            if (!Php.isValidStyleValue(value)) {
                continue;
            }
            classnames.addAll(classnames(value, d));
            LinkedHashMap<String, JsonNode> found = declarations(value, d, convertVarsToClassnames);
            if (found.isEmpty()) {
                continue;
            }
            JsonNode existing = declarations.get(BACKGROUND_IMAGE);
            JsonNode added = found.get(BACKGROUND_IMAGE);
            if (existing != null && added != null) {
                // the new image goes in front of the one already there
                found.put(BACKGROUND_IMAGE, NODES.textNode(Php.toPhpString(added) + ", " + Php.toPhpString(existing)));
            }
            // a property already present keeps its position and takes the new value
            declarations.putAll(found);
        }
        return new Parsed(declarations, classnames);
    }

    /** The value at {@code path}, step by step; {@code null} once a step is not an array or the key is missing. */
    private static JsonNode valueAt(JsonNode blockStyles, List<String> path) {
        JsonNode node = blockStyles;
        for (String key : path) {
            if (!Php.isArray(node)) {
                return null;
            }
            node = Php.get(node, key);
        }
        return node;
    }

    /** The definition's class names for a value; a PHP-empty value, {@code "0"} included, gives none. */
    static List<String> classnames(JsonNode value, Definition d) {
        List<String> out = new ArrayList<>();
        if (Php.isEmpty(value)) {
            return out;
        }
        for (ClassnameRule rule : d.classnames()) {
            if (rule.presetType() == null) {
                out.add(rule.pattern());
                continue;
            }
            String slug = Presets.slug(value, rule.presetType());
            if (!Php.isEmpty(slug)) {
                out.add(rule.pattern().replace("$slug", slug));
            }
        }
        return out;
    }

    /**
     * The definition's declarations for a value. A text value containing {@code var:} becomes the preset's custom
     * property or nothing; an array becomes one declaration per side when the definition has a side pattern; any
     * other value is declared as it is.
     */
    static LinkedHashMap<String, JsonNode> declarations(JsonNode value, Definition d, boolean convertVarsToClassnames) {
        if (d.valueFunc() == ValueFunc.URL_OR_VALUE) {
            return urlOrValue(value, d);
        }
        if (d.valueFunc() == ValueFunc.INDIVIDUAL_SIDES) {
            return individualSides(value, d, convertVarsToClassnames);
        }
        LinkedHashMap<String, JsonNode> out = new LinkedHashMap<>();
        if (isVarText(value)) {
            if (!convertVarsToClassnames && !d.cssVars().isEmpty()) {
                String cssVar = Presets.cssVarValue(value, d.cssVars());
                if (!cssVar.isEmpty()) {
                    out.put(d.defaultProperty(), NODES.textNode(cssVar));
                }
            }
            return out;
        }
        if (Php.isArray(value)) {
            if (d.individualPattern() == null) {
                return out;
            }
            for (Map.Entry<String, JsonNode> e : Php.entries(value)) {
                JsonNode side = e.getValue();
                if (isVarText(side) && !convertVarsToClassnames && !d.cssVars().isEmpty()) {
                    side = NODES.textNode(Presets.cssVarValue(side, d.cssVars()));
                }
                String property = d.individualPattern().replace("%s", KebabCase.of(e.getKey()));
                if (Php.isValidStyleValue(side)) {
                    out.put(property, side);
                }
            }
            return out;
        }
        out.put(d.defaultProperty(), value);
        return out;
    }

    /**
     * A background image: {@code {"url": u}} with a non-empty {@code u} becomes {@code url('u')}, unescaped; a text
     * value is used as it is, presets included; anything else gives nothing.
     */
    static LinkedHashMap<String, JsonNode> urlOrValue(JsonNode value, Definition d) {
        LinkedHashMap<String, JsonNode> out = new LinkedHashMap<>();
        if (Php.isEmpty(value) || d.defaultProperty() == null) {
            return out;
        }
        JsonNode url = Php.get(value, "url");
        if (!Php.isEmpty(url)) {
            out.put(d.defaultProperty(), NODES.textNode("url('" + Php.toPhpString(url) + "')"));
        } else if (value.isTextual()) {
            out.put(d.defaultProperty(), value);
        }
        return out;
    }

    /**
     * One border side: each non-empty entry whose key names a border definition with a side pattern becomes that
     * pattern for this side, unchecked. Only the colour preset converts; another preset becomes {@code ""}.
     */
    static LinkedHashMap<String, JsonNode> individualSides(
            JsonNode value, Definition d, boolean convertVarsToClassnames) {
        LinkedHashMap<String, JsonNode> out = new LinkedHashMap<>();
        if (!Php.isArray(value) || Php.isEmpty(value) || d.path().isEmpty()) {
            return out;
        }
        String group = d.path().get(0);
        String side = d.path().size() > 1 ? d.path().get(1) : "";
        for (Map.Entry<String, JsonNode> e : Php.entries(value)) {
            JsonNode v = e.getValue();
            if (Php.isEmpty(v)) {
                continue;
            }
            Optional<Definition> property = Definitions.find(group, e.getKey());
            if (property.isEmpty() || property.get().individualPattern() == null) {
                continue;
            }
            if (isVarText(v) && !convertVarsToClassnames && !d.cssVars().isEmpty()) {
                v = NODES.textNode(Presets.cssVarValue(v, d.cssVars()));
            }
            out.put(property.get().individualPattern().replace("%s", side), v);
        }
        return out;
    }

    private static boolean isVarText(JsonNode v) {
        return v != null && v.isTextual() && v.asText().contains("var:");
    }
}
