package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.security.runtime.InstallMode;
import org.quarkpress.security.runtime.SecurityConfig;

class InstallModeDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(InstallModeDbTest.class);

    @Inject
    InstallMode mode;

    @Inject
    SecurityConfig config;

    @Test
    void withoutTheMultisiteModuleTheInstallIsSingleSite() {
        assertThat(mode.multisite()).isFalse();
    }

    @Test
    void settingsHaveWordPressDefaults() {
        assertThat(config.initialSuperAdmins()).isEmpty();
        assertThat(config.disallowUnfilteredHtml()).isFalse();
    }
}
