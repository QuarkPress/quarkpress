package org.quarkpress.persistence.deployment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;

/** Every module owns one Flyway version band; two modules may not share one. */
final class MigrationBands {

    private MigrationBands() {}

    static List<String> problems(List<MigrationLocationBuildItem> locations) {
        List<String> problems = new ArrayList<>();
        Map<Integer, String> moduleByBand = new TreeMap<>();
        Map<String, Integer> bandByModule = new TreeMap<>();
        for (MigrationLocationBuildItem location : locations) {
            String owner = moduleByBand.putIfAbsent(location.band(), location.module());
            if (owner != null && !owner.equals(location.module())) {
                problems.add("QuarkPress migration band V" + location.band() + "_ is claimed by " + owner + " and by "
                        + location.module());
            }
            Integer band = bandByModule.putIfAbsent(location.module(), location.band());
            if (band != null && band != location.band()) {
                problems.add("QuarkPress module " + location.module() + " claims migration bands " + band + " and "
                        + location.band());
            }
        }
        return problems;
    }
}
