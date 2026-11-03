package org.quarkpress.posts;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A registered key's sanitize callback: runs on every value written through {@link RegisteredMeta}, with the caller
 * in the context, and returns the value to store. Never null: return the value as it is to leave it alone.
 */
@FunctionalInterface
public interface MetaSanitizer {

    JsonNode sanitize(JsonNode value, MetaContext context);
}
