package org.quarkword.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.decorator.Decorator;
import jakarta.decorator.Delegate;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;
import org.quarkword.core.deployment.Failures;
import org.quarkword.core.runtime.Binding;
import org.quarkword.core.runtime.PresetSite;
import org.quarkword.multisite.runtime.TestPresetSite;

class ForeignPresetDecoratorTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Failures.class, TestPresetSite.class, Rewriting.class))
            .assertException(failure -> assertThat(Failures.messages(failure))
                    .contains("QuarkWord: " + Rewriting.class.getName() + " implements PresetSite; only the multisite"
                            + " module decides a request's site before QuarkWord code runs"));

    /** Application code may not rewrite the request's site by decorating the preset. */
    @Decorator
    @Priority(1)
    public abstract static class Rewriting implements PresetSite {
        @Inject
        @Delegate
        PresetSite delegate;

        @Override
        public Optional<Binding> binding() {
            return Optional.of(Binding.site(SiteId.of(1)));
        }

        @Override
        public Optional<URI> redirect() {
            return delegate.redirect();
        }
    }

    @Test
    void theBuildFails() {
        // assertException above checks the failure
    }
}
