package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.multisite.runtime.HttpResolution;

class NetworkPathDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(NetworkPathDbTest.class, Network.class);

    @Inject
    HttpResolution resolution;

    @Inject
    Network network;

    private Binding resolve(String path) {
        return resolution.route(new SiteRequest("http", "localhost", -1, path)).binding();
    }

    @Test
    void sitesOfANetworkWithAPathCountSegmentsAfterIt() {
        String slug = Network.unique("under");
        SiteId site = network.site("localhost", "/net/" + slug + "/");
        network.moveMain("localhost", "/net/");
        try {
            assertThat(resolve("/net/" + slug + "/x")).isEqualTo(Binding.site(site));
            assertThat(resolve("/net/")).isEqualTo(Binding.site(SiteId.of(1)));
            assertThat(resolve("/NET/")).isEqualTo(Binding.site(SiteId.of(1)));
            assertThat(resolve("/net/other")).isEqualTo(Binding.site(SiteId.of(1)));
            // not under the network path: one segment is compared, and no site lives at "/" or "/other/"
            assertThat(resolve("/other/x")).isEqualTo(Binding.UNRESOLVED);
            assertThat(resolve("/")).isEqualTo(Binding.UNRESOLVED);
        } finally {
            network.moveMain("localhost", "/");
        }
    }
}
