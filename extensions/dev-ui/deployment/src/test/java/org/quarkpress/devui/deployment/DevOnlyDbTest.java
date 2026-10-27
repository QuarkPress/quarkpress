package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.Arc;
import io.quarkus.builder.BuildChainBuilder;
import io.quarkus.deployment.builditem.FeatureBuildItem;
import io.quarkus.deployment.builditem.RunTimeConfigurationDefaultBuildItem;
import io.quarkus.deployment.pkg.builditem.CurateOutcomeBuildItem;
import io.quarkus.devui.spi.AbstractDevUIBuildItem;
import io.quarkus.devui.spi.JsonRPCProvidersBuildItem;
import io.quarkus.devui.spi.page.CardPageBuildItem;
import io.quarkus.test.QuarkusExtensionTest;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.devui.runtime.ContentDevJsonRpc;
import org.quarkpress.devui.runtime.DevCalls;
import org.quarkpress.devui.runtime.DevIdentity;
import org.quarkpress.devui.runtime.QuarkPressDevJsonRpc;
import org.quarkpress.devui.runtime.RevisionReads;
import org.quarkpress.devui.runtime.SchedulerDevJsonRpc;
import org.quarkpress.devui.runtime.SitesDevJsonRpc;
import org.quarkpress.devui.runtime.TaxonomyReads;
import org.quarkpress.devui.runtime.UsersDevJsonRpc;

/**
 * A build that is not local development (here: test mode) gets nothing from the dev-ui extension: no feature, no card,
 * no JSON-RPC provider, no bean. A build step added to the chain fails the build if the extension's feature, card or
 * provider appears.
 */
class DevOnlyDbTest {

    // Each later task that adds a JSON-RPC provider or bean adds its runtime class here.
    static final List<Class<?>> DEV_ONLY_BEANS = List.of(
            DevCalls.class,
            DevIdentity.class,
            QuarkPressDevJsonRpc.class,
            SitesDevJsonRpc.class,
            ContentDevJsonRpc.class,
            TaxonomyReads.class,
            RevisionReads.class,
            SchedulerDevJsonRpc.class,
            UsersDevJsonRpc.class);

    @RegisterExtension
    static final QuarkusExtensionTest app =
            DevUiApp.nonDevelopment(DevOnlyDbTest.class).addBuildChainCustomizer(DevOnlyDbTest::probe);

    static void probe(BuildChainBuilder builder) {
        builder.addBuildStep(context -> {
                    CurateOutcomeBuildItem curate = context.consume(CurateOutcomeBuildItem.class);
                    List<AbstractDevUIBuildItem> items = new ArrayList<>();
                    items.addAll(context.consumeMulti(CardPageBuildItem.class));
                    items.addAll(context.consumeMulti(JsonRPCProvidersBuildItem.class));
                    for (AbstractDevUIBuildItem item : items) {
                        if (item.getExtensionPathName(curate).equals("quarkpress-dev-ui")) {
                            throw new IllegalStateException(
                                    "a non-development build produced a quarkpress-dev-ui card or JSON-RPC provider");
                        }
                    }
                    for (FeatureBuildItem feature : context.consumeMulti(FeatureBuildItem.class)) {
                        if (feature.getName().equals(DevUiProcessor.FEATURE)) {
                            throw new IllegalStateException(
                                    "a non-development build installed the quarkpress-dev-ui feature");
                        }
                    }
                    context.produce(new RunTimeConfigurationDefaultBuildItem("dev-ui-probe.checked", "true"));
                })
                .consumes(CardPageBuildItem.class)
                .consumes(JsonRPCProvidersBuildItem.class)
                .consumes(CurateOutcomeBuildItem.class)
                .consumes(FeatureBuildItem.class)
                .produces(RunTimeConfigurationDefaultBuildItem.class)
                .build();
    }

    @Test
    void aNonDevelopmentBuildHasNoDevUiCardOrJsonRpcProvider() {
        assertThat(ConfigProvider.getConfig().getValue("dev-ui-probe.checked", Boolean.class))
                .isTrue();
    }

    @Test
    void aNonDevelopmentBuildHasNoDevUiBean() {
        for (Class<?> type : DEV_ONLY_BEANS) {
            assertThat(Arc.container().select(type).isResolvable())
                    .as(type.getName())
                    .isFalse();
        }
    }
}
