package org.quarkpress.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.arc.DefaultBean;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.OptionStore;
import org.quarkpress.core.SiteId;

/** Stores no option; replaced by the persistence module's store. */
@DefaultBean
@ApplicationScoped
public class NoOptionStore implements OptionStore {

    @Override
    public Optional<JsonNode> find(SiteId site, String name) {
        return Optional.empty();
    }

    @Override
    public Map<String, JsonNode> autoloaded(SiteId site) {
        return Map.of();
    }
}
