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
        assertThat(release.sha256ByPath().keySet()).containsAll(WordPressSources.BOOTSTRAP_FILES);
        assertThat(release.sha256ByPath().keySet())
                .contains("wp-includes/shortcodes.php", "wp-includes/kses.php", "wp-includes/formatting.php");
        assertThat(release.sha256ByPath().values()).allMatch(hash -> hash.matches("[0-9a-f]{64}"));
        assertThat(release.sha256ByPath())
                .containsEntry(
                        "wp-includes/blocks.php", "99de93fa9afbf4576d5a34d1e46365980d510de59f565bece9f4db178625a460")
                .containsEntry(
                        "wp-includes/functions.php", "cac8a17e293df99f0683a88debd3ba5e5cce274183a4e9fd3c9f18375747f41c")
                .containsEntry(
                        "wp-admin/includes/export.php",
                        "1cd94583390885b9324bd5fde38da21af60603c85b64d4b0b6a878fcb1615bba")
                .containsEntry(
                        "wp-includes/pluggable.php", "3a2482a65b50d62ebae75a728be98b78985d3a3f5a3e007cb2d0c1338bf05777")
                .containsEntry(
                        "wp-includes/class-phpass.php",
                        "eac68d6268d6704c8ca3195a731a6cc476e6dbfc6a604adb784886e3a0b10221")
                .containsEntry(
                        "wp-includes/class-wp-application-passwords.php",
                        "266e15ec38652acaee5e3fd6103fc1275d2d6ea5c54f8926c1c4ddf688559658");
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
