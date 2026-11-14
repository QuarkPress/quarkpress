package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;

import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.UserId;
import org.quarkpress.restwp.runtime.RestWpConfig;

/**
 * The module declares its keys to core, which refuses every {@code quarkpress.*} key no module declared; the theme
 * keys set what the synthetic theme declares.
 */
class ConfigDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(ConfigDbTest.class)
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.path", "/api")
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.cors.origins", "https://a.test,https://b.test")
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.theme.align-wide", "false")
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.theme.title-tag", "true")
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.theme.formats", "aside,video")
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.theme.html5", "none")
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.theme.disable-custom-colors", "true")
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.editor.autosave-interval", "90")
            .overrideRuntimeConfigKey("quarkpress.rest-wp-v2.editor.local-autosave-interval", "20")
            .overrideRuntimeConfigKey(
                    "quarkpress.rest-wp-v2.editor.dashboard-url", "https://admin.example.test/wp-admin");

    @Inject
    RestWpConfig config;

    @Test
    void theDeclaredKeysAreAcceptedAndRead() {
        assertThat(config.path()).isEqualTo("/api");
        assertThat(config.cors().origins()).contains(List.of("https://a.test", "https://b.test"));
    }

    @Test
    void configuredSupports() {
        // a feature turned off, one turned on, the formats after standard, and html5 not declared (false)
        long editor = TestData.memberOfMain(Jdbc.dataSource(), "editor").value();
        Http.as("test-" + editor)
                .get("/api/wp/v2/themes?status=active&_fields=theme_supports")
                .then()
                .statusCode(200)
                .body(equalTo("[{\"theme_supports\":{\"align-wide\":false,\"automatic-feed-links\":false,"
                        + "\"block-templates\":false,\"block-template-parts\":false,\"custom-background\":false,"
                        + "\"custom-header\":false,\"custom-logo\":false,"
                        + "\"customize-selective-refresh-widgets\":false,\"dark-editor-style\":false,"
                        + "\"disable-custom-colors\":true,\"disable-custom-font-sizes\":false,"
                        + "\"disable-custom-gradients\":false,\"disable-layout-styles\":false,"
                        + "\"editor-color-palette\":false,\"editor-font-sizes\":false,"
                        + "\"editor-gradient-presets\":false,\"editor-spacing-sizes\":false,\"editor-styles\":true,"
                        + "\"html5\":false,\"formats\":[\"standard\",\"aside\",\"video\"],\"post-thumbnails\":true,"
                        + "\"responsive-embeds\":true,\"title-tag\":true,\"wp-block-styles\":false}}]"));
    }

    @Test
    void configuredEditorSettings() {
        // the intervals and the dashboard URL (given without its trailing slash) reach the editor's settings
        AgroalDataSource data = Jdbc.dataSource();
        long editor = TestData.memberOfMain(data, "editor").value();
        PostId post = TestData.post(data, TestData.MAIN, UserId.of(editor), "post", "publish");
        Http.as("test-" + editor)
                .get("/api/quarkpress/v1/editor/post/" + post.value())
                .then()
                .statusCode(200)
                .body("settings.autosaveInterval", equalTo(90))
                .body("settings.localAutosaveInterval", equalTo(20))
                .body("settings.__experimentalDashboardLink", equalTo("https://admin.example.test/wp-admin/"))
                // the configured theme: no wide alignment, formats beyond standard
                .body("settings.alignWide", equalTo(false))
                .body("settings.disablePostFormats", equalTo(false))
                // a declared disable feature turns the recorded false on; the undeclared ones stay recorded
                .body("settings.disableCustomColors", equalTo(true))
                .body("settings.disableCustomFontSizes", equalTo(false))
                .body("settings.disableCustomGradients", equalTo(false))
                .body("settings.disableLayoutStyles", equalTo(false));
    }
}
