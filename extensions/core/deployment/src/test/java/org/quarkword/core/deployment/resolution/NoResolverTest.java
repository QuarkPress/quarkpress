package org.quarkword.core.deployment.resolution;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteRequest;
import org.quarkword.core.deployment.Requests;
import org.quarkword.core.runtime.Binding;
import org.quarkword.core.runtime.SiteBinding;
import org.quarkword.core.runtime.SiteResolution;

class NoResolverTest {

    @RegisterExtension
    static final QuarkusExtensionTest app =
            new QuarkusExtensionTest().withApplicationRoot(jar -> jar.addClasses(Requests.class, NoResolverTest.class));

    @Inject
    SiteResolution resolution;

    @Inject
    SiteBinding binding;

    @Test
    void withoutResolversNothingIsResolved() {
        assertThat(resolution.resolve(SiteRequest.none())).isEmpty();
        Requests.fresh(() -> assertThat(binding.current()).isEqualTo(Binding.UNRESOLVED));
    }
}
