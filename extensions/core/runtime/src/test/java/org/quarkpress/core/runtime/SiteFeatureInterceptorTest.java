package org.quarkpress.core.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.annotation.Priority;
import jakarta.interceptor.Interceptor;
import org.junit.jupiter.api.Test;

class SiteFeatureInterceptorTest {

    @Test
    void runsAfterSecurityAndBeforeTransactions() {
        int priority =
                SiteFeatureInterceptor.class.getAnnotation(Priority.class).value();
        assertThat(priority)
                .isEqualTo(SiteFeatureInterceptor.PRIORITY)
                .isEqualTo(Interceptor.Priority.PLATFORM_BEFORE + 175)
                .isGreaterThan(Interceptor.Priority.PLATFORM_BEFORE + 150)
                .isLessThan(Interceptor.Priority.PLATFORM_BEFORE + 200);
    }

    @Test
    void methodIdsSpellParameterTypesLikeTheBuild() throws NoSuchMethodException {
        assertThat(SiteFeatureInterceptor.methodId(Samples.class.getDeclaredMethod(
                        "mixed", String.class, int.class, String[].class, long[][].class)))
                .isEqualTo("mixed(java.lang.String,int,[Ljava.lang.String;,[[J)");
    }

    static class Samples {
        void mixed(String text, int count, String[] names, long[][] grid) {}
    }
}
