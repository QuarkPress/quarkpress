package org.quarkpress.restwp;

import java.util.List;

/**
 * Adds settings to the registry: an application's CDI beans of this type register theirs after WordPress's own, the
 * beans ordered by class name and each bean's settings in its order.
 */
public interface SettingsProvider {

    List<SettingDefinition> settings();
}
