package org.quarkpress.lib.wpkses;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * WordPress 7.1.3's kses data (resource {@code wordpress-tables.txt}, written by src/test/php/export-tables.php):
 * the default protocols, URI attributes, safe CSS properties, and the post and data allowlists.
 */
record WordPressTables(
        List<String> protocols,
        List<String> uriAttributes,
        List<String> safeStyleProperties,
        Allowlist post,
        Allowlist data) {

    static final String RESOURCE = "wordpress-tables.txt";

    static final WordPressTables INSTANCE = load();

    private static WordPressTables load() {
        try (InputStream in = WordPressTables.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing resource " + RESOURCE);
            }
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            return parse(reader.lines().toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static WordPressTables parse(List<String> lines) {
        List<String> protocols = null;
        List<String> uriAttributes = null;
        List<String> safeStyle = null;
        Map<String, Allowlist.Builder> contexts = new LinkedHashMap<>();
        for (String line : lines) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] fields = split(line, ' ');
            List<String> rest = List.of(fields).subList(1, fields.length);
            switch (fields[0]) {
                case "protocols" -> protocols = rest;
                case "uri-attributes" -> uriAttributes = rest;
                case "safe-style-css" -> safeStyle = rest;
                case "element" ->
                    contexts.computeIfAbsent(fields[1], k -> Allowlist.builder())
                            .element(fields[2]);
                case "attribute" ->
                    contexts.computeIfAbsent(fields[1], k -> Allowlist.builder())
                            .attribute(fields[2], fields[3], rule(fields));
                default -> throw new IllegalStateException("unknown line in " + RESOURCE + ": " + line);
            }
        }
        if (protocols == null
                || uriAttributes == null
                || safeStyle == null
                || !contexts.containsKey("post")
                || !contexts.containsKey("data")) {
            throw new IllegalStateException(RESOURCE + " is incomplete");
        }
        return new WordPressTables(
                protocols,
                uriAttributes,
                safeStyle,
                contexts.get("post").build(),
                contexts.get("data").build());
    }

    /** Splits at every {@code separator}, keeping empty fields. */
    private static String[] split(String text, char separator) {
        List<String> parts = new ArrayList<>();
        int start = 0;
        for (int i = text.indexOf(separator); i >= 0; i = text.indexOf(separator, start)) {
            parts.add(text.substring(start, i));
            start = i + 1;
        }
        parts.add(text.substring(start));
        return parts.toArray(String[]::new);
    }

    /** "attribute <context> <element> <name> [required] [<check>=<value>]...". */
    private static AttributeRule rule(String[] fields) {
        boolean required = false;
        List<AttributeCheck> checks = new ArrayList<>();
        for (int i = 4; i < fields.length; i++) {
            String token = fields[i];
            if (token.equals("required")) {
                required = true;
                continue;
            }
            int eq = token.indexOf('=');
            String name = token.substring(0, eq);
            String value = token.substring(eq + 1);
            checks.add(
                    switch (name) {
                        case "maxlen" -> AttributeCheck.maxLength(Long.parseLong(value));
                        case "minlen" -> AttributeCheck.minLength(Long.parseLong(value));
                        case "maxval" -> AttributeCheck.maxValue(Long.parseLong(value));
                        case "minval" -> AttributeCheck.minValue(Long.parseLong(value));
                        case "valueless" -> AttributeCheck.valueless(value);
                        case "values" -> AttributeCheck.values(List.of(split(value, ',')));
                        case "value_callback" -> {
                            if (!value.equals("_wp_kses_allow_pdf_objects")) {
                                throw new IllegalStateException("unknown callback " + value);
                            }
                            yield AttributeCheck.pdfObjectUrl();
                        }
                        default -> throw new IllegalStateException("unknown check " + token);
                    });
        }
        AttributeCheck[] array = checks.toArray(AttributeCheck[]::new);
        if (required) {
            return AttributeRule.required(array);
        }
        return array.length == 0 ? AttributeRule.allowed() : AttributeRule.of(array);
    }
}
