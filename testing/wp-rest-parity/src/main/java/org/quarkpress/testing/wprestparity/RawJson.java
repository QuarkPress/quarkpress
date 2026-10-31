package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.quarkpress.testing.wporacle.OracleJson;

/**
 * A JSON body's bytes with the normalisation applied in place: every value (a scalar, or a whole object or list) whose
 * normalised form differs from the value as sent is replaced by the normalised value written as JSON; every other byte
 * (whitespace, the order of keys, the escapes of the strings left alone) is kept. Two bodies that are equal once
 * normalised and parsed are then equal as text exactly when they were written the same way.
 */
final class RawJson {

    /** A span of the body to replace. */
    private record Replacement(int start, int end, String text) {}

    private RawJson() {}

    /**
     * {@code body} (whose parsed form is {@code original}) with each value that differs in {@code normalised} written as
     * its normalised JSON; a value the normalised tree lacks is written as {@code <absent>}.
     */
    static String normalised(byte[] body, JsonNode original, JsonNode normalised) {
        List<Replacement> replacements = new ArrayList<>();
        try (JsonParser parser = OracleJson.MAPPER.getFactory().createParser(body)) {
            parser.nextToken();
            walk(parser, original, normalised, replacements);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(body.length);
        int at = 0;
        for (Replacement replacement : replacements) {
            out.write(body, at, replacement.start() - at);
            out.writeBytes(replacement.text().getBytes(StandardCharsets.UTF_8));
            at = replacement.end();
        }
        out.write(body, at, body.length - at);
        return out.toString(StandardCharsets.UTF_8);
    }

    /** Walks the value the parser is at, whose parsed form is {@code original}, against its normalised form. */
    private static void walk(JsonParser parser, JsonNode original, JsonNode normalised, List<Replacement> out)
            throws IOException {
        JsonToken token = parser.currentToken();
        int start = (int) parser.currentTokenLocation().getByteOffset();
        boolean container = token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY;
        boolean sameShape = normalised != null
                && (token == JsonToken.START_OBJECT
                        ? normalised.isObject()
                        : token == JsonToken.START_ARRAY ? normalised.isArray() : normalised.isValueNode());
        if (container && sameShape) {
            if (token == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    String key = parser.currentName();
                    parser.nextToken();
                    walk(parser, original.get(key), normalised.get(key), out);
                }
            } else {
                int index = 0;
                while (parser.nextToken() != JsonToken.END_ARRAY) {
                    walk(parser, original.get(index), normalised.get(index), out);
                    index++;
                }
            }
            return;
        }
        if (container) {
            parser.skipChildren();
        } else {
            parser.finishToken();
        }
        int end = (int) parser.currentLocation().getByteOffset();
        if (!Objects.equals(original, normalised)) {
            out.add(new Replacement(start, end, normalised == null ? "<absent>" : normalised.toString()));
        }
    }
}
