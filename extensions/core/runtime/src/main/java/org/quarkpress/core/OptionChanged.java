package org.quarkpress.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import java.util.Objects;

/**
 * A site option was created, changed or deleted. A {@code MissingNode} stands for "no value": {@code before} for a new
 * option, {@code after} for a deleted one. The values are copies; changing them changes nothing.
 */
public record OptionChanged(SiteId site, Instant at, String name, JsonNode before, JsonNode after)
        implements SiteEvent {

    public OptionChanged {
        Objects.requireNonNull(site, "site");
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
