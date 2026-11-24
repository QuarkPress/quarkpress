package org.quarkword.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.quarkword.devui.runtime.AnonymousDevIdentity;
import org.quarkword.devui.runtime.ContentDevJsonRpc;
import org.quarkword.devui.runtime.DevCalls;
import org.quarkword.devui.runtime.QuarkWordDevJsonRpc;
import org.quarkword.devui.runtime.RevisionReadsBean;
import org.quarkword.devui.runtime.SchedulerDevJsonRpc;
import org.quarkword.devui.runtime.SitesDevJsonRpc;
import org.quarkword.devui.runtime.SystemDevIdentity;
import org.quarkword.devui.runtime.TaxonomyReadsBean;
import org.quarkword.devui.runtime.UsersDevJsonRpc;

class DevUiPlanTest {

    @Test
    void withoutOptionalModulesOnlyTheModulesPageIsPlanned() {
        DevUiPlan plan = DevUiPlan.of(capability -> false);
        assertThat(plan.pages()).extracting(DevUiPlan.PagePlan::component).containsExactly("qwc-quarkword-modules.js");
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
                        "qwc-quarkword-modules.js", "qwc-quarkword-features.js", "qwc-quarkword-persistence.js");
        assertThat(plan.providers()).containsExactly(QuarkWordDevJsonRpc.class);
        assertThat(plan.beans()).containsExactly(DevCalls.class.getName(), AnonymousDevIdentity.class.getName());
        assertThat(plan.methods())
                .containsExactly("clearFeature", "features", "persistence", "setFeature", "siteExists");
    }

    @Test
    void multisiteAndSecurityAddTheSitesReadRunAsTheSystem() {
        DevUiPlan plan = DevUiPlan.of(Set.of(DevUiPlan.PERSISTENCE, DevUiPlan.MULTISITE, DevUiPlan.SECURITY)::contains);
        assertThat(plan.providers())
                .containsExactly(QuarkWordDevJsonRpc.class, SitesDevJsonRpc.class, UsersDevJsonRpc.class);
        assertThat(plan.beans()).containsExactly(DevCalls.class.getName(), SystemDevIdentity.class.getName());
        assertThat(plan.methods())
                .containsExactly(
                        "clearFeature",
                        "createSite",
                        "devUsers",
                        "features",
                        "persistence",
                        "setFeature",
                        "siteExists",
                        "sites");
        assertThat(plan.pages())
                .extracting(DevUiPlan.PagePlan::component)
                .containsExactly(
                        "qwc-quarkword-modules.js",
                        "qwc-quarkword-sites.js",
                        "qwc-quarkword-features.js",
                        "qwc-quarkword-persistence.js",
                        "qwc-quarkword-users.js");
    }

    @Test
    void theWritesBelongToTheProvidersOfTheirModules() {
        assertThat(DevUiPlan.of(DevUiPlan.PERSISTENCE::equals).methods())
                .containsExactly("clearFeature", "features", "persistence", "setFeature", "siteExists");
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
        assertThat(plan.pages()).extracting(DevUiPlan.PagePlan::component).containsExactly("qwc-quarkword-modules.js");
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
                        "qwc-quarkword-modules.js",
                        "qwc-quarkword-sites.js",
                        "qwc-quarkword-features.js",
                        "qwc-quarkword-persistence.js",
                        "qwc-quarkword-registries.js",
                        "qwc-quarkword-counts.js",
                        "qwc-quarkword-scheduler.js",
                        "qwc-quarkword-users.js");
        assertThat(plan.methods())
                .containsExactly(
                        "clearFeature",
                        "counts",
                        "createSite",
                        "devUsers",
                        "features",
                        "persistence",
                        "registries",
                        "scheduler",
                        "setFeature",
                        "siteExists",
                        "sites");
    }
}
