package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.SiteChanges;
import org.quarkpress.multisite.Sites;

class ReservedNonApplicationSegmentDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(ReservedNonApplicationSegmentDbTest.class, Network.class);

    @Inject
    Sites sites;

    @Inject
    Network network;

    @Test
    void theNonApplicationRootsFirstSegmentIsReservedUnderTheNetworkPath() {
        network.moveMain("localhost", "/net/");
        try {
            assertThatThrownBy(() -> sites.create(NewSite.of("localhost", "/net/q/", "x")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("the first path segment 'q' is reserved for the non-application root on subdirectory"
                            + " sites of the network");
            SiteInfo site = sites.create(NewSite.of("localhost", "/net/quiet/", "Quiet"));
            assertThatThrownBy(() -> sites.update(site.id(), SiteChanges.none().withPath("/net/q/")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("the first path segment 'q' is reserved");
            assertThatThrownBy(() -> sites.create(NewSite.of("localhost", "/net/wp-json/", "x")))
                    .hasMessageStartingWith("the first path segment 'wp-json' is reserved for WordPress");
            // another domain's sites are no subdirectory sites of the network
            assertThat(sites.create(NewSite.of(Network.unique("other") + ".example", "/q2/", "Elsewhere"))
                            .path())
                    .isEqualTo("/q2/");
        } finally {
            network.moveMain("localhost", "/");
        }
    }
}
