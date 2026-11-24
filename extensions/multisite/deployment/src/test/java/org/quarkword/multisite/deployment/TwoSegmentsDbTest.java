package org.quarkword.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.core.SiteId;
import org.quarkword.core.SiteRequest;
import org.quarkword.core.runtime.Binding;
import org.quarkword.multisite.runtime.HttpResolution;

class TwoSegmentsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(TwoSegmentsDbTest.class, Network.class)
            .overrideRuntimeConfigKey("quarkword.multisite.path-segments", "2");

    @Inject
    HttpResolution resolution;

    @Inject
    Network network;

    @Test
    void theLongestOfTwoSegmentsWins() {
        String slug = Network.unique("two");
        SiteId outer = network.site("localhost", "/" + slug + "/");
        SiteId inner = network.site("localhost", "/" + slug + "/b/");
        assertThat(resolution
                        .route(new SiteRequest("http", "localhost", -1, "/" + slug + "/b/c"))
                        .binding())
                .isEqualTo(Binding.site(inner));
        assertThat(resolution
                        .route(new SiteRequest("http", "localhost", -1, "/" + slug + "/c"))
                        .binding())
                .isEqualTo(Binding.site(outer));
    }
}
