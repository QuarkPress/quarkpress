package org.quarkpress.outbound.runtime;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Settings of outbound requests (oEmbed discovery, URL uploads), read when the application starts. */
@ConfigMapping(prefix = "quarkpress.outbound")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface OutboundConfig {

    /** How long a connection may take to open. */
    @WithDefault("5s")
    Duration connectTimeout();

    /** How long a whole exchange may take, redirects and body included, unless the caller sets its own. */
    @WithDefault("5s")
    Duration timeout();

    /** How long a connection may stay silent between two reads. */
    @WithDefault("5s")
    Duration idleTimeout();

    /** How many redirects are followed; the 3xx answer after them is returned as is. */
    @WithDefault("5")
    int maxRedirects();

    /** The largest body (after decompression) accepted, unless the caller sets its own limit. */
    @WithDefault("1M")
    MemorySize maxResponseSize();

    /** The ports a URL may name; a URL without a port uses its scheme's, which must be listed too. */
    @WithDefault("80,443,8080")
    List<Integer> allowedPorts();

    /**
     * Lets requests reach loopback, private, link-local and other non-public addresses. For tests only: it opens the
     * server's own network to anyone who can make it fetch a URL. Scheme, user info and port rules still apply.
     */
    @WithDefault("false")
    boolean allowPrivateAddresses();

    /** The {@code User-Agent} header sent. */
    @WithDefault("QuarkPress")
    String userAgent();

    /**
     * The name of a Quarkus TLS registry configuration ({@code quarkus.tls.<name>.*}) whose trust store replaces the
     * JVM's for HTTPS requests. Unset, the JVM's trust store is used.
     */
    Optional<String> tlsConfigurationName();
}
