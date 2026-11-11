package org.quarkpress.outbound.runtime;

import io.quarkus.runtime.StartupEvent;
import io.quarkus.tls.TlsConfigurationRegistry;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

/**
 * Start-up checks of the outbound settings: a named TLS configuration must exist and have a trust store, and lifted
 * address checks are announced.
 */
public class OutboundStartup {

    private static final Logger LOG = Logger.getLogger(OutboundStartup.class);

    @Inject
    OutboundConfig config;

    @Inject
    TlsConfigurationRegistry tls;

    void start(@Observes StartupEvent event) {
        VertxFetch.trustOptions(tls, config.tlsConfigurationName());
        if (config.allowPrivateAddresses()) {
            LOG.warn("quarkpress.outbound.allow-private-addresses is true: outbound requests may reach loopback and"
                    + " private addresses; use it for tests only");
        }
    }
}
