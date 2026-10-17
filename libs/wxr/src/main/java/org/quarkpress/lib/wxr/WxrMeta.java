package org.quarkpress.lib.wxr;

import java.util.Optional;
import org.quarkpress.lib.phpserialize.MaybeUnserializeResult;
import org.quarkpress.lib.phpserialize.PhpString;
import org.quarkpress.lib.phpserialize.PhpValue;
import org.quarkpress.lib.phpserialize.WordPressSerialization;

/**
 * A post, comment or term meta row: key and value as written, never trimmed. The parsed form follows the
 * WordPress Importer: when WordPress's {@code is_serialized} accepts the value, every object header is rewritten
 * to {@code stdClass} and the result goes through {@code maybe_unserialize}. The parsed form is not cached: it is
 * recomputed on each call, so keep the result when it is needed more than once.
 */
public record WxrMeta(String key, String value) {

    /** The three outcomes; a null value reads as empty text, as the importer sees a missing value. */
    public MaybeUnserializeResult unserialized() {
        String data = value == null ? "" : value;
        // The shape check inside tryMaybeUnserialize decides whether the text is serialized; the header rewrite
        // never changes that outcome, so the check runs once, on the rewritten text.
        MaybeUnserializeResult result = WordPressSerialization.tryMaybeUnserialize(ObjectHeaders.toStdClass(data));
        if (result instanceof MaybeUnserializeResult.NotSerialized) {
            return new MaybeUnserializeResult.NotSerialized(PhpString.of(data));
        }
        return result;
    }

    /** The parsed value when the value is serialized and parses; empty otherwise (WordPress would give false). */
    public Optional<PhpValue> parsed() {
        return unserialized() instanceof MaybeUnserializeResult.Parsed parsed
                ? Optional.of(parsed.value())
                : Optional.empty();
    }
}
