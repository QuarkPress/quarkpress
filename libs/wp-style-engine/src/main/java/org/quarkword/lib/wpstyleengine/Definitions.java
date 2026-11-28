package org.quarkword.lib.wpstyleengine;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * WordPress's block style definitions table, in its order: the groups {@code background}, {@code color},
 * {@code border}, {@code shadow}, {@code dimensions}, {@code spacing} and {@code typography}, each with its
 * definitions in order. The order fixes the order of the declarations and class names a block style object gives.
 */
final class Definitions {

    private static final String COLOR_VAR = "--wp--preset--color--$slug";
    private static final String GRADIENT_VAR = "--wp--preset--gradient--$slug";
    private static final String DIMENSION_VAR = "--wp--preset--dimension--$slug";
    private static final String SPACING_VAR = "--wp--preset--spacing--$slug";

    private static final List<Definition> ALL = List.of(
            // background
            row("background", "backgroundImage", "background-image")
                    .valueFunc(ValueFunc.URL_OR_VALUE)
                    .build(),
            row("background", "backgroundPosition", "background-position").build(),
            row("background", "backgroundRepeat", "background-repeat").build(),
            row("background", "backgroundSize", "background-size").build(),
            row("background", "backgroundAttachment", "background-attachment").build(),
            row("background", "gradient", "background-image")
                    .cssVar("gradient", GRADIENT_VAR)
                    .always("has-background")
                    .build(),
            // color
            row("color", "text", "color")
                    .cssVar("color", COLOR_VAR)
                    .always("has-text-color")
                    .preset("has-$slug-color", "color")
                    .build(),
            row("color", "background", "background-color")
                    .cssVar("color", COLOR_VAR)
                    .always("has-background")
                    .preset("has-$slug-background-color", "color")
                    .build(),
            row("color", "gradient", "background")
                    .cssVar("gradient", GRADIENT_VAR)
                    .always("has-background")
                    .preset("has-$slug-gradient-background", "gradient")
                    .build(),
            // border: the colour shorthand has no preset custom property, unlike the per-side colours
            row("border", "color", "border-color")
                    .individual("border-%s-color")
                    .always("has-border-color")
                    .preset("has-$slug-border-color", "color")
                    .build(),
            row("border", "radius", "border-radius")
                    .individual("border-%s-radius")
                    .cssVar("border-radius", "--wp--preset--border-radius--$slug")
                    .build(),
            row("border", "style", "border-style").individual("border-%s-style").build(),
            row("border", "width", "border-width").individual("border-%s-width").build(),
            side("top"),
            side("right"),
            side("bottom"),
            side("left"),
            // shadow: the value sits at the top-level key
            new Row("shadow", "shadow", List.of("shadow"), "box-shadow")
                    .cssVar("shadow", "--wp--preset--shadow--$slug")
                    .build(),
            // dimensions
            row("dimensions", "aspectRatio", "aspect-ratio")
                    .always("has-aspect-ratio")
                    .build(),
            row("dimensions", "height", "height")
                    .cssVar("dimension", DIMENSION_VAR)
                    .build(),
            row("dimensions", "minHeight", "min-height")
                    .cssVar("dimension", DIMENSION_VAR)
                    .build(),
            row("dimensions", "minWidth", "min-width")
                    .cssVar("dimension", DIMENSION_VAR)
                    .build(),
            row("dimensions", "objectFit", "object-fit").build(),
            row("dimensions", "width", "width")
                    .cssVar("dimension", DIMENSION_VAR)
                    .build(),
            // spacing
            row("spacing", "padding", "padding")
                    .individual("padding-%s")
                    .cssVar("spacing", SPACING_VAR)
                    .build(),
            row("spacing", "margin", "margin")
                    .individual("margin-%s")
                    .cssVar("spacing", SPACING_VAR)
                    .build(),
            // typography
            row("typography", "fontSize", "font-size")
                    .cssVar("font-size", "--wp--preset--font-size--$slug")
                    .preset("has-$slug-font-size", "font-size")
                    .build(),
            row("typography", "fontFamily", "font-family")
                    .cssVar("font-family", "--wp--preset--font-family--$slug")
                    .preset("has-$slug-font-family", "font-family")
                    .build(),
            row("typography", "fontStyle", "font-style").build(),
            row("typography", "fontWeight", "font-weight").build(),
            row("typography", "lineHeight", "line-height").build(),
            row("typography", "textColumns", "column-count").build(),
            row("typography", "textDecoration", "text-decoration").build(),
            row("typography", "textIndent", "text-indent").build(),
            row("typography", "textTransform", "text-transform").build(),
            row("typography", "letterSpacing", "letter-spacing").build(),
            row("typography", "writingMode", "writing-mode").build());

    /** group, then key, to definition. */
    private static final Map<String, Map<String, Definition>> BY_GROUP = index();

    private Definitions() {}

    /** The rows in table order. */
    static List<Definition> all() {
        return ALL;
    }

    /** The definition at {@code group.key}, as a lookup in WordPress's table. */
    static Optional<Definition> find(String group, String key) {
        Map<String, Definition> definitions = BY_GROUP.get(group);
        return definitions == null ? Optional.empty() : Optional.ofNullable(definitions.get(key));
    }

    private static Map<String, Map<String, Definition>> index() {
        Map<String, Map<String, Definition>> index = new HashMap<>();
        for (Definition d : ALL) {
            index.computeIfAbsent(d.group(), g -> new HashMap<>()).put(d.key(), d);
        }
        Map<String, Map<String, Definition>> frozen = new HashMap<>();
        index.forEach((group, definitions) -> frozen.put(group, Map.copyOf(definitions)));
        return Map.copyOf(frozen);
    }

    private static Row row(String group, String key, String defaultProperty) {
        return new Row(group, key, List.of(group, key), defaultProperty);
    }

    /** A border side: no property of its own, the colour preset only, per-side handling. */
    private static Definition side(String side) {
        return new Row("border", side, List.of("border", side), null)
                .cssVar("color", COLOR_VAR)
                .valueFunc(ValueFunc.INDIVIDUAL_SIDES)
                .build();
    }

    /** Builds one table row. */
    private static final class Row {
        private final String group;
        private final String key;
        private final List<String> path;
        private final String defaultProperty;
        private String individual;
        private final Map<String, String> cssVars = new LinkedHashMap<>();
        private final List<ClassnameRule> classnames = new ArrayList<>();
        private ValueFunc valueFunc;

        Row(String group, String key, List<String> path, String defaultProperty) {
            this.group = group;
            this.key = key;
            this.path = path;
            this.defaultProperty = defaultProperty;
        }

        Row individual(String pattern) {
            individual = pattern;
            return this;
        }

        Row cssVar(String presetType, String pattern) {
            cssVars.put(presetType, pattern);
            return this;
        }

        Row always(String classname) {
            classnames.add(new ClassnameRule(classname, null));
            return this;
        }

        Row preset(String pattern, String presetType) {
            classnames.add(new ClassnameRule(pattern, presetType));
            return this;
        }

        Row valueFunc(ValueFunc func) {
            valueFunc = func;
            return this;
        }

        Definition build() {
            return new Definition(group, key, path, defaultProperty, individual, cssVars, classnames, valueFunc);
        }
    }
}
