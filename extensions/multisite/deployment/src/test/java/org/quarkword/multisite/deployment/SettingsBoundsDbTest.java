package org.quarkword.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkword.multisite.runtime.MultisiteSettingsView;
import org.quarkword.multisite.runtime.ResolverStrategy;

/** The largest path segment count and an indexed resolvers key pass the key check and are read. */
class SettingsBoundsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(SettingsBoundsDbTest.class)
            .overrideRuntimeConfigKey("quarkword.multisite.path-segments", "16")
            .overrideRuntimeConfigKey("quarkword.multisite.resolvers[0]", "path");

    @Inject
    MultisiteSettingsView settings;

    @Test
    void theLargestCountAndAnIndexedListAreAccepted() {
        assertThat(settings.pathSegments()).isEqualTo(16);
        assertThat(settings.resolvers()).containsExactly(ResolverStrategy.PATH);
    }
}
