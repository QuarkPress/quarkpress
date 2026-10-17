package org.quarkpress.persistence.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteId;

/**
 * Reads options from qp_option. Reads for a site other than the bound one find nothing. Each read without a caller
 * transaction runs in a fresh one, so it never sees stale rows from the request's session. Values are copies; a stored
 * JSON null reads as a {@code NullNode}. The store reads the database only, never the request's option cache.
 */
@ApplicationScoped
public class JpaOptionStore implements OptionStore {

    @Inject
    OptionRepository options;

    @Override
    @Transactional
    public Optional<JsonNode> find(SiteId site, String name) {
        return options.findOption(site, name).map(option -> JsonValues.copyOfStored(option.value));
    }

    @Override
    @Transactional
    public Map<String, JsonNode> autoloaded(SiteId site) {
        Map<String, JsonNode> values = new LinkedHashMap<>();
        for (OptionEntity option : options.autoloaded(site)) {
            values.put(option.name, JsonValues.copyOfStored(option.value));
        }
        return values;
    }
}
