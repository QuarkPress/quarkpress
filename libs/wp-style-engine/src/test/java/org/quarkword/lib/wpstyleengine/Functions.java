package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.quarkword.lib.wpkses.Kses;

/** QuarkWord's answer for a case, in the JSON shape the php driver returns for the same {@code fn}. */
final class Functions {

    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final Kses KSES = Kses.wordPress();

    private Functions() {}

    static JsonNode ours(String fn, JsonNode in, JsonNode args) {
        return switch (fn) {
            // Task 1
            case "kebab" -> NODES.textNode(KebabCase.of(in.asText()));
            case "sanitize_key" -> NODES.textNode(Keys.sanitizeKey(in.asText()));
            case "protected" -> protectedHelper(in.get("name").asText(), in.get("args"));
            // Task 2
            case "declarations" -> declarations(in);
            case "rule" -> rule(in);
            case "store" -> store(in);
            case "compile_css" -> NODES.textNode(StyleEngine.compileCss(in.get(0), textOrNull(in.get(1))));
            case "safecss" -> NODES.textNode(KSES.safeCss(in.asText()));
            // Task 3
            case "processor" -> Task3.processor(in);
            case "stylesheet_from_rules" ->
                Php.get(args, "context") != null
                        ? Task5.stylesheetFromRules(in, args)
                        : Task3.stylesheetFromRules(in, args);
            // Task 4
            case "get_styles" -> Task4.getStyles(in, args);
            // Task 5
            case "styles_then_context" -> Task5.stylesThenContext(in, args);
            case "stylesheet_from_context" -> Task5.stylesheetFromContext(in, args);
            default -> throw new IllegalArgumentException("unknown fn " + fn);
        };
    }

    /** Every {@code fn} that {@link #ours} dispatches; each task adds its own. */
    static final Set<String> FNS = Set.of(
            // Task 1
            "kebab",
            "sanitize_key",
            "protected",
            // Task 2
            "declarations",
            "rule",
            "store",
            "compile_css",
            "safecss",
            // Task 3
            "processor",
            "stylesheet_from_rules",
            // Task 4
            "get_styles",
            // Task 5
            "styles_then_context",
            "stylesheet_from_context");

    /** A protected WordPress helper, or a driver-local one, by the name the driver maps it to. */
    private static JsonNode protectedHelper(String name, JsonNode args) {
        return switch (name) {
            // Task 1
            case "is_valid_style_value" -> NODES.booleanNode(Php.isValidStyleValue(args.get(0)));
            case "php_string" -> NODES.textNode(Php.toPhpString(args.get(0)));
            // Task 4
            case "get_slug_from_preset_value",
                    "get_css_var_value",
                    "get_classnames",
                    "get_url_or_value_css_declaration" -> Task4.protectedHelper(name, args);
            default -> throw new IllegalArgumentException("unknown protected helper " + name);
        };
    }

    // Task 2: declarations, rules, stores

    private static String textOrNull(JsonNode v) {
        return v == null || v.isNull() ? null : v.asText();
    }

    /** The declarations the driver builds from a raw map, with the listed properties added again as important. */
    private static CssDeclarations object(JsonNode map, JsonNode important) {
        CssDeclarations.Builder builder = CssDeclarations.fromJson(map).toBuilder();
        if (important != null) {
            for (JsonNode property : important) {
                builder.add(property.asText(), Php.get(map, property.asText()).asText(), true);
            }
        }
        return builder.build();
    }

    private static JsonNode declarations(JsonNode in) {
        CssDeclarations d = CssDeclarations.fromJson(in.get("init"));
        for (JsonNode op : in.path("ops")) {
            d = switch (op.get(0).asText()) {
                case "add" ->
                    op.get(2).isTextual()
                            ? (op.size() > 3
                                    ? d.with(op.get(1).asText(), op.get(2).asText(), Php.isStrictTrue(op.get(3)))
                                    : d.with(op.get(1).asText(), op.get(2).asText()))
                            : d;
                case "addAll" ->
                    d.withAll(object(op.get(1).get("map"), op.get(1).get("important")));
                case "remove" -> d.without(op.get(1).asText());
                case "removeAll" -> {
                    List<String> properties = new ArrayList<>();
                    op.get(1).forEach(p -> properties.add(p.asText()));
                    yield d.withoutAll(properties);
                }
                default -> throw new IllegalArgumentException("unknown declarations op " + op);
            };
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("declarations", PhpJson.map(d.declarations()));
        out.put("important", PhpJson.list(List.copyOf(d.important())));
        out.put(
                "css",
                PhpJson.list(
                        List.of(d.toCss(), d.toCss(true, 0), d.toCss(true, 1), d.toCss(true, 2), d.toCss(false, 3))));
        return PhpJson.map(out);
    }

    private static JsonNode rule(JsonNode in) {
        JsonNode raw = in.get("declarations");
        CssDeclarations declarations =
                in.path("object").asBoolean() ? object(raw, in.get("important")) : CssDeclarations.fromJson(raw);
        CssRule r = CssRule.of(in.get("selector").asText(), declarations, textOrNull(in.get("rules_group")));
        for (JsonNode op : in.path("ops")) {
            switch (op.get(0).asText()) {
                case "add" ->
                    r = r.withDeclarations(
                            op.has(2) ? object(op.get(1), op.get(2)) : CssDeclarations.fromJson(op.get(1)));
                // declarations are values: changing the source after the rule exists leaves the rule as it is
                case "mutateSource" -> {}
                default -> throw new IllegalArgumentException("unknown rule op " + op);
            }
        }
        return PhpJson.list(List.of(r.toCss(), r.toCss(true, 0), r.toCss(true, 1), r.toCss(true, 2)));
    }

    private static JsonNode store(JsonNode ops) {
        StyleStores stores = new StyleStores();
        List<Object> nulls = storeOps(stores, ops);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nulls", nulls);
        out.put("stores", stores.names());
        out.put("rules", dumpRules(stores));
        return PhpJson.map(out);
    }

    /** Runs the store ops on {@code stores}; for each add_rule, whether WordPress's add_rule returns null. */
    private static List<Object> storeOps(StyleStores stores, JsonNode ops) {
        List<Object> nulls = new ArrayList<>();
        for (JsonNode op : ops) {
            String name = op.get(1).asText();
            switch (op.get(0).asText()) {
                case "get_store" -> stores.store(name);
                case "add_rule" -> {
                    StyleStore store = stores.store(name);
                    JsonNode raw = op.get(4);
                    try {
                        store.addRule(
                                op.get(2).asText(),
                                textOrNull(op.get(3)),
                                raw == null || raw.isNull() ? CssDeclarations.empty() : CssDeclarations.fromJson(raw));
                        nulls.add(false);
                    } catch (IllegalArgumentException e) {
                        nulls.add(true);
                    }
                }
                case "remove_rule" -> stores.store(name).removeRule(op.get(2).asText());
                case "store_css_rule" -> {
                    // WordPress checks the store name, the selector and the declarations before it fetches the store
                    String selector = textOrNull(op.get(2));
                    if (!Php.isEmpty(name) && !Php.isEmpty(selector) && !Php.isEmpty(op.get(3))) {
                        StyleEngine.storeCssRule(stores.store(name), selector, op.get(3), null, textOrNull(op.get(4)));
                    }
                }
                default -> throw new IllegalArgumentException("unknown store op " + op);
            }
        }
        return nulls;
    }

    /** Every store's rules: name to [[key, selector, rules group, declarations, important]]. */
    private static JsonNode dumpRules(StyleStores stores) {
        Map<String, Object> rules = new LinkedHashMap<>();
        for (String name : stores.names()) {
            List<Object> list = new ArrayList<>();
            stores.store(name)
                    .rules()
                    .forEach((key, rule) -> list.add(List.of(
                            key,
                            rule.selector(),
                            rule.rulesGroup(),
                            PhpJson.map(rule.declarations().declarations()),
                            List.copyOf(rule.declarations().important()))));
            rules.put(name, list);
        }
        return PhpJson.map(rules);
    }

    // Task 4: get_styles and its protected helpers
    private static final class Task4 {

        private Task4() {}

        /** WordPress's result array: {@code []} when empty, otherwise the keys it keeps. */
        static JsonNode getStyles(JsonNode in, JsonNode args) {
            return result(StyleEngine.getStyles(in, options(args == null ? null : args.get("options"))));
        }

        /** The options the driver passes: a text selector, and convert_vars_to_classnames when strictly true. */
        static StyleOptions options(JsonNode options) {
            StyleOptions o = StyleOptions.defaults();
            if (Php.isArray(options)) {
                JsonNode selector = Php.get(options, "selector");
                if (selector != null && selector.isTextual()) {
                    o = o.withSelector(selector.asText());
                }
                o = o.withConvertVarsToClassnames(Php.isStrictTrue(Php.get(options, "convert_vars_to_classnames")));
            }
            return o;
        }

        static JsonNode result(StyleResult r) {
            if (r.isEmpty()) {
                return NODES.arrayNode();
            }
            ObjectNode out = NODES.objectNode();
            if (!r.css().isEmpty()) {
                out.put("css", r.css());
            }
            if (!r.declarations().isEmpty()) {
                out.set("declarations", declarationsJson(r.declarations()));
            }
            if (!r.classnames().isEmpty()) {
                out.put("classnames", r.classnames());
            }
            return out;
        }

        static JsonNode protectedHelper(String name, JsonNode args) {
            return switch (name) {
                case "get_slug_from_preset_value" ->
                    NODES.textNode(Presets.slug(
                            args.get(0), args.get(1).isTextual() ? args.get(1).asText() : null));
                case "get_css_var_value" -> NODES.textNode(Presets.cssVarValue(args.get(0), textMap(args.get(1))));
                case "get_classnames" -> PhpJson.list(BlockStyles.classnames(args.get(0), definition(args.get(1))));
                case "get_url_or_value_css_declaration" ->
                    declarationsJson(BlockStyles.urlOrValue(args.get(0), definition(args.get(1))));
                default -> throw new IllegalArgumentException("unknown protected helper " + name);
            };
        }

        /** A row of the table ({"$def": "group.key"}), or a raw PHP definition array. */
        private static Definition definition(JsonNode arg) {
            JsonNode def = Php.get(arg, "$def");
            if (def != null && def.isTextual() && arg.size() == 1) {
                String name = def.asText();
                int dot = name.indexOf('.');
                return Definitions.find(name.substring(0, dot), name.substring(dot + 1))
                        .orElseThrow();
            }
            List<String> path = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : Php.entries(arg.path("path"))) {
                path.add(e.getValue().asText());
            }
            JsonNode keys = arg.path("property_keys");
            List<ClassnameRule> classnames = new ArrayList<>();
            for (Map.Entry<String, JsonNode> e : Php.entries(arg.path("classnames"))) {
                classnames.add(new ClassnameRule(
                        e.getKey(), e.getValue().isTextual() ? e.getValue().asText() : null));
            }
            return new Definition(
                    "",
                    "",
                    path,
                    keys.hasNonNull("default") ? keys.get("default").asText() : null,
                    keys.hasNonNull("individual") ? keys.get("individual").asText() : null,
                    textMap(arg.path("css_vars")),
                    classnames,
                    null);
        }

        private static Map<String, String> textMap(JsonNode array) {
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<String, JsonNode> e : Php.entries(array)) {
                m.put(e.getKey(), e.getValue().asText());
            }
            return m;
        }

        private static JsonNode declarationsJson(Map<String, JsonNode> declarations) {
            Map<String, Object> m = new LinkedHashMap<>();
            declarations.forEach((k, v) -> m.put(k, phpShape(v)));
            return PhpJson.map(m);
        }

        /**
         * A raw value as PHP's json_decode then json_encode give it back: an empty or 0..n-1 keyed object is a list,
         * an integer beyond PHP's range a float.
         */
        private static JsonNode phpShape(JsonNode v) {
            if (v.isObject()) {
                Map<String, Object> m = new LinkedHashMap<>();
                v.properties().forEach(e -> m.put(e.getKey(), phpShape(e.getValue())));
                return PhpJson.map(m);
            }
            if (v.isArray()) {
                List<Object> l = new ArrayList<>();
                v.forEach(item -> l.add(phpShape(item)));
                return PhpJson.list(l);
            }
            if (v.isBigInteger()) {
                return NODES.numberNode(v.doubleValue());
            }
            return v;
        }
    }

    // Task 3: the processor and stylesheets from CSS rules
    private static final class Task3 {

        private Task3() {}

        /** A rule from {@code {"selector", "declarations", "rules_group"?, "important"?}}, as the driver builds it. */
        private static CssRule ruleSpec(JsonNode spec) {
            JsonNode raw = spec.get("declarations");
            CssDeclarations declarations = spec.has("important")
                    ? object(raw == null ? NODES.arrayNode() : raw, spec.get("important"))
                    : CssDeclarations.fromJson(raw);
            return CssRule.of(spec.get("selector").asText(), declarations, textOrNull(spec.get("rules_group")));
        }

        static JsonNode processor(JsonNode in) {
            StyleStores stores = new StyleStores();
            for (JsonNode s : in.path("stores")) {
                StyleStore store = stores.store(s.get("name").asText());
                for (JsonNode r : s.path("rules")) {
                    String selector = r.get(0).asText();
                    String trimmed = Php.trim(selector);
                    // WordPress's add_rule returns null for these, and the driver then adds nothing
                    if (!trimmed.isEmpty() && !trimmed.equals("0")) {
                        store.addRule(selector, textOrNull(r.get(1)), CssDeclarations.fromJson(r.get(2)));
                    }
                }
            }
            StyleProcessor p = new StyleProcessor();
            for (JsonNode name : in.path("addStores")) {
                p.addStore(stores.store(name.asText()));
            }
            List<CssRule> direct = new ArrayList<>();
            in.path("direct").forEach(spec -> direct.add(ruleSpec(spec)));
            p.addRules(direct);
            List<Object> out = new ArrayList<>();
            JsonNode calls =
                    in.has("calls") ? in.get("calls") : NODES.arrayNode().add(NODES.arrayNode());
            for (JsonNode call : calls) {
                out.add(p.css(Php.isStrictTrue(Php.get(call, "optimize")), Php.isTruthy(Php.get(call, "prettify"))));
            }
            if (!Php.isTruthy(in.get("dumpDirect"))) {
                return PhpJson.list(out);
            }
            List<Object> declarations = new ArrayList<>();
            direct.forEach(
                    rule -> declarations.add(PhpJson.map(rule.declarations().declarations())));
            return PhpJson.list(List.of(out, declarations));
        }

        static JsonNode stylesheetFromRules(JsonNode in, JsonNode args) {
            return NODES.textNode(StyleEngine.stylesheetFromCssRules(entries(in), options(args)));
        }

        /** The entries as the driver builds them. */
        static List<CssRuleEntry> entries(JsonNode in) {
            List<CssRuleEntry> entries = new ArrayList<>();
            if (in != null) {
                for (JsonNode e : in) {
                    String selector = e.has("selector") && !e.get("selector").isNull()
                            ? Php.toPhpString(e.get("selector"))
                            : null;
                    String group = textOrNull(e.get("rules_group"));
                    if (Php.isTruthy(e.get("object"))) {
                        JsonNode raw = e.get("declarations");
                        entries.add(CssRuleEntry.of(
                                selector, object(raw == null ? NODES.arrayNode() : raw, e.get("important")), group));
                    } else {
                        entries.add(CssRuleEntry.fromJson(selector, e.get("declarations"), group));
                    }
                }
            }
            return entries;
        }

        /** Optimize when strictly true, prettify when truthy; no context. */
        static StylesheetOptions options(JsonNode args) {
            return StylesheetOptions.defaults()
                    .withOptimize(Php.isStrictTrue(Php.get(args, "optimize")))
                    .withPrettify(Php.isTruthy(Php.get(args, "prettify")));
        }
    }

    // Task 5: stores in the entry points
    private static final class Task5 {

        private Task5() {}

        /**
         * get_styles calls into the context store (or the store a call's options name, or none with "noContext"),
         * then the context's stylesheet plain and prettified, the store names and every store's rules.
         */
        static JsonNode stylesThenContext(JsonNode in, JsonNode args) {
            String context = in.get("context").asText();
            StyleStores stores = new StyleStores();
            List<Object> results = new ArrayList<>();
            for (JsonNode call : in.get("calls")) {
                JsonNode styles = call.get(0);
                JsonNode options = call.get(1);
                StyleOptions o = Task4.options(options);
                String name = null;
                if (!Php.isTruthy(Php.get(options, "noContext"))) {
                    JsonNode own = Php.get(options, "context");
                    // the driver's ??= keeps a call's own context unless it is missing or null
                    name = own == null || own.isNull() ? context : own.asText();
                }
                if (!Php.isEmpty(name)) {
                    // WordPress fetches, and so creates, the store only when it has a rule to add to it
                    StyleStore store = stores.find(name).orElse(null);
                    if (store == null
                            && !Php.isEmpty(o.selector())
                            && !StyleEngine.getStyles(styles, o).declarations().isEmpty()) {
                        store = stores.store(name);
                    }
                    if (store != null) {
                        o = o.withContext(store);
                    }
                }
                results.add(Task4.result(StyleEngine.getStyles(styles, o)));
            }
            StylesheetOptions plain =
                    StylesheetOptions.defaults().withOptimize(Php.isStrictTrue(Php.get(args, "optimize")));
            String css = StyleEngine.stylesheetFromContext(stores, context, plain);
            String pretty = StyleEngine.stylesheetFromContext(stores, context, plain.withPrettify(true));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("results", results);
            out.put("stores", stores.names());
            out.put("css", css);
            out.put("pretty", pretty);
            out.put("store", dumpRules(stores));
            return PhpJson.map(out);
        }

        /** Store ops, then the stylesheet of args.context and the store names. */
        static JsonNode stylesheetFromContext(JsonNode ops, JsonNode args) {
            StyleStores stores = new StyleStores();
            storeOps(stores, ops);
            String css = StyleEngine.stylesheetFromContext(
                    stores, Php.get(args, "context").asText(), Task3.options(args));
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("css", css);
            out.put("stores", stores.names());
            return PhpJson.map(out);
        }

        /** stylesheet_from_rules with args.context: the stylesheet, every store's rules and the context's stylesheet. */
        static JsonNode stylesheetFromRules(JsonNode in, JsonNode args) {
            String context = Php.get(args, "context").asText();
            StyleStores stores = new StyleStores();
            StylesheetOptions options = Task3.options(args);
            String css =
                    StyleEngine.stylesheetFromCssRules(Task3.entries(in), options.withContext(stores.store(context)));
            String fromContext = StyleEngine.stylesheetFromContext(stores, context, options);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("css", css);
            out.put("store", dumpRules(stores));
            out.put("fromContext", fromContext);
            return PhpJson.map(out);
        }
    }
}
