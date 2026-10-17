package org.quarkpress.core.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;
import java.util.Objects;

/**
 * Declares that a module ships Flyway migrations under {@code db/quarkpress/...} using version band {@code band}: its
 * script names start with {@code V<band>_}. Two modules may not claim the same band.
 */
public final class MigrationLocationBuildItem extends MultiBuildItem {

    private final String module;
    private final int band;

    public MigrationLocationBuildItem(String module, int band) {
        this.module = Objects.requireNonNull(module, "module");
        if (band < 1) {
            throw new IllegalArgumentException("migration band must be at least 1: " + band);
        }
        this.band = band;
    }

    public String module() {
        return module;
    }

    public int band() {
        return band;
    }
}
