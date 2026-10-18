package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.multisite.runtime.HttpResolution;
import org.quarkpress.multisite.runtime.SiteLookup;

class NetworkOnceDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(NetworkOnceDbTest.class, CountingLookup.class);

    static final AtomicInteger NETWORK_LOOKUPS = new AtomicInteger();

    /** Counts how often the network address is read. */
    @Alternative
    @Priority(1)
    @ApplicationScoped
    public static class CountingLookup extends SiteLookup {
        @Override
        public Optional<Address> network() {
            NETWORK_LOOKUPS.incrementAndGet();
            return super.network();
        }
    }

    @Inject
    HttpResolution resolution;

    @Test
    void theNetworkAddressIsLookedUpOncePerResolution() {
        NETWORK_LOOKUPS.set(0);
        // an unknown host is asked by all three strategies
        assertThat(resolution
                        .route(new SiteRequest("http", "nobody.example", -1, "/"))
                        .binding())
                .isEqualTo(Binding.UNRESOLVED);
        assertThat(NETWORK_LOOKUPS).hasValue(1);
        assertThat(resolution
                        .route(new SiteRequest("http", "nobody.example", -1, "/"))
                        .binding())
                .isEqualTo(Binding.UNRESOLVED);
        assertThat(NETWORK_LOOKUPS).hasValue(2);
    }
}
