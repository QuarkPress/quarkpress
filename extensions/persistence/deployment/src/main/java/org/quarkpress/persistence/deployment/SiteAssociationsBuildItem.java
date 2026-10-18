package org.quarkpress.persistence.deployment;

import io.quarkus.builder.item.SimpleBuildItem;
import java.util.List;
import java.util.Map;

/** The field-mapped associations from site-scoped entities to site-scoped entities: class name to field names. */
public final class SiteAssociationsBuildItem extends SimpleBuildItem {

    private final Map<String, List<String>> fieldsByClass;

    public SiteAssociationsBuildItem(Map<String, List<String>> fieldsByClass) {
        this.fieldsByClass = Map.copyOf(fieldsByClass);
    }

    public Map<String, List<String>> fieldsByClass() {
        return fieldsByClass;
    }
}
