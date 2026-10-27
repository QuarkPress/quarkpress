package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.quarkpress.devui.runtime.AnonymousDevIdentity;
import org.quarkpress.devui.runtime.ContentDevJsonRpc;
import org.quarkpress.devui.runtime.DevCalls;
import org.quarkpress.devui.runtime.QuarkPressDevJsonRpc;
import org.quarkpress.devui.runtime.RevisionReadsBean;
import org.quarkpress.devui.runtime.SchedulerDevJsonRpc;
import org.quarkpress.devui.runtime.SitesDevJsonRpc;
import org.quarkpress.devui.runtime.SystemDevIdentity;
import org.quarkpress.devui.runtime.TaxonomyReadsBean;
import org.quarkpress.devui.runtime.UsersDevJsonRpc;

class DevUiPlanTest {

    @Test
    void withoutOptionalModulesOnlyTheModulesPageIsPlanned() {
        DevUiPlan plan = DevUiPlan.of(capability -> false);
        assertThat(plan.pages()).extracting(DevUiPlan.PagePlan::component).containsExactly("qwc-quarkpress-modules.js");
        assertThat(plan.providers()).isEmpty();
        assertThat(plan.beans()).isEmpty();
        assertThat(plan.methods()).isEmpty();
    }

    @Test
    void persistenceAloneGivesTheFeatureAndPersistenceReadsWithoutTheSystemIdentity() {
        DevUiPlan plan = DevUiPlan.of(DevUiPlan.PERSISTENCE::equals);
        assertThat(plan.pages())
                .extracting(DevUiPlan.PagePlan::component)
                .containsExactly(
                        "qwc-quarkpress-modules.js", "qwc-quarkpress-features.js", "qwc-quarkpress-persistence.js");
        assertThat(plan.providers()).containsExactly(QuarkPressDevJsonRpc.class);
        assertThat(plan.beans()).containsExactly(DevCalls.class.getName(), AnonymousDevIdentity.class.getName());
        assertThat(plan.methods()).containsExactly("features", "persistence", "setFeature");
    }

    @Test
    void multisiteAndSecurityAddTheSitesReadRunAsTheSystem() {
        DevUiPlan plan = DevUiPlan.of(Set.of(DevUiPlan.PERSISTENCE, DevUiPlan.MULTISITE, DevUiPlan.SECURITY)::contains);
        assertThat(plan.providers())
                .containsExactly(QuarkPressDevJsonRpc.class, SitesDevJsonRpc.class, UsersDevJsonRpc.class);
        assertThat(plan.beans()).containsExactly(DevCalls.class.getName(), SystemDevIdentity.class.getName());
        assertThat(plan.methods())
                .containsExactly("createSite", "devUsers", "features", "persistence", "setFeature", "sites");
        assertThat(plan.pages())
                .extracting(DevUiPlan.PagePlan::component)
                .containsExactly(
                        "qwc-quarkpress-modules.js",
                        "qwc-quarkpress-sites.js",
                        "qwc-quarkpress-features.js",
                        "qwc-quarkpress-persistence.js",
                        "qwc-quarkpress-users.js");
    }

    @Test
    void theWritesBelongToTheProvidersOfTheirModules() {
        assertThat(DevUiPlan.of(DevUiPlan.PERSISTENCE::equals).methods())
                .containsExactly("features", "persistence", "setFeature");
        assertThat(DevUiPlan.methodsOf(List.of(SitesDevJsonRpc.class))).containsExactly("createSite", "sites");
    }

    @Test
    void postsWithoutTaxonomyAndRevisionsPlanTheContentReadsWithoutTheirBeans() {
        DevUiPlan plan = DevUiPlan.of(Set.of(DevUiPlan.PERSISTENCE, DevUiPlan.SECURITY, DevUiPlan.POSTS)::contains);
        assertThat(plan.providers()).contains(ContentDevJsonRpc.class);
        assertThat(plan.methods()).contains("counts", "registries");
        assertThat(plan.beans()).doesNotContain(TaxonomyReadsBean.class.getName(), RevisionReadsBean.class.getName());
        DevUiPlan full =
                DevUiPlan.of(Set.of(
                        DevUiPlan.PERSISTENCE,
                        DevUiPlan.SECURITY,
                        DevUiPlan.POSTS,
                        DevUiPlan.TAXONOMY,
                        DevUiPlan.REVISIONS)::contains);
        assertThat(full.beans()).contains(TaxonomyReadsBean.class.getName(), RevisionReadsBean.class.getName());
    }

    @Test
    void multisiteWithoutPersistenceGetsNoSitesPage() {
        DevUiPlan plan = DevUiPlan.of(DevUiPlan.MULTISITE::equals);
        assertThat(plan.pages()).extracting(DevUiPlan.PagePlan::component).containsExactly("qwc-quarkpress-modules.js");
        assertThat(plan.providers()).isEmpty();
        assertThat(plan.beans()).isEmpty();
    }

    /** A provider shape: an overloaded method, a static one, and methods Dev UI does not publish. */
    public static class SampleProvider {

        public String read() {
            return "";
        }

        public String read(String key) {
            return key;
        }

        public static String count() {
            return "0";
        }

        protected String guarded() {
            return "";
        }

        String packaged() {
            return "";
        }

        private String hidden() {
            return "";
        }
    }

    @Test
    void theMethodsAreThePublicOnesStaticIncludedEachNameOnceSorted() {
        assertThat(DevUiPlan.methodsOf(List.of(SampleProvider.class))).containsExactly("count", "read");
    }

    @Test
    void theTokenPageNeedsOidcAndKeycloakDevServices() {
        UnaryOperator<String> resolve = path -> "/q/" + path;
        assertThat(DevUiPlan.tokenPage(true, true, resolve)).contains("/q/dev-ui/quarkus-oidc/keycloak-provider");
        assertThat(DevUiPlan.tokenPage(true, false, resolve)).isEmpty();
        assertThat(DevUiPlan.tokenPage(false, true, resolve)).isEmpty();
    }

    @Test
    void schedulingAndSecurityPlanTheSchedulerAndDevUsers() {
        DevUiPlan plan =
                DevUiPlan.of(Set.of(DevUiPlan.PERSISTENCE, DevUiPlan.SECURITY, DevUiPlan.SCHEDULING)::contains);
        assertThat(plan.providers()).contains(SchedulerDevJsonRpc.class, UsersDevJsonRpc.class);
        assertThat(plan.methods()).contains("scheduler", "devUsers");
    }

    @Test
    void theNonApplicationPathFollowsQuarkusRules() {
        assertThat(DevUiPlan.nonApplicationPath("/", "q", "dev-ui/x")).isEqualTo("/q/dev-ui/x");
        assertThat(DevUiPlan.nonApplicationPath("/app", "q", "dev-ui/x")).isEqualTo("/app/q/dev-ui/x");
        assertThat(DevUiPlan.nonApplicationPath("/app/", "q/", "dev-ui/x")).isEqualTo("/app/q/dev-ui/x");
        assertThat(DevUiPlan.nonApplicationPath("/app", "/internal", "dev-ui/x"))
                .isEqualTo("/internal/dev-ui/x");
        assertThat(DevUiPlan.nonApplicationPath("/app", "/", "dev-ui/x")).isEqualTo("/dev-ui/x");
    }

    @Test
    void everyModulePresentPlansEveryPageProviderAndMethod() {
        DevUiPlan plan = DevUiPlan.of(capability -> true);
        assertThat(plan.pages())
                .extracting(DevUiPlan.PagePlan::component)
                .containsExactly(
                        "qwc-quarkpress-modules.js",
                        "qwc-quarkpress-sites.js",
                        "qwc-quarkpress-features.js",
                        "qwc-quarkpress-persistence.js",
                        "qwc-quarkpress-registries.js",
                        "qwc-quarkpress-counts.js",
                        "qwc-quarkpress-scheduler.js",
                        "qwc-quarkpress-users.js");
        assertThat(plan.methods())
                .containsExactly(
                        "counts",
                        "createSite",
                        "devUsers",
                        "features",
                        "persistence",
                        "registries",
                        "scheduler",
                        "setFeature",
                        "sites");
    }
}
