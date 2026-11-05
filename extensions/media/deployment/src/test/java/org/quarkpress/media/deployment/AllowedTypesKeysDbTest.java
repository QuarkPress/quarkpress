package org.quarkpress.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.media.runtime.MediaConfig;

/** The two map settings take one further key segment, quoted or plain, and the application starts. */
class AllowedTypesKeysDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.with(AllowedTypesKeysDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.media.allowed-types.\"svg\"", "image/svg+xml")
            .overrideRuntimeConfigKey("quarkpress.media.allowed-types.\"tar.gz\"", "application/gzip")
            .overrideRuntimeConfigKey("quarkpress.media.allowed-types.psd", "image/vnd.adobe.photoshop")
            .overrideRuntimeConfigKey("quarkpress.media.output-formats.\"image/heic\"", "image/png");

    @Inject
    MediaConfig config;

    @Test
    void allowedTypesKeysAreAccepted() {
        assertThat(config.allowedTypes())
                .containsEntry("svg", "image/svg+xml")
                .containsEntry("tar.gz", "application/gzip")
                .containsEntry("psd", "image/vnd.adobe.photoshop");
    }

    @Test
    void aConfiguredOutputFormatOverridesTheDefault() {
        assertThat(config.effectiveOutputFormats())
                .containsEntry("image/heic", "image/png")
                .containsEntry("image/heif", "image/jpeg");
    }
}
