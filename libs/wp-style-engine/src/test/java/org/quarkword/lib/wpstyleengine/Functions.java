package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
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
            "safecss");

    /** A protected WordPress helper, or a driver-local one, by the name the driver maps it to. */
    private static JsonNode protectedHelper(String name, JsonNode args) {
        return switch (name) {
            // Task 1
            case "is_valid_style_value" -> NODES.booleanNode(Php.isValidStyleValue(args.get(0)));
            case "php_string" -> NODES.textNode(Php.toPhpString(args.get(0)));
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
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("nulls", nulls);
        out.put("stores", stores.names());
        out.put("rules", PhpJson.map(rules));
        return PhpJson.map(out);
    }
}
