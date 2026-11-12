package org.quarkpress.restwp.runtime.oembed;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.quarkus.runtime.configuration.ConfigurationException;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/**
 * The provider registry: the bundled oembed.com snapshot, the configured providers tried before it (one named like a
 * snapshot provider replacing it), the start-up refusal of a provider named in only one of the two maps, and the
 * configuration keys the mapping declares.
 */
class OEmbedProvidersTest {

    @Test
    void snapshotLoads() {
        OEmbedProviders providers = OEmbedProviders.of(true, Map.of(), Map.of(), List.of());
        Optional<OEmbedProviders.Match> youtube = providers.find("https://www.youtube.com/watch?v=x");
        assertThat(youtube).isPresent();
        assertThat(youtube.get().provider()).isEqualTo("YouTube");
        assertThat(youtube.get().endpoint()).isEqualTo("https://www.youtube.com/oembed");
        // {format} in an endpoint becomes json
        Optional<OEmbedProviders.Match> vimeo = providers.find("https://vimeo.com/123");
        assertThat(vimeo).isPresent();
        assertThat(vimeo.get().provider()).isEqualTo("Vimeo");
        assertThat(vimeo.get().endpoint()).isEqualTo("https://vimeo.com/api/oembed.json");
        // the snapshot's providers are not trusted unless configured so
        assertThat(providers.trusted("YouTube")).isFalse();
        // a URL no scheme matches
        assertThat(providers.find("https://pages.invalid/page")).isEmpty();
        assertThat(providers.find("https://media.invalid/a.mp3")).isEmpty();
        // the registry switched off: nothing is found
        assertThat(OEmbedProviders.of(false, Map.of(), Map.of(), List.of()).find("https://www.youtube.com/watch?v=x"))
                .isEmpty();
    }

    @Test
    void configuredFirst() {
        Map<String, String> endpoints = new LinkedHashMap<>();
        Map<String, List<String>> schemes = new LinkedHashMap<>();
        endpoints.put("local", "http://127.0.0.1:1/oembed");
        schemes.put("local", List.of("https://local.invalid/*", "https://*.youtube.com/watch*"));
        endpoints.put("YouTube", "http://127.0.0.1:1/yt?format=xml");
        schemes.put("YouTube", List.of("https://youtu.be/*"));
        OEmbedProviders providers = OEmbedProviders.of(true, endpoints, schemes, List.of("local"));
        // the configured provider matches before the snapshot's YouTube
        assertThat(providers.find("https://www.youtube.com/watch?v=x"))
                .contains(new OEmbedProviders.Match("local", "http://127.0.0.1:1/oembed"));
        assertThat(providers.find("https://local.invalid/a"))
                .map(OEmbedProviders.Match::provider)
                .contains("local");
        // the configured YouTube replaces the snapshot's: its old schemes no longer match
        assertThat(providers.find("https://youtu.be/x"))
                .contains(new OEmbedProviders.Match("YouTube", "http://127.0.0.1:1/yt?format=xml"));
        assertThat(providers.find("https://www.youtube.com/v/x")).isEmpty();
        // trust is by name
        assertThat(providers.trusted("local")).isTrue();
        assertThat(providers.trusted("YouTube")).isFalse();
        // without the snapshot only the configured ones exist
        OEmbedProviders own = OEmbedProviders.of(false, endpoints, schemes, List.of());
        assertThat(own.find("https://vimeo.com/1")).isEmpty();
        assertThat(own.find("https://youtu.be/x"))
                .map(OEmbedProviders.Match::provider)
                .contains("YouTube");
    }

    @Test
    void aProviderNamedInOneMapOnlyIsRefused() {
        assertThatThrownBy(
                        () -> OEmbedProviders.of(true, Map.of("lonely", "http://127.0.0.1:1/x"), Map.of(), List.of()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("lonely")
                .hasMessageContaining("quarkpress.oembed.provider-schemes.lonely");
        assertThatThrownBy(() ->
                        OEmbedProviders.of(true, Map.of(), Map.of("orphan", List.of("https://o.invalid/*")), List.of()))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("orphan")
                .hasMessageContaining("quarkpress.oembed.provider-endpoints.orphan");
        // a trusted name that is no provider at all is refused too
        assertThatThrownBy(() -> OEmbedProviders.of(true, Map.of(), Map.of(), List.of("nobody")))
                .isInstanceOf(ConfigurationException.class)
                .hasMessageContaining("nobody");
    }

    @Test
    void theDeclaredKeysAreFlat() {
        // the keys core's start-up check will admit under quarkpress.oembed, as the deployment SPI derives them
        Set<String> keys = new TreeSet<>();
        for (Method method : OEmbedConfig.class.getMethods()) {
            if (method.isDefault() || method.getParameterCount() != 0) {
                continue;
            }
            String name = kebab(method.getName());
            Type generic = method.getGenericReturnType();
            Class<?> type = method.getReturnType();
            if (type == Optional.class && generic instanceof ParameterizedType parameterized) {
                generic = parameterized.getActualTypeArguments()[0];
                type = generic instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) generic;
            }
            if (Map.class.isAssignableFrom(type)) {
                Type value = ((ParameterizedType) generic).getActualTypeArguments()[1];
                Class<?> raw = value instanceof ParameterizedType p ? (Class<?>) p.getRawType() : (Class<?>) value;
                // a map of configuration groups could not be declared: its keys would be two segments deep
                assertThat(raw.isInterface() && !raw.getName().startsWith("java."))
                        .as(name + " is a map of groups")
                        .isFalse();
                keys.add(name + ".*");
            } else if (java.util.Collection.class.isAssignableFrom(type)) {
                keys.add(name + "[]");
            } else {
                assertThat(type.isInterface() && !type.getName().startsWith("java."))
                        .as(name + " is a group")
                        .isFalse();
                keys.add(name);
            }
        }
        assertThat(keys)
                .containsExactly(
                        "default-height",
                        "default-width",
                        "max-response-size",
                        "provider-endpoints.*",
                        "provider-schemes.*",
                        "registry",
                        "timeout",
                        "trusted-providers[]");
    }

    private static String kebab(String name) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (Character.isUpperCase(c)) {
                out.append('-').append(Character.toLowerCase(c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }
}
