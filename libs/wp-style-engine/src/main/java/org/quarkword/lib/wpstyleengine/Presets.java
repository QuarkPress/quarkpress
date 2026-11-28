package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;

/** Preset references, {@code var:preset|<type>|<slug>}, as WordPress's style engine reads them. */
final class Presets {

    private Presets() {}

    /**
     * The kebab-cased slug of a preset of {@code presetType}: the value must contain {@code var:preset|<type>|}
     * anywhere, and the slug is everything after its last {@code |}. {@code ""} when the value or the type is not
     * text, or the value names no preset of that type.
     */
    static String slug(JsonNode value, String presetType) {
        if (value == null || !value.isTextual() || presetType == null) {
            return "";
        }
        String text = value.asText();
        if (!text.contains("var:preset|" + presetType + "|")) {
            return "";
        }
        return KebabCase.of(text.substring(text.lastIndexOf('|') + 1));
    }

    /**
     * {@code var(<pattern>)} for the first preset type of {@code cssVars} that gives a slug, with every {@code $slug}
     * in the pattern replaced by it once; {@code ""} when none does.
     */
    static String cssVarValue(JsonNode value, Map<String, String> cssVars) {
        for (Map.Entry<String, String> e : cssVars.entrySet()) {
            String slug = slug(value, e.getKey());
            // a valid style value: any text but "", "0" included
            if (!slug.isEmpty()) {
                return "var(" + e.getValue().replace("$slug", slug) + ")";
            }
        }
        return "";
    }
}
