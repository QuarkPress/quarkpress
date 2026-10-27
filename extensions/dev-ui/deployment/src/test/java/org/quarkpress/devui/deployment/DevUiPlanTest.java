package org.quarkpress.devui.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DevUiPlanTest {

    @Test
    void withoutOptionalModulesOnlyTheModulesPageIsPlanned() {
        DevUiPlan plan = DevUiPlan.of(capability -> false);
        assertThat(plan.pages()).extracting(DevUiPlan.PagePlan::component).containsExactly("qwc-quarkpress-modules.js");
        assertThat(plan.providers()).isEmpty();
        assertThat(plan.beans()).isEmpty();
        assertThat(plan.methods()).isEmpty();
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
