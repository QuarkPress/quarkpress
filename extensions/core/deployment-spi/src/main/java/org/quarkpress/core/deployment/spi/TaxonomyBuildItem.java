package org.quarkpress.core.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;
import java.util.Objects;
import org.quarkpress.core.TaxonomyDefinition;

/**
 * Registers a taxonomy for every site (WordPress's {@code register_taxonomy}). Build the definition with
 * {@code TaxonomyDefinition.builder}, which applies WordPress's defaults. A key that another taxonomy already has, the
 * built-in ones included, fails the build.
 */
public final class TaxonomyBuildItem extends MultiBuildItem {

    private final TaxonomyDefinition definition;

    public TaxonomyBuildItem(TaxonomyDefinition definition) {
        this.definition = Objects.requireNonNull(definition, "definition");
    }

    public TaxonomyDefinition definition() {
        return definition;
    }
}
