package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.devui.runtime.AnonymousDevIdentity;
import org.quarkpress.devui.runtime.DevCalls;
import org.quarkpress.devui.runtime.QuarkPressDevJsonRpc;
import org.quarkpress.devui.runtime.SitesDevJsonRpc;
import org.quarkpress.devui.runtime.SystemDevIdentity;

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
        assertThat(plan.methods()).containsExactly("features", "persistence");
    }

    @Test
    void multisiteAndSecurityAddTheSitesReadRunAsTheSystem() {
        DevUiPlan plan = DevUiPlan.of(Set.of(DevUiPlan.PERSISTENCE, DevUiPlan.MULTISITE, DevUiPlan.SECURITY)::contains);
        assertThat(plan.providers()).containsExactly(QuarkPressDevJsonRpc.class, SitesDevJsonRpc.class);
        assertThat(plan.beans()).containsExactly(DevCalls.class.getName(), SystemDevIdentity.class.getName());
        assertThat(plan.methods()).containsExactly("features", "persistence", "sites");
        assertThat(plan.pages())
                .extracting(DevUiPlan.PagePlan::component)
                .containsExactly(
                        "qwc-quarkpress-modules.js",
                        "qwc-quarkpress-sites.js",
                        "qwc-quarkpress-features.js",
                        "qwc-quarkpress-persistence.js");
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
}
