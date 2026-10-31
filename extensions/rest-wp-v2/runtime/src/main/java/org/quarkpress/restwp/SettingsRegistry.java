package org.quarkpress.restwp;

import java.util.List;
import java.util.Optional;

/**
 * The registered settings (inject it): WordPress's own, in WordPress's order ({@code url} and {@code email} on a
 * single site only), then those of the {@link SettingsProvider} beans. A REST name is registered once.
 */
public interface SettingsRegistry {

    List<SettingDefinition> all();

    Optional<SettingDefinition> find(String restName);
}
