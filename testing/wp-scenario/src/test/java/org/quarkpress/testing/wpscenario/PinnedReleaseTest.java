package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class PinnedReleaseTest {

    @Test
    void thePinnedReleaseIsUnpacked() throws Exception {
        String version = System.getProperty("wp.version");
        Path tree = WordPressTree.ensure(version);
        assertThat(tree.resolve("wp-settings.php")).isRegularFile();
        assertThat(tree.resolve("wp-includes/version.php")).content().contains("$wp_version = '" + version + "';");
        assertThat(tree.resolve("wp-admin/includes/schema.php")).isRegularFile();
    }
}
