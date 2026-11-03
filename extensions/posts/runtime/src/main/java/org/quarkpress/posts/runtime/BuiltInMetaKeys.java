package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.lib.wpkses.TextFields;
import org.quarkpress.posts.MetaContext;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaSanitizer;
import org.quarkpress.posts.PostTypes;

/**
 * The post meta keys WordPress registers itself: {@code wp_pattern_sync_status} for {@code wp_block} (a string with
 * the values {@code partial} and {@code unsynced}, text-field sanitized with lib-wp-kses), then {@code footnotes} for
 * every REST type supporting an editor, custom fields and revisions, in type registration order (a string copied
 * into revisions, sanitized by {@link FootnotesSanitizer}). Neither has a default: an absent key reads as "".
 */
public final class BuiltInMetaKeys {

    public static final String FOOTNOTES = "footnotes";
    public static final String PATTERN_SYNC_STATUS = "wp_pattern_sync_status";

    /** WordPress's text-field sanitizing of a scalar, as a sanitizer. */
    static final MetaSanitizer TEXT_FIELD = (value, context) -> TextNode.valueOf(TextFields.textField(text(value)));

    private BuiltInMetaKeys() {}

    /** The built-in keys for the registered types; {@code unfilteredHtml} answers for the footnotes sanitizer. */
    static List<MetaKeyDefinition> all(PostTypes types, Predicate<MetaContext> unfilteredHtml) {
        List<MetaKeyDefinition> keys = new ArrayList<>();
        if (types.registered("wp_block")) {
            ObjectNode values = JsonNodeFactory.instance.objectNode();
            values.putArray("enum").add("partial").add("unsynced");
            keys.add(MetaKeyDefinition.builder(PATTERN_SYNC_STATUS)
                    .subtype("wp_block")
                    .single()
                    .showInRest()
                    .schema(values)
                    .sanitizer(TEXT_FIELD)
                    .build());
        }
        FootnotesSanitizer footnotes = new FootnotesSanitizer(unfilteredHtml);
        for (PostTypeDefinition type : types.all()) {
            if (type.showInRest()
                    && type.supports("editor")
                    && type.supports("custom-fields")
                    && type.supports("revisions")) {
                keys.add(MetaKeyDefinition.builder(FOOTNOTES)
                        .subtype(type.key())
                        .single()
                        .showInRest()
                        .revisions()
                        .sanitizer(footnotes)
                        .build());
            }
        }
        return keys;
    }

    private static String text(JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode() || value.isContainerNode()) {
            return "";
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "1" : "";
        }
        return value.asText();
    }
}
