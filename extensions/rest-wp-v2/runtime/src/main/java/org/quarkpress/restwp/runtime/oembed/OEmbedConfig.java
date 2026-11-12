package org.quarkpress.restwp.runtime.oembed;

import io.quarkus.runtime.annotations.ConfigPhase;
import io.quarkus.runtime.annotations.ConfigRoot;
import io.quarkus.runtime.configuration.MemorySize;
import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The oEmbed proxy's settings. A provider is a name present in both {@link #providerEndpoints} and
 * {@link #providerSchemes} (two flat maps, since a map of groups cannot be declared to the start-up key check); a
 * name in only one of them refuses the start.
 */
@ConfigMapping(prefix = "quarkpress.oembed")
@ConfigRoot(phase = ConfigPhase.RUN_TIME)
public interface OEmbedConfig {

    /** Whether the bundled snapshot of the oembed.com provider registry is used, after the configured providers. */
    @WithDefault("true")
    boolean registry();

    /**
     * Configured providers' endpoints by name: the oEmbed endpoint URL, in which {@code {format}} stands for
     * {@code json}. A name of the snapshot's ({@code provider_name}) replaces that provider.
     */
    Map<String, String> providerEndpoints();

    /**
     * Configured providers' URL schemes by name, comma-separated (a comma inside a scheme escaped as {@code \,}):
     * wildcard schemes as the registry writes them, {@code *} standing for one or more characters, a leading
     * {@code http://} also matching {@code https://}.
     */
    Map<String, List<String>> providerSchemes();

    /** The providers whose {@code html} is passed on unfiltered; every other provider's goes through the filter. */
    Optional<List<String>> trustedProviders();

    /** The most bytes a provider's answer may have; a larger one is a failure. */
    @WithDefault("1M")
    MemorySize maxResponseSize();

    /** How long one provider exchange may take, redirects and body included. */
    @WithDefault("5s")
    Duration timeout();

    /** The {@code maxwidth} sent to a provider when the request names none, or zero. */
    @WithDefault("600")
    int defaultWidth();

    /** The {@code maxheight} sent to a provider when the request names none, or zero. */
    @WithDefault("750")
    int defaultHeight();
}
