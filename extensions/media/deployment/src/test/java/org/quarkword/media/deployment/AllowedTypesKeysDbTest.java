package org.quarkword.media.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.media.runtime.MediaConfig;

/** The two map settings take one further key segment, quoted or plain, and the application starts. */
class AllowedTypesKeysDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MediaApp.with(AllowedTypesKeysDbTest.class)
            .overrideRuntimeConfigKey("quarkword.media.allowed-types.\"svg\"", "image/svg+xml")
            .overrideRuntimeConfigKey("quarkword.media.allowed-types.\"tgz\"", "application/gzip")
            .overrideRuntimeConfigKey("quarkword.media.allowed-types.psd", "image/vnd.adobe.photoshop")
            .overrideRuntimeConfigKey("quarkword.media.output-formats.\"image/heic\"", "image/png");

    @Inject
    MediaConfig config;

    @Test
    void allowedTypesKeysAreAccepted() {
        assertThat(config.allowedTypes())
                .containsEntry("svg", "image/svg+xml")
                .containsEntry("tgz", "application/gzip")
                .containsEntry("psd", "image/vnd.adobe.photoshop");
    }

    @Test
    void aConfiguredOutputFormatOverridesTheDefault() {
        assertThat(config.effectiveOutputFormats())
                .containsEntry("image/heic", "image/png")
                .containsEntry("image/heif", "image/jpeg");
    }
}
