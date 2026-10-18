package org.quarkpress.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.deployment.Failures;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.core.runtime.PresetSite;

class ForeignPresetSiteTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class, Foreign.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkPress: " + Foreign.class.getName() + " implements PresetSite; only the multisite"
                            + " module decides a request's site before QuarkPress code runs"));

    /** Application code may not decide the request's site around the resolvers. */
    @ApplicationScoped
    public static class Foreign implements PresetSite {
        @Override
        public Optional<Binding> binding() {
            return Optional.of(Binding.NETWORK);
        }

        @Override
        public Optional<URI> redirect() {
            return Optional.empty();
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
