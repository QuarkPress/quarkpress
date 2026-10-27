package org.quarkpress.devui.deployment;

import io.quarkus.arc.deployment.AdditionalBeanBuildItem;
import io.quarkus.arc.processor.BuiltinScope;
import io.quarkus.deployment.Capabilities;
import io.quarkus.deployment.IsLocalDevelopment;
import io.quarkus.deployment.annotations.BuildProducer;
import io.quarkus.deployment.annotations.BuildStep;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.devui.spi.page.Page;
import io.quarkus.devui.spi.page.WebComponentPageBuilder;
import io.quarkus.maven.dependency.ResolvedDependency;
import java.util.ArrayList;
import java.util.List;
import org.quarkpress.core.deployment.spi.MigrationLocationBuildItem;
import org.quarkpress.core.deployment.spi.QuarkPressConfigKeysBuildItem;
import org.quarkpress.core.deployment.spi.SiteFeatureBuildItem;

/**
 * The QuarkPress card of the Quarkus Dev UI. Every step runs only in local development, so a production or test build
 * gets no page, no JSON-RPC provider and no bean from this extension.
 */
class DevUiProcessor {

    static final String FEATURE = "quarkpress-dev-ui";

    /** The Dev UI namespace of this extension's card: the artifact id of its runtime module. */
    static final String NAMESPACE = "quarkpress-dev-ui";

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    FeatureBuildItem feature() {
        return new FeatureBuildItem(FEATURE);
    }

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    void card(
            Capabilities capabilities,
            CurateOutcomeBuildItem curate,
            List<SiteFeatureBuildItem> features,
            List<MigrationLocationBuildItem> migrations,
            List<QuarkPressConfigKeysBuildItem> configKeys,
            BuildProducer<CardPageBuildItem> cards) {
        DevUiPlan plan = DevUiPlan.of(capabilities::isPresent);
        List<InstalledModules.Artifact> artifacts = new ArrayList<>();
        for (ResolvedDependency dependency : curate.getApplicationModel().getDependencies()) {
            artifacts.add(new InstalledModules.Artifact(
                    dependency.getGroupId(),
                    dependency.getArtifactId(),
                    dependency.getVersion(),
                    dependency.isRuntimeExtensionArtifact()));
        }
        List<InstalledModules.InstalledModule> modules =
                InstalledModules.collect(artifacts, capabilities::isPresent, features, migrations, configKeys);
        List<String> components = new ArrayList<>();
        for (DevUiPlan.PagePlan page : plan.pages()) {
            components.add(page.component());
        }
        CardPageBuildItem card = new CardPageBuildItem();
        card.addBuildTimeData("modules", modules);
        card.addBuildTimeData("pages", components);
        card.addBuildTimeData("methods", plan.methods());
        for (DevUiPlan.PagePlan page : plan.pages()) {
            WebComponentPageBuilder builder = Page.webComponentPageBuilder()
                    .title(page.title())
                    .icon(page.icon())
                    .componentLink(page.component());
            if (page.component().equals(DevUiPlan.MODULES_COMPONENT)) {
                builder.staticLabel(String.valueOf(modules.size()));
            }
            card.addPage(builder);
        }
        cards.produce(card);
    }

    @BuildStep(onlyIf = IsLocalDevelopment.class)
    void jsonRpc(
            Capabilities capabilities,
            BuildProducer<JsonRPCProvidersBuildItem> providers,
            BuildProducer<AdditionalBeanBuildItem> beans) {
        DevUiPlan plan = DevUiPlan.of(capabilities::isPresent);
        for (Class<?> provider : plan.providers()) {
            providers.produce(new JsonRPCProvidersBuildItem(provider, BuiltinScope.APPLICATION.getName()));
        }
        if (!plan.beans().isEmpty()) {
            beans.produce(AdditionalBeanBuildItem.builder()
                    .addBeanClasses(plan.beans())
                    .setUnremovable()
                    .setDefaultScope(BuiltinScope.APPLICATION.getName())
                    .build());
        }
    }
}
