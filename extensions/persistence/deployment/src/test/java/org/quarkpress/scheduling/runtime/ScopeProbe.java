package org.quarkpress.scheduling.runtime;

import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Instant;
import org.quarkpress.persistence.NetworkScope;
import org.quarkpress.persistence.runtime.OptionEntity;
import org.quarkpress.persistence.runtime.OptionRepository;

/** Stands in for code of a trusted module in tests. */
@ApplicationScoped
public class ScopeProbe {

    @Inject
    NetworkScope network;

    @Inject
    OptionRepository options;

    public long countOptionsNamed(String name) throws Exception {
        return network.call(() -> options.count("name", name));
    }

    public Throwable insertWithoutSite(String name) {
        try {
            network.run(() -> {
                OptionEntity option = new OptionEntity();
                option.name = name;
                option.value = TextNode.valueOf(name);
                option.autoload = true;
                option.updatedAt = Instant.parse("2026-10-17T12:00:00Z");
                options.persist(option);
                options.flush();
            });
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }
}
