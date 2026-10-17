package org.quarkpress.core.deployment.spi;

import io.quarkus.builder.item.MultiBuildItem;
import java.util.Locale;
import java.util.Objects;

/**
 * Declares a site-scoped table. Native SQL that names it is allowed only in classes annotated
 * {@code @SiteScopedNativeSql}, because native SQL bypasses the site filter.
 */
public final class SiteScopedTableBuildItem extends MultiBuildItem {

    private final String table;

    public SiteScopedTableBuildItem(String table) {
        String lower = Objects.requireNonNull(table, "table").toLowerCase(Locale.ROOT);
        if (!lower.startsWith("qp_")) {
            throw new IllegalArgumentException("QuarkPress tables start with qp_: " + table);
        }
        this.table = lower;
    }

    public String table() {
        return table;
    }
}
