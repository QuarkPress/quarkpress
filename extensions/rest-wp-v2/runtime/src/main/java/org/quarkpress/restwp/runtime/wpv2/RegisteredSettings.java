package org.quarkpress.restwp.runtime.wpv2;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.restwp.SettingDefinition;
import org.quarkpress.restwp.SettingsProvider;
import org.quarkpress.restwp.SettingsRegistry;
import org.quarkpress.security.runtime.InstallMode;

/**
 * The settings registry: WordPress's settings for this kind of installation (a network or a single site, as the
 * security module's {@link InstallMode} tells), then each {@link SettingsProvider} bean's,
 * the beans ordered by class name. A REST name registered twice fails when the registry is first read, which the
 * settings route's registration does while the application starts.
 */
@ApplicationScoped
public class RegisteredSettings implements SettingsRegistry {

    @Inject
    @All
    List<InstanceHandle<SettingsProvider>> providers;

    @Inject
    InstallMode mode;

    private volatile Registered registered;

    private record Registered(List<SettingDefinition> all, Map<String, SettingDefinition> byName) {}

    @Override
    public List<SettingDefinition> all() {
        return registered().all();
    }

    @Override
    public Optional<SettingDefinition> find(String restName) {
        return Optional.ofNullable(registered().byName().get(restName));
    }

    private Registered registered() {
        Registered known = registered;
        if (known == null) {
            synchronized (this) {
                known = registered;
                if (known == null) {
                    known = build();
                    registered = known;
                }
            }
        }
        return known;
    }

    private Registered build() {
        List<SettingDefinition> all = new ArrayList<>(BuiltInSettings.of(mode.multisite()));
        List<InstanceHandle<SettingsProvider>> sorted = new ArrayList<>(providers);
        sorted.sort(
                Comparator.comparing(handle -> handle.getBean().getBeanClass().getName()));
        for (InstanceHandle<SettingsProvider> handle : sorted) {
            all.addAll(handle.get().settings());
        }
        Map<String, SettingDefinition> byName = new HashMap<>();
        for (SettingDefinition setting : all) {
            if (byName.putIfAbsent(setting.restName(), setting) != null) {
                throw new IllegalStateException("the setting " + setting.restName() + " is registered twice");
            }
        }
        return new Registered(List.copyOf(all), Map.copyOf(byName));
    }
}
