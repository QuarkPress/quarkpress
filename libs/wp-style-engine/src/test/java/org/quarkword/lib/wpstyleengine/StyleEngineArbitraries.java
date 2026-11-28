package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.random.RandomGenerator;
import org.quarkword.testing.gen.Gen;

/**
 * Generators for the generated parity cases (Task 6): block styles over every group and key of the definitions table,
 * presets, raw, unsafe and wrongly typed values, CSS rules with colliding selectors and rules groups, declaration, rule
 * and store operations, processor inputs and get_styles calls into a context.
 *
 * <p>Every string is whole UTF-16 (an emoji is one piece), so no value holds a lone surrogate, which JSON could not carry
 * to PHP; nesting stays at depth 8 or less. Two shapes where WordPress stops with a fatal error are never generated:
 * <ul>
 *   <li>D7: under a strictly true {@code optimize}, every rule inside a rules group carries a custom property of its
 *       own ({@code --qw-g<n>: 1}), so a grouped rule never has the same declarations as another rule;
 *   <li>D3: store and context names are never {@code ""} or {@code "0"}; a selector that is empty after trimming (but
 *       not {@code ""} or {@code "0"} itself) never reaches store_css_rule or a context store; raw declarations given to
 *       store_css_rule are an array or empty.
 * </ul>
 * D2 is kept out too: an {@code important} option is always a boolean, and a rule's source declarations are never
 * changed after the rule exists.
 */
final class StyleEngineArbitraries {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private StyleEngineArbitraries() {}

    // ---- pieces

    private static final List<String> KEBAB_PIECES = List.of(
            "heavenly",
            "Blue",
            "XMLHttpRequest",
            "accent2Dark",
            "camelCase",
            "PascalCase",
            "UPPER",
            "lower",
            "x",
            "A",
            "aB",
            "0",
            "1",
            "42",
            "1st",
            "2nd",
            "3rd",
            "4th",
            "10px",
            "h1",
            "_",
            "-",
            ".",
            "'",
            "\u2019",
            " ",
            "  ",
            "--",
            "__",
            "\u00c4pfel",
            "Blau",
            "foo\u00c9tat",
            "\u00df",
            "\u00e9",
            "\u00ff",
            "\u0130",
            "\u03a9mega",
            "\u03b1\u03b2\u03b3",
            "\u0391\u0392",
            "\ufb01",
            "\u212a",
            "\u65e5\u672c",
            "\u8a9e",
            "\ud83d\ude00",
            "\ud83d\udc4d\ud83c\udffd",
            "don\u2019t",
            "it's",
            "snake_case",
            "kebab-case",
            "dot.case");

    private static final List<String> PRESET_TYPES = List.of(
            "color",
            "gradient",
            "dimension",
            "spacing",
            "border-radius",
            "shadow",
            "font-size",
            "font-family",
            "custom",
            "colour",
            "Color",
            "");

    private static final List<String> RAW = List.of(
            "red",
            "#000",
            "rgb(0,0,0)",
            "hsl(1,2%,3%)",
            "linear-gradient(red,blue)",
            "radial-gradient(rgb(1,2,3),var(--x))",
            "calc(1px + 2px)",
            "clamp(1px, var(--x), 2px)",
            "var(--wp--custom--x)",
            "url(https://e.com/a.png)",
            "1em",
            "0",
            "auto",
            "\"Helvetica Neue\", serif",
            "16/9");

    private static final List<String> UNSAFE = List.of(
            "red;position:fixed",
            "red}",
            "</style><script>x</script>",
            "/* c */",
            "re\\64",
            "a&b",
            "re\u0000d",
            "red\n\tblue",
            "url(javascript:alert(1))",
            "a.jpg');color:red;x:url('b",
            "data:image/png;base64,AA",
            ")");

    /** Realistic raw values by kind: what the editor writes into each group. */
    private static final Map<String, List<String>> REALISTIC = Map.of(
            "color",
            List.of("#fff", "#ff0000", "red", "transparent", "currentColor", "rgba(0,0,0,.5)", "var(--x)"),
            "len",
            List.of(
                    "0",
                    "1px",
                    "1.5rem",
                    "clamp(1rem, 2vw, 3rem)",
                    "calc(100% - 2px)",
                    "50vh",
                    "var(--wp--preset--spacing--50)",
                    "-1px",
                    "auto",
                    "1px 2px",
                    "0px"),
            "gradient",
            List.of(
                    "linear-gradient(135deg,rgba(6,147,227,1) 0%,rgb(155,81,224) 100%)",
                    "radial-gradient(circle, red, blue)", "none"),
            "font",
            List.of("\"Inter\", sans-serif", "Georgia, serif", "system-ui"),
            "misc",
            List.of(
                    "solid",
                    "dashed",
                    "none",
                    "cover",
                    "contain",
                    "50% 50%",
                    "no-repeat",
                    "fixed",
                    "uppercase",
                    "italic",
                    "700",
                    "1.5",
                    "0.1em",
                    "underline",
                    "vertical-rl",
                    "16/9",
                    "fill",
                    "2",
                    "0 0 10px rgba(0,0,0,.3)",
                    "inherit"));

    /** The preset types each kind of value uses in the editor. */
    private static final Map<String, List<String>> KIND_TYPES = Map.of(
            "color", List.of("color"),
            "len", List.of("spacing", "dimension", "font-size", "border-radius"),
            "gradient", List.of("gradient"),
            "font", List.of("font-family"),
            "misc", List.of("shadow", "font-size", "custom"));

    private static final List<String> SIDE_KEYS = List.of(
            "top",
            "right",
            "bottom",
            "left",
            "topLeft",
            "topRight",
            "bottomLeft",
            "bottomRight",
            "blockStart",
            "inlineEnd",
            "0",
            "1",
            "foo",
            "Top",
            "top-left");

    private static final List<String> SIDES = List.of("top", "right", "bottom", "left");
    private static final List<String> CORNERS = List.of("topLeft", "topRight", "bottomLeft", "bottomRight");
    private static final List<String> BORDER_SIDE_KEYS = List.of("color", "width", "style", "radius", "foo", "top");

    private static final List<String> URLS = List.of(
            "https://example.com/a.jpg",
            "a.jpg",
            "a b.png",
            "x') ;color:red;('",
            "a.jpg');color:red;x:url('b",
            "javascript:alert(1)",
            "data:image/png;base64,AA",
            "</style>",
            "",
            "0",
            "https://e.com/\u00e9.png");

    /** Selectors for rules: a small pool, so that keys collide. */
    private static final List<String> RULE_SELECTORS = List.of(
            ".a",
            ".b",
            " .a",
            ".a,.b",
            "",
            "0",
            "&:hover",
            ":is(.a, .b) p",
            ".a , .b",
            ".wp-container-core-group-is-layout-1 > *",
            ":root :where(.is-layout-flow) > *",
            ".wp-elements-1 a:where(:not(.wp-element-button))");

    private static final List<String> RULES_GROUPS =
            List.of("", "0", "@media print", " @media print ", ".parent", "@media (max-width: 600px)");

    private static final List<String> PROPERTIES = List.of(
            "color",
            "width",
            "background-image",
            "--x",
            "--MyVar",
            "Color",
            "min height",
            "0",
            "1",
            "behavior",
            "margin-block-start",
            "display",
            "gap",
            "padding-top");

    /** Store and context names; never "" or "0" (D3). */
    private static final List<String> STORE_NAMES = List.of("block-supports", "core", "c", "a b", "\u00e9", "1", " x ");

    // ---- small helpers

    private static <T> T pick(RandomGenerator r, List<T> xs) {
        return xs.get(r.nextInt(xs.size()));
    }

    private static boolean chance(RandomGenerator r, double p) {
        return r.nextDouble() < p;
    }

    /** {@code n} distinct elements of {@code xs} in random order. */
    private static <T> List<T> sample(RandomGenerator r, List<T> xs, int n) {
        List<T> copy = new ArrayList<>(xs);
        Collections.shuffle(copy, new java.util.Random(r.nextLong()));
        return copy.subList(0, Math.min(n, copy.size()));
    }

    /** Whether {@code s} is empty after PHP's trim but is neither {@code ""} nor {@code "0"} (the D3 shapes). */
    static boolean blankAfterTrim(String s) {
        if (s == null || s.isEmpty() || s.equals("0")) {
            return false;
        }
        String t = Php.trim(s);
        return t.isEmpty() || t.equals("0");
    }

    // ---- kebab, keys, safecss

    /** Text for _wp_to_kebab_case: case mixes, digits, punctuation, spaces, Latin-1, Greek, CJK and emoji. */
    static Gen<String> kebabInputs() {
        return r -> {
            int n = r.nextInt(7);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < n; i++) {
                sb.append(pick(r, KEBAB_PIECES));
            }
            return sb.toString();
        };
    }

    /** Text for sanitize_key: kebab inputs, CSS-ish property names and punctuation. */
    static Gen<String> sanitizeKeyInputs() {
        List<String> extra =
                List.of("position:fixed", "--My_Var", "min height", "F\u00f6nt-Size", "a;b", "<b>", "\t", "A-Z_0-9");
        Gen<String> kebab = kebabInputs();
        return r -> chance(r, 0.3) ? pick(r, extra) + kebab.next(r) : kebab.next(r);
    }

    /** {@code prop + (":" | ": ") + value} for the properties the table produces, border-top-radius and custom ones. */
    static Gen<String> safecssInputs() {
        List<String> properties = new ArrayList<>();
        for (Definition d : Definitions.all()) {
            if (d.defaultProperty() != null) {
                properties.add(d.defaultProperty());
            }
            if (d.individualPattern() != null) {
                for (String side : SIDES) {
                    properties.add(d.individualPattern().replace("%s", side));
                }
                for (String corner : List.of("top-left", "top-right", "bottom-left", "bottom-right")) {
                    properties.add(d.individualPattern().replace("%s", corner));
                }
            }
        }
        for (String side : SIDES) {
            for (String part : List.of("color", "width", "style")) {
                properties.add("border-" + side + "-" + part);
            }
        }
        properties.addAll(
                List.of("border-top-radius", "--wp--preset--color--x", "--x", "--MyVar", "behavior", "Color"));
        List<String> all = List.copyOf(properties);
        Gen<JsonNode> values = value();
        return r -> {
            StringBuilder sb = new StringBuilder();
            int n = chance(r, 0.2) ? 2 : 1;
            for (int i = 0; i < n; i++) {
                if (i > 0) {
                    sb.append(';');
                }
                JsonNode v = values.next(r);
                sb.append(pick(r, all))
                        .append(chance(r, 0.5) ? ":" : ": ")
                        .append(v.isTextual() ? v.asText() : v.toString());
            }
            return sb.toString();
        };
    }

    // ---- values

    private static String preset(RandomGenerator r, String type) {
        String slug =
                chance(r, 0.15) ? pick(r, List.of("", "0")) : kebabInputs().next(r);
        double f = r.nextDouble();
        if (f < 0.7) {
            return "var:preset|" + type + "|" + slug;
        }
        if (f < 0.8) {
            return "var:custom|" + type + "|" + slug;
        }
        return pick(
                r,
                List.of(
                        "var:preset|" + type + "|" + slug + "|extra",
                        "xvar:preset|" + type + "|" + slug,
                        " var:preset|" + type + "|" + slug,
                        "var:preset|" + type,
                        "var:preset|" + type + "|",
                        "var:",
                        "var:preset"));
    }

    private static JsonNode jsonShape(RandomGenerator r) {
        return switch (r.nextInt(12)) {
            case 0 -> NODES.numberNode(0);
            case 1 -> NODES.textNode("0");
            case 2 -> NODES.textNode("");
            case 3 -> NODES.textNode(" ");
            case 4 -> NODES.numberNode(5);
            case 5 -> NODES.numberNode(1.5);
            case 6 -> NODES.booleanNode(true);
            case 7 -> NODES.booleanNode(false);
            case 8 -> NODES.nullNode();
            case 9 -> NODES.arrayNode();
            case 10 -> NODES.objectNode();
            default -> {
                ObjectNode nested = NODES.objectNode();
                nested.put("a", "b");
                nested.putObject("c").put("d", 1);
                yield nested;
            }
        };
    }

    /** A value of {@code kind} as the editor writes it, or a preset, raw, unsafe or wrongly typed value. */
    private static JsonNode value(RandomGenerator r, String kind) {
        double f = r.nextDouble();
        if (kind != null && f < 0.35) {
            return NODES.textNode(pick(r, REALISTIC.get(kind)));
        }
        if (f < 0.6) {
            String type = kind != null && chance(r, 0.7) ? pick(r, KIND_TYPES.get(kind)) : pick(r, PRESET_TYPES);
            return NODES.textNode(preset(r, type));
        }
        if (f < 0.75) {
            return NODES.textNode(pick(r, RAW));
        }
        if (f < 0.88) {
            return NODES.textNode(pick(r, UNSAFE));
        }
        return jsonShape(r);
    }

    /** Presets of every type, raw values, unsafe values and JSON shapes. */
    static Gen<JsonNode> value() {
        return r -> value(r, null);
    }

    // ---- block styles

    /** A scalar, a side map over the side keys, or a list. */
    private static JsonNode sides(RandomGenerator r, String kind, List<String> keys) {
        double f = r.nextDouble();
        if (f < 0.35) {
            return value(r, kind);
        }
        ObjectNode m = NODES.objectNode();
        for (String k : sample(r, keys, r.nextInt(keys.size() + 1))) {
            m.set(k, value(r, kind));
        }
        if (chance(r, 0.25)) {
            m.set(pick(r, SIDE_KEYS), value(r, kind));
        }
        if (f > 0.95) {
            ArrayNode list = NODES.arrayNode();
            m.forEach(list::add);
            return list;
        }
        return m;
    }

    private static JsonNode borderSide(RandomGenerator r) {
        double f = r.nextDouble();
        if (f < 0.1) {
            return value(r, "color");
        }
        ObjectNode m = NODES.objectNode();
        for (String k : sample(r, BORDER_SIDE_KEYS, r.nextInt(5))) {
            String kind = switch (k) {
                case "color" -> "color";
                case "width", "radius" -> "len";
                default -> "misc";
            };
            m.set(k, k.equals("radius") && chance(r, 0.3) ? sides(r, "len", CORNERS) : value(r, kind));
        }
        return m;
    }

    private static JsonNode backgroundImage(RandomGenerator r) {
        double f = r.nextDouble();
        if (f < 0.55) {
            ObjectNode m = NODES.objectNode();
            if (chance(r, 0.9)) {
                m.set("url", chance(r, 0.85) ? NODES.textNode(pick(r, URLS)) : jsonShape(r));
            }
            if (chance(r, 0.5)) {
                m.put("id", r.nextInt(100));
            }
            if (chance(r, 0.2)) {
                m.put("source", "file").put("title", "t");
            }
            return m;
        }
        if (f < 0.8) {
            return NODES.textNode(pick(
                    r,
                    List.of(
                            "url(a.png)",
                            "none",
                            "linear-gradient(red,blue)",
                            preset(r, "gradient"),
                            preset(r, "color"),
                            "url('a.jpg');color:red")));
        }
        return value(r, null);
    }

    /** Puts {@code key} with probability {@code p}. */
    private static void maybe(RandomGenerator r, ObjectNode o, double p, String key, Gen<JsonNode> v) {
        if (chance(r, p)) {
            o.set(key, v.next(r));
        }
    }

    private static ObjectNode shuffled(RandomGenerator r, ObjectNode o) {
        List<Map.Entry<String, JsonNode>> entries = new ArrayList<>(o.properties());
        Collections.shuffle(entries, new java.util.Random(r.nextLong()));
        ObjectNode out = NODES.objectNode();
        entries.forEach(e -> out.set(e.getKey(), e.getValue()));
        return out;
    }

    private static JsonNode group(RandomGenerator r, String name) {
        if (chance(r, 0.04)) {
            return value(r, null);
        }
        ObjectNode g = NODES.objectNode();
        switch (name) {
            case "color" -> {
                maybe(r, g, 0.6, "text", x -> value(x, "color"));
                maybe(r, g, 0.5, "background", x -> value(x, "color"));
                maybe(r, g, 0.35, "gradient", x -> value(x, "gradient"));
                maybe(r, g, 0.1, "link", x -> value(x, "color"));
                maybe(r, g, 0.05, "Text", x -> value(x, "color"));
            }
            case "background" -> {
                maybe(r, g, 0.6, "backgroundImage", StyleEngineArbitraries::backgroundImage);
                maybe(r, g, 0.3, "backgroundPosition", x -> value(x, "misc"));
                maybe(r, g, 0.3, "backgroundRepeat", x -> value(x, "misc"));
                maybe(r, g, 0.3, "backgroundSize", x -> value(x, "misc"));
                maybe(r, g, 0.2, "backgroundAttachment", x -> value(x, "misc"));
                maybe(r, g, 0.3, "gradient", x -> value(x, "gradient"));
            }
            case "border" -> {
                maybe(r, g, 0.4, "color", x -> chance(x, 0.3) ? sides(x, "color", SIDE_KEYS) : value(x, "color"));
                maybe(r, g, 0.4, "radius", x -> chance(x, 0.5) ? sides(x, "len", SIDE_KEYS) : value(x, "len"));
                maybe(r, g, 0.4, "style", x -> chance(x, 0.3) ? sides(x, "misc", SIDE_KEYS) : value(x, "misc"));
                maybe(r, g, 0.4, "width", x -> chance(x, 0.3) ? sides(x, "len", SIDE_KEYS) : value(x, "len"));
                for (String side : SIDES) {
                    maybe(r, g, 0.3, side, StyleEngineArbitraries::borderSide);
                }
            }
            case "dimensions" -> {
                maybe(r, g, 0.3, "aspectRatio", x -> value(x, "misc"));
                maybe(r, g, 0.3, "height", x -> value(x, "len"));
                maybe(r, g, 0.3, "minHeight", x -> value(x, "len"));
                maybe(r, g, 0.3, "minWidth", x -> value(x, "len"));
                maybe(r, g, 0.2, "objectFit", x -> value(x, "misc"));
                maybe(r, g, 0.3, "width", x -> value(x, "len"));
                maybe(r, g, 0.1, "maxWidth", x -> value(x, "len"));
            }
            case "spacing" -> {
                maybe(r, g, 0.6, "padding", x -> sides(x, "len", SIDES));
                maybe(r, g, 0.5, "margin", x -> sides(x, "len", SIDES));
                maybe(r, g, 0.2, "blockGap", x -> sides(x, "len", SIDES));
            }
            case "typography" -> {
                maybe(r, g, 0.4, "fontSize", x -> value(x, "len"));
                maybe(r, g, 0.3, "fontFamily", x -> value(x, "font"));
                for (String k : List.of(
                        "fontStyle",
                        "fontWeight",
                        "lineHeight",
                        "textColumns",
                        "textDecoration",
                        "textIndent",
                        "textTransform",
                        "letterSpacing",
                        "writingMode")) {
                    maybe(r, g, 0.2, k, x -> value(x, "misc"));
                }
                maybe(r, g, 0.1, "textAlign", x -> value(x, "misc"));
            }
            case "elements" -> {
                ObjectNode link = g.putObject("link");
                link.putObject("color").set("text", value(r, "color"));
                if (chance(r, 0.3)) {
                    g.putObject("button").putObject("color").set("background", value(r, "color"));
                }
            }
            case "filter" -> g.set("duotone", value(r, null));
            default -> throw new IllegalArgumentException(name);
        }
        return shuffled(r, g);
    }

    /** Block style objects over every group and key of the table, the ignored keys, and non-object top levels. */
    static Gen<JsonNode> blockStyles() {
        return r -> {
            double f = r.nextDouble();
            if (f < 0.03) {
                return pick(
                        r,
                        List.<JsonNode>of(
                                NODES.nullNode(),
                                NODES.textNode("color:red"),
                                NODES.arrayNode().add("red"),
                                NODES.objectNode()));
            }
            ObjectNode s = NODES.objectNode();
            maybe(r, s, 0.55, "color", x -> group(x, "color"));
            maybe(r, s, 0.35, "background", x -> group(x, "background"));
            maybe(r, s, 0.45, "border", x -> group(x, "border"));
            maybe(r, s, 0.25, "shadow", x -> value(x, "misc"));
            maybe(r, s, 0.3, "dimensions", x -> group(x, "dimensions"));
            maybe(r, s, 0.45, "spacing", x -> group(x, "spacing"));
            maybe(r, s, 0.45, "typography", x -> group(x, "typography"));
            maybe(r, s, 0.1, "elements", x -> group(x, "elements"));
            maybe(r, s, 0.05, "filter", x -> group(x, "filter"));
            return shuffled(r, s);
        };
    }

    /** get_styles options: selector and convert_vars_to_classnames, each sometimes absent. */
    static Gen<ObjectNode> styleOptions() {
        return r -> {
            ObjectNode o = NODES.objectNode();
            switch (r.nextInt(9)) {
                case 0 -> o.put("selector", ".a");
                case 1 -> o.put("selector", ".a , .b");
                case 2 -> o.put("selector", 0);
                case 3 -> o.put("selector", "");
                case 4 -> o.put("selector", "  .x  ");
                case 5 -> o.put("selector", "</style>");
                case 6 -> o.put("selector", ".wp-elements-1 a:where(:not(.wp-element-button))");
                default -> {}
            }
            switch (r.nextInt(5)) {
                case 0 -> o.put("convert_vars_to_classnames", true);
                case 1 -> o.put("convert_vars_to_classnames", "1");
                case 2 -> o.put("convert_vars_to_classnames", false);
                default -> {}
            }
            return o;
        };
    }

    // ---- rules

    /** A raw declarations map of 0\u20135 entries over the property pool. */
    private static ObjectNode declarationsMap(RandomGenerator r, int max) {
        ObjectNode m = NODES.objectNode();
        int n = r.nextInt(max + 1);
        for (int i = 0; i < n; i++) {
            String p = pick(r, PROPERTIES);
            m.set(
                    p,
                    chance(r, 0.5)
                            ? NODES.textNode(pick(r, REALISTIC.get(chance(r, 0.5) ? "color" : "len")))
                            : value(r, null));
        }
        return m;
    }

    /** A pool of declarations maps that a case's rules draw from, so that identical declarations are frequent. */
    private static List<ObjectNode> declarationsPool(RandomGenerator r) {
        List<ObjectNode> pool = new ArrayList<>();
        int n = 1 + r.nextInt(3);
        for (int i = 0; i < n; i++) {
            pool.add(declarationsMap(r, 3));
        }
        return pool;
    }

    private static ObjectNode drawDeclarations(RandomGenerator r, List<ObjectNode> pool) {
        return chance(r, 0.65) ? pool.get(r.nextInt(pool.size())).deepCopy() : declarationsMap(r, 5);
    }

    /** The text-valued properties of {@code map}, each with probability 1/2: the important list of an object form. */
    private static ArrayNode importantSubset(RandomGenerator r, ObjectNode map) {
        ArrayNode list = NODES.arrayNode();
        map.properties().forEach(e -> {
            if (e.getValue().isTextual() && chance(r, 0.5)) {
                list.add(e.getKey());
            }
        });
        return list;
    }

    /** A counter for the D7 markers of one case. */
    private static final class Marker {
        private int next;

        void mark(ObjectNode declarations) {
            declarations.put("--qw-g" + next++, "1");
        }
    }

    /**
     * stylesheet_from_rules entries. With {@code optimize} (strictly true), grouped rules carry a marker (D7); with
     * {@code context}, no selector is empty after trimming (D3).
     */
    static JsonNode cssRules(RandomGenerator r, boolean optimize, boolean context) {
        ArrayNode entries = NODES.arrayNode();
        List<ObjectNode> pool = declarationsPool(r);
        Marker marker = new Marker();
        int n = r.nextInt(13);
        for (int i = 0; i < n; i++) {
            ObjectNode e = NODES.objectNode();
            if (chance(r, 0.95)) {
                String selector = pick(r, RULE_SELECTORS);
                if (!context && chance(r, 0.03)) {
                    selector = pick(r, List.of(" ", "\t", " 0 "));
                }
                e.put("selector", selector);
            }
            String group = chance(r, 0.4) ? pick(r, RULES_GROUPS) : null;
            if (group != null) {
                e.put("rules_group", group);
            }
            double f = r.nextDouble();
            if (f < 0.75 || (optimize && group != null)) {
                ObjectNode d = drawDeclarations(r, pool);
                if (optimize && group != null) {
                    marker.mark(d);
                }
                e.set("declarations", d);
                if (chance(r, 0.2)) {
                    e.put("object", true);
                    e.set("important", importantSubset(r, d));
                }
            } else if (f < 0.9) {
                e.set(
                        "declarations",
                        pick(
                                r,
                                List.<JsonNode>of(
                                        NODES.textNode("color:red"),
                                        NODES.nullNode(),
                                        NODES.arrayNode(),
                                        NODES.arrayNode().add("red"),
                                        NODES.booleanNode(true),
                                        NODES.numberNode(0))));
            }
            // otherwise no declarations key
            entries.add(e);
        }
        return entries;
    }

    /** stylesheet_from_rules entries with any optimize. */
    static Gen<JsonNode> cssRules() {
        return r -> cssRules(r, chance(r, 0.5), false);
    }

    /** optimize absent, true, 1 or false; prettify absent, true, "yes" or false. */
    static Gen<ObjectNode> rulesArgs() {
        return r -> {
            ObjectNode a = NODES.objectNode();
            switch (r.nextInt(5)) {
                case 0, 1 -> a.put("optimize", true);
                case 2 -> a.put("optimize", 1);
                case 3 -> a.put("optimize", false);
                default -> {}
            }
            switch (r.nextInt(4)) {
                case 0 -> a.put("prettify", true);
                case 1 -> a.put("prettify", "yes");
                case 2 -> a.put("prettify", false);
                default -> {}
            }
            return a;
        };
    }

    static boolean strictOptimize(JsonNode args) {
        JsonNode o = args.get("optimize");
        return o != null && o.isBoolean() && o.asBoolean();
    }

    /** {"in", "args"} for stylesheet_from_rules: a quarter of the cases also store into a context. */
    static Gen<ObjectNode> stylesheetFromRulesCases() {
        Gen<ObjectNode> args = rulesArgs();
        return r -> {
            ObjectNode a = args.next(r);
            boolean context = chance(r, 0.25);
            if (context) {
                a.put("context", pick(r, STORE_NAMES));
            }
            ObjectNode c = NODES.objectNode();
            c.set("in", cssRules(r, strictOptimize(a), context));
            c.set("args", a);
            return c;
        };
    }

    // ---- declarations, rules, stores, processor

    /** {"init", "ops"} for the declarations fn: add (with a boolean important), addAll, remove and removeAll. */
    static Gen<JsonNode> declarationOps() {
        return r -> {
            ObjectNode in = NODES.objectNode();
            in.set("init", chance(r, 0.05) ? NODES.arrayNode().add("red").add("blue") : declarationsMap(r, 5));
            ArrayNode ops = in.putArray("ops");
            int n = r.nextInt(7);
            for (int i = 0; i < n; i++) {
                switch (r.nextInt(6)) {
                    case 0, 1 -> {
                        ArrayNode op = ops.addArray().add("add").add(pick(r, PROPERTIES));
                        op.add(value(r, chance(r, 0.5) ? "color" : null));
                        if (chance(r, 0.5)) {
                            op.add(r.nextBoolean());
                        }
                    }
                    case 2 -> {
                        ObjectNode map = declarationsMap(r, 4);
                        ObjectNode arg = ops.addArray().add("addAll").addObject();
                        arg.set("map", map);
                        arg.set("important", importantSubset(r, map));
                    }
                    case 3, 4 -> ops.addArray().add("remove").add(pick(r, PROPERTIES));
                    default -> {
                        ArrayNode list = ops.addArray().add("removeAll").addArray();
                        for (String p : sample(r, PROPERTIES, r.nextInt(4))) {
                            list.add(p);
                        }
                    }
                }
            }
            return in;
        };
    }

    /** {"selector", "declarations", "rules_group"?, "object"?, "important"?, "ops"?} for the rule fn. */
    static Gen<JsonNode> ruleSpecs() {
        List<String> selectors = new ArrayList<>(RULE_SELECTORS);
        selectors.addAll(List.of(" ", "\t.a\n", ".a,", ",", ".a,,.b", "</style>", "h1,h2"));
        return r -> {
            ObjectNode in = NODES.objectNode();
            in.put("selector", pick(r, selectors));
            ObjectNode d = declarationsMap(r, 5);
            in.set("declarations", d);
            if (chance(r, 0.4)) {
                in.put("rules_group", pick(r, RULES_GROUPS));
            }
            if (chance(r, 0.3)) {
                in.put("object", true);
                in.set("important", importantSubset(r, d));
            }
            if (chance(r, 0.4)) {
                ArrayNode ops = in.putArray("ops");
                int n = 1 + r.nextInt(3);
                for (int i = 0; i < n; i++) {
                    ObjectNode map = declarationsMap(r, 3);
                    ArrayNode op = ops.addArray().add("add").add(map);
                    if (chance(r, 0.5)) {
                        op.add(importantSubset(r, map));
                    }
                }
            }
            return in;
        };
    }

    private static String storeSelector(RandomGenerator r) {
        return pick(
                r,
                List.of(
                        ".a",
                        ".b",
                        " .a ",
                        ".a,.b",
                        "  ",
                        " 0 ",
                        "&:hover",
                        ".wp-container-core-group-is-layout-1 > *",
                        ".x .y",
                        "h1,h2"));
    }

    /**
     * Store ops over the names {@code names}: add_rule, store_css_rule, remove_rule and get_store. With {@code optimize},
     * grouped rules carry a marker (D7); store_css_rule never gets a selector that is empty after trimming or
     * non-array raw declarations (D3).
     */
    static ArrayNode storeOps(RandomGenerator r, List<String> names, boolean optimize) {
        ArrayNode ops = NODES.arrayNode();
        List<ObjectNode> pool = declarationsPool(r);
        Marker marker = new Marker();
        int n = r.nextInt(13);
        for (int i = 0; i < n; i++) {
            String name = pick(r, names);
            double f = r.nextDouble();
            if (f < 0.5) {
                ArrayNode op = ops.addArray().add("add_rule").add(name).add(storeSelector(r));
                String group = chance(r, 0.4) ? pick(r, RULES_GROUPS) : null;
                op.add(group == null ? NODES.nullNode() : NODES.textNode(group));
                ObjectNode d = drawDeclarations(r, pool);
                if (optimize && group != null) {
                    marker.mark(d);
                }
                op.add(chance(r, 0.9) || (optimize && group != null) ? d : NODES.nullNode());
            } else if (f < 0.75) {
                String selector = chance(r, 0.1) ? null : storeSelector(r);
                if (blankAfterTrim(selector)) {
                    selector = ".a";
                }
                String group = pick(r, RULES_GROUPS);
                JsonNode d;
                double g = r.nextDouble();
                if (g < 0.8 || optimize) {
                    ObjectNode m = drawDeclarations(r, pool);
                    if (optimize) {
                        marker.mark(m);
                    }
                    d = m;
                } else {
                    d = pick(r, List.<JsonNode>of(NODES.arrayNode(), NODES.nullNode(), NODES.textNode("")));
                }
                ArrayNode op = ops.addArray().add("store_css_rule").add(name);
                op.add(selector == null ? NODES.nullNode() : NODES.textNode(selector));
                op.add(d).add(group);
            } else if (f < 0.88) {
                ops.addArray()
                        .add("remove_rule")
                        .add(name)
                        .add(pick(r, List.of(".a", ".b", "@media print .a", ".a ", ".parent .a")));
            } else {
                ops.addArray().add("get_store").add(name);
            }
        }
        return ops;
    }

    /** Store ops for the store fn. */
    static Gen<JsonNode> storeOps() {
        return r -> storeOps(r, sample(r, STORE_NAMES, 1 + r.nextInt(3)), false);
    }

    /** {"in": store ops, "args": rulesArgs() + context} for stylesheet_from_context. */
    static Gen<ObjectNode> stylesheetFromContextCases() {
        Gen<ObjectNode> args = rulesArgs();
        return r -> {
            ObjectNode a = args.next(r);
            List<String> names = sample(r, STORE_NAMES, 1 + r.nextInt(3));
            String context = chance(r, 0.9) ? names.get(0) : pick(r, STORE_NAMES);
            a.put("context", context);
            // the marker applies to every store: any of them may be the context
            ObjectNode c = NODES.objectNode();
            c.set("in", storeOps(r, names, strictOptimize(a)));
            c.set("args", a);
            return c;
        };
    }

    /**
     * Processor inputs: stores filled with rules, the stores added in a random order (some twice), direct rules and
     * get_css calls. Two calls only without stores and without a strictly true optimize (D6).
     */
    static Gen<JsonNode> processorInputs() {
        List<String> selectors = new ArrayList<>(RULE_SELECTORS);
        selectors.addAll(List.of("  ", " 0 ", ".x .y", "h1,h2"));
        return r -> {
            ObjectNode call = NODES.objectNode();
            switch (r.nextInt(4)) {
                case 0 -> call.put("optimize", true);
                case 1 -> call.put("optimize", 1);
                case 2 -> call.put("optimize", false);
                default -> {}
            }
            switch (r.nextInt(4)) {
                case 0 -> call.put("prettify", true);
                case 1 -> call.put("prettify", "yes");
                case 2 -> call.put("prettify", 0);
                default -> {}
            }
            boolean optimize = strictOptimize(call);
            List<ObjectNode> pool = declarationsPool(r);
            Marker marker = new Marker();
            ObjectNode in = NODES.objectNode();
            ArrayNode stores = in.putArray("stores");
            List<String> names = sample(r, List.of("a", "b", "core"), r.nextInt(3));
            for (String name : names) {
                ObjectNode s = stores.addObject().put("name", name);
                ArrayNode rules = s.putArray("rules");
                int n = r.nextInt(6);
                for (int i = 0; i < n; i++) {
                    String group = chance(r, 0.4) ? pick(r, RULES_GROUPS) : "";
                    ObjectNode d = drawDeclarations(r, pool);
                    if (optimize && !group.isEmpty()) {
                        marker.mark(d);
                    }
                    rules.addArray().add(pick(r, selectors)).add(group).add(d);
                }
            }
            ArrayNode add = in.putArray("addStores");
            List<String> order = new ArrayList<>();
            for (String name : names) {
                int times = 1 + r.nextInt(2);
                for (int i = 0; i < times; i++) {
                    order.add(name);
                }
            }
            Collections.shuffle(order, new java.util.Random(r.nextLong()));
            order.forEach(add::add);
            ArrayNode direct = in.putArray("direct");
            int n = r.nextInt(7);
            for (int i = 0; i < n; i++) {
                ObjectNode spec = direct.addObject().put("selector", pick(r, selectors));
                ObjectNode d = drawDeclarations(r, pool);
                String group = chance(r, 0.4) ? pick(r, RULES_GROUPS) : null;
                if (group != null) {
                    spec.put("rules_group", group);
                    if (optimize) {
                        marker.mark(d);
                    }
                }
                spec.set("declarations", d);
                if (chance(r, 0.25)) {
                    spec.set("important", importantSubset(r, d));
                }
            }
            ArrayNode calls = in.putArray("calls");
            calls.add(call);
            if (names.isEmpty() && !optimize && chance(r, 0.3)) {
                calls.add(call.deepCopy());
            }
            return in;
        };
    }

    /**
     * get_styles calls into a context store: {"calls": [[styles, options]], "context"}. Options hold a selector that
     * is never empty after trimming unless it is "" or "0" (D3), and sometimes their own context or none.
     */
    static Gen<JsonNode> contextCalls() {
        List<String> selectors = List.of(
                ".x",
                ".y",
                " .x",
                "\t.x\n",
                ".a , .b",
                ".a,.b",
                ".wp-container-1 > *",
                "</style>",
                "a,b,",
                "",
                "0",
                ".z",
                "h1");
        Gen<JsonNode> styles = blockStyles();
        Gen<ObjectNode> styleOptions = styleOptions();
        return r -> {
            ObjectNode in = NODES.objectNode();
            ArrayNode calls = in.putArray("calls");
            int n = 1 + r.nextInt(8);
            for (int i = 0; i < n; i++) {
                ObjectNode o = styleOptions.next(r);
                JsonNode selector = o.get("selector");
                if (selector == null || chance(r, 0.5)) {
                    o.put("selector", pick(r, selectors));
                } else if (selector.isTextual() && blankAfterTrim(selector.asText())) {
                    o.put("selector", ".x");
                }
                if (chance(r, 0.1)) {
                    o.remove("selector");
                }
                if (chance(r, 0.1)) {
                    o.put("noContext", true);
                } else if (chance(r, 0.15)) {
                    o.put("context", pick(r, STORE_NAMES));
                }
                calls.addArray().add(styles.next(r)).add(o);
            }
            in.put("context", pick(r, STORE_NAMES));
            return in;
        };
    }
}
