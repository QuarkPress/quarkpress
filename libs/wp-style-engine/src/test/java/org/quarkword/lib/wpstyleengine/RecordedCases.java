package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import org.quarkword.testing.wporacle.OracleJson;

/** Reads the shared case file of both test tiers. */
final class RecordedCases {

    static final String RESOURCE = "/wp-parity-cases.json";

    private RecordedCases() {}

    static List<RecordedCase> load() {
        try (InputStream in = RecordedCases.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("missing test resource " + RESOURCE);
            }
            List<RecordedCase> cases = new ArrayList<>();
            for (JsonNode c : OracleJson.MAPPER.readTree(in)) {
                JsonNode args = c.get("args");
                JsonNode out = c.get("out");
                cases.add(new RecordedCase(
                        c.get("id").asText(),
                        c.get("fn").asText(),
                        c.get("in"),
                        args instanceof ObjectNode o ? o : OracleJson.MAPPER.createObjectNode(),
                        out == null || out.isNull() ? null : out,
                        c.path("oracle").asBoolean(true)));
            }
            return List.copyOf(cases);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
