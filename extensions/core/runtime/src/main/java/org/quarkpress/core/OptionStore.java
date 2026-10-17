package org.quarkpress.core;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import java.util.Optional;

/**
 * Reads stored site options. Names are already normalized. The persistence module provides the real store.
 * Implementations read only the site they are given and must not read options through {@link SiteContext} or the
 * request option cache while answering.
 */
public interface OptionStore {

    Optional<JsonNode> find(SiteId site, String name);

    /** Every option of the site marked for autoloading, by name. */
    Map<String, JsonNode> autoloaded(SiteId site);
}
