package org.quarkpress.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/**
 * A network option was created, changed or deleted. Network options belong to no site, so the event carries none. A
 * {@code MissingNode} stands for "no value"; the values are copies.
 */
public record NetworkOptionChanged(Instant at, String name, JsonNode before, JsonNode after) {

    public NetworkOptionChanged {
        Objects.requireNonNull(at, "at");
        Objects.requireNonNull(name, "name");
        before = Objects.requireNonNull(before, "before").deepCopy();
        after = Objects.requireNonNull(after, "after").deepCopy();
    }

    @Override
    public JsonNode before() {
        return before.deepCopy();
    }

    @Override
    public JsonNode after() {
        return after.deepCopy();
    }
}
