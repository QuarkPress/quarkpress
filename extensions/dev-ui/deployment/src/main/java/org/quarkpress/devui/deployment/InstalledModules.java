package org.quarkpress.devui.deployment;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeSet;
import java.util.function.Predicate;
import org.quarkpress.core.FeatureDescriptor;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

/**
 * The QuarkPress modules of an application, each with what it declared at build time: its capability, its per-site
 * features, its migration band and the prefixes of its configuration keys.
 */
final class InstalledModules {

    private static final String GROUP_ID = "org.quarkpress";
    private static final String ARTIFACT_PREFIX = "quarkpress-";

    private InstalledModules() {}

    /** A dependency of the application, as the curated application model lists it. */
    record Artifact(String groupId, String artifactId, String version, boolean runtimeExtension) {}

    /** A per-site feature a module declares. */
    record FeatureInfo(String key, boolean defaultEnabled, String description) {}

    /**
     * One installed QuarkPress module. {@code capability} is {@code null} when the module provides none under its
     * name, {@code migrationBand} when it ships no migrations.
     */
    record InstalledModule(
            String artifactId,
            String module,
            String version,
            String capability,
            List<FeatureInfo> features,
            Integer migrationBand,
            List<String> configPrefixes) {

        InstalledModule {
            features = List.copyOf(features);
            configPrefixes = List.copyOf(configPrefixes);
        }
    }

    /**
     * The runtime extension artifacts of group {@code org.quarkpress} named {@code quarkpress-*}, sorted by artifact
     * id, with what each module declared in {@code features}, {@code migrations} and {@code configKeys}.
     */
    static List<InstalledModule> collect(
            List<Artifact> artifacts,
            Predicate<String> capabilityPresent,
            List<SiteFeatureBuildItem> features,
            List<MigrationLocationBuildItem> migrations,
            List<QuarkPressConfigKeysBuildItem> configKeys) {
        List<Artifact> extensions = new ArrayList<>();
        for (Artifact artifact : artifacts) {
            if (artifact.groupId().equals(GROUP_ID)
                    && artifact.runtimeExtension()
                    && artifact.artifactId().startsWith(ARTIFACT_PREFIX)) {
                extensions.add(artifact);
            }
        }
        extensions.sort(Comparator.comparing(Artifact::artifactId));
        List<InstalledModule> modules = new ArrayList<>();
        for (Artifact artifact : extensions) {
            String module = artifact.artifactId().substring(ARTIFACT_PREFIX.length());
            String capability = "org.quarkpress." + module.replace("-", "");
            List<FeatureInfo> moduleFeatures = new ArrayList<>();
            for (SiteFeatureBuildItem feature : features) {
                FeatureDescriptor descriptor = feature.descriptor();
                if (descriptor.module().equals(module)) {
                    moduleFeatures.add(
                            new FeatureInfo(descriptor.key(), descriptor.defaultEnabled(), descriptor.description()));
                }
            }
            moduleFeatures.sort(Comparator.comparing(FeatureInfo::key));
            Integer band = null;
            for (MigrationLocationBuildItem migration : migrations) {
                if (migration.module().equals(module)) {
                    band = migration.band();
                    break;
                }
            }
            TreeSet<String> prefixes = new TreeSet<>();
            for (QuarkPressConfigKeysBuildItem declaration : configKeys) {
                if (declaration.module().equals(module)) {
                    prefixes.add(declaration.prefix());
                }
            }
            modules.add(new InstalledModule(
                    artifact.artifactId(),
                    module,
                    artifact.version(),
                    capabilityPresent.test(capability) ? capability : null,
                    moduleFeatures,
                    band,
                    new ArrayList<>(prefixes)));
        }
        return modules;
    }
}
