package org.quarkword.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteRequest;
import org.quarkword.core.SiteResolver;
import org.quarkword.core.deployment.Requests;
import org.quarkword.core.runtime.SiteResolution;

class TieOrderTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(Requests.class, Zulu.class, Alpha.class, TieOrderTest.class));

    @ApplicationScoped
    @Priority(50)
    public static class Zulu implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            return Optional.of(SiteId.of(26));
        }
    }

    @ApplicationScoped
    @Priority(50)
    public static class Alpha implements SiteResolver {
        @Override
        public Optional<SiteId> resolve(SiteRequest request) {
            return Optional.of(SiteId.of(1));
        }
    }

    @Inject
    SiteResolution resolution;

    @Test
    void equalPrioritiesAreOrderedByClassName() {
        for (int i = 0; i < 5; i++) {
            assertThat(resolution.resolve(SiteRequest.none())).contains(SiteId.of(1));
        }
    }
}
