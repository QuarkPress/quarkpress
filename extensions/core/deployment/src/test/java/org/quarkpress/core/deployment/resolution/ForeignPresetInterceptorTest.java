package org.quarkpress.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.interceptor.AroundInvoke;
import jakarta.interceptor.Interceptor;
import jakarta.interceptor.InterceptorBinding;
import jakarta.interceptor.InvocationContext;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.deployment.Failures;
import org.quarkpress.multisite.runtime.InterceptedPresetSite;

public class ForeignPresetInterceptorTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(
                    jar -> jar.addClasses(Failures.class, InterceptedPresetSite.class, Watched.class, Rewriting.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: " + Rewriting.class.getName() + " intercepts the PresetSite bean "
                            + InterceptedPresetSite.class.getName()
                            + "; only the multisite module decides a request's site before QuarkPress code runs"));

    @InterceptorBinding
    @Retention(RetentionPolicy.RUNTIME)
    public @interface Watched {}

    /** Application code may not rewrite the request's site by intercepting the preset. */
    @Watched
    @Interceptor
    @Priority(1)
    public static class Rewriting {
        @AroundInvoke
        Object around(InvocationContext context) throws Exception {
            return context.proceed();
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
