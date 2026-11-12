package org.quarkpress.restwp.runtime.oembed;

import com.fasterxml.jackson.databind.JsonNode;
import io.quarkus.runtime.StartupEvent;
import io.quarkus.runtime.configuration.ConfigurationException;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * The oEmbed providers the proxy may ask, in the order they are tried: the configured ones ({@code
 * quarkpress.oembed.provider-endpoints.<name>} with {@code provider-schemes.<name>}, by name), then the bundled
 * snapshot of the oembed.com registry (when {@code quarkpress.oembed.registry} is on), minus any snapshot provider a
 * configured one is named after. A URL goes to the first endpoint one of whose wildcard schemes matches it; endpoints
 * without schemes (discovery only) are never used. A provider named in only one of the two maps, or a trusted name
 * that is no provider, refuses the start.
 */
@ApplicationScoped
public class OEmbedProviders {

    /** The bundled snapshot of {@code https://oembed.com/providers.json}; its licence sits beside it. */
    static final String SNAPSHOT = "/org/quarkpress/restwp/oembed/providers.json";

    /** The provider that matched a URL and the endpoint to ask ({@code {format}} already replaced by json). */
    public record Match(String provider, String endpoint) {
        public Match {
            Objects.requireNonNull(provider, "provider");
            Objects.requireNonNull(endpoint, "endpoint");
        }
    }

    /** One endpoint of a provider with the schemes that lead to it. */
    private record Entry(String provider, List<String> schemes, String endpoint) {}

    /** The ordered endpoints and the trusted names, built once. */
    private record Registry(List<Entry> entries, Set<String> trusted) {}

    @Inject
    OEmbedConfig config;

    private volatile Registry registry;

    /** For the container. */
    OEmbedProviders() {}

    private OEmbedProviders(Registry registry) {
        this.registry = registry;
    }

    /**
     * A registry from the given settings, as the bean builds its own from the configuration.
     *
     * @throws ConfigurationException for a provider named in only one of the two maps, or a trusted name that is no
     *     provider
     */
    public static OEmbedProviders of(
            boolean registry, Map<String, String> endpoints, Map<String, List<String>> schemes, List<String> trusted) {
        return new OEmbedProviders(build(registry, endpoints, schemes, trusted));
    }

    /** Builds the registry while the application starts, so that a misconfiguration refuses the start. */
    void start(@Observes StartupEvent started) {
        registry();
    }

    private Registry registry() {
        Registry built = registry;
        if (built == null) {
            built = build(
                    config.registry(),
                    config.providerEndpoints(),
                    config.providerSchemes(),
                    config.trustedProviders().orElse(List.of()));
            registry = built;
        }
        return built;
    }

    /** The first provider endpoint one of whose schemes matches {@code url}. */
    public Optional<Match> find(String url) {
        for (Entry entry : registry().entries()) {
            for (String scheme : entry.schemes()) {
                if (WildcardScheme.matches(scheme, url)) {
                    return Optional.of(
                            new Match(entry.provider(), entry.endpoint().replace("{format}", "json")));
                }
            }
        }
        return Optional.empty();
    }

    /** Whether the provider's {@code html} is passed on unfiltered. */
    public boolean trusted(String provider) {
        return registry().trusted().contains(provider);
    }

    private static Registry build(
            boolean snapshot, Map<String, String> endpoints, Map<String, List<String>> schemes, List<String> trusted) {
        for (String name : endpoints.keySet()) {
            if (!schemes.containsKey(name)) {
                throw new ConfigurationException("quarkpress-rest-wp-v2: the oEmbed provider " + name
                        + " has an endpoint but no schemes; set quarkpress.oembed.provider-schemes." + name);
            }
        }
        for (String name : schemes.keySet()) {
            if (!endpoints.containsKey(name)) {
                throw new ConfigurationException("quarkpress-rest-wp-v2: the oEmbed provider " + name
                        + " has schemes but no endpoint; set quarkpress.oembed.provider-endpoints." + name);
            }
        }
        List<Entry> entries = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        // the configured providers first, in name order
        for (Map.Entry<String, String> endpoint : new TreeMap<>(endpoints).entrySet()) {
            String name = endpoint.getKey();
            entries.add(new Entry(name, List.copyOf(schemes.get(name)), endpoint.getValue()));
            names.add(name);
        }
        if (snapshot) {
            for (Entry entry : snapshot()) {
                if (!endpoints.containsKey(entry.provider())) {
                    entries.add(entry);
                    names.add(entry.provider());
                }
            }
        }
        for (String name : trusted) {
            if (!names.contains(name)) {
                throw new ConfigurationException("quarkpress-rest-wp-v2: quarkpress.oembed.trusted-providers names "
                        + name + ", which is not a provider");
            }
        }
        return new Registry(List.copyOf(entries), Set.copyOf(trusted));
    }

    /** The snapshot's endpoints that have schemes, in the registry's order. */
    private static List<Entry> snapshot() {
        JsonNode providers;
        try (InputStream in = OEmbedProviders.class.getResourceAsStream(SNAPSHOT)) {
            if (in == null) {
                throw new IllegalStateException("the oEmbed provider snapshot " + SNAPSHOT + " is missing");
            }
            providers = RestJson.READER.readTree(in);
        } catch (IOException e) {
            throw new UncheckedIOException("the oEmbed provider snapshot " + SNAPSHOT + " cannot be read", e);
        }
        List<Entry> entries = new ArrayList<>();
        for (JsonNode provider : providers) {
            JsonNode name = provider.get("provider_name");
            if (name == null || !name.isTextual()) {
                continue;
            }
            for (JsonNode endpoint : provider.path("endpoints")) {
                JsonNode url = endpoint.get("url");
                JsonNode schemes = endpoint.get("schemes");
                if (url == null || !url.isTextual() || schemes == null || !schemes.isArray() || schemes.isEmpty()) {
                    continue;
                }
                List<String> patterns = new ArrayList<>();
                for (JsonNode scheme : schemes) {
                    if (scheme.isTextual() && !scheme.textValue().isEmpty()) {
                        patterns.add(scheme.textValue());
                    }
                }
                if (!patterns.isEmpty()) {
                    entries.add(new Entry(name.textValue(), List.copyOf(patterns), url.textValue()));
                }
            }
        }
        return entries;
    }
}
