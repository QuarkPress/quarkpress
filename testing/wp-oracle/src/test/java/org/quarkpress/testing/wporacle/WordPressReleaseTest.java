package org.quarkpress.testing.wporacle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class WordPressReleaseTest {

    @Test
    void loadsThePinnedRelease() {
        WordPressRelease release = WordPressRelease.load("7.1.3");
        assertThat(release.version()).isEqualTo("7.1.3");
        assertThat(release.tag()).isEqualTo("7.1.3");
        assertThat(release.commit()).isEqualTo("ab1bc858a3f02a99c175d9e93c019254a4d5692c");
        assertThat(release.sha256ByPath()).hasSize(8);
        assertThat(release.sha256ByPath())
                .containsEntry(
                        "wp-includes/blocks.php", "99de93fa9afbf4576d5a34d1e46365980d510de59f565bece9f4db178625a460")
                .containsEntry(
                        "wp-includes/functions.php",
                        "cac8a17e293df99f0683a88debd3ba5e5cce274183a4e9fd3c9f18375747f41c");
    }

    @Test
    void unknownVersionFails() {
        assertThatThrownBy(() -> WordPressRelease.load("0.0.1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wp-0.0.1.sha256");
    }

    @Test
    void malformedPinFilesAreRejected() {
        String commit = "# commit " + "a".repeat(40) + "\n";
        assertThatThrownBy(() -> WordPressRelease.parse("x", commit + "nothex  wp-includes/a.php\n"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> WordPressRelease.parse("x", "b".repeat(64) + "  wp-includes/a.php\n"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("commit");
        String twice = commit + "b".repeat(64) + "  wp-includes/a.php\n" + "c".repeat(64) + "  wp-includes/a.php\n";
        assertThatThrownBy(() -> WordPressRelease.parse("x", twice))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplicate");
    }
}
