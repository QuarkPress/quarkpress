package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The admin folder below a site's URL that the editor's dashboard link defaults to. */
class AdminFolderTest {

    @Test
    void theAdminPathWithoutItsLeadingSlash() {
        assertThat(EditorSettings.adminFolder(Optional.of("/wp-admin/"))).isEqualTo("wp-admin/");
        assertThat(EditorSettings.adminFolder(Optional.of("/a/b/"))).isEqualTo("a/b/");
        assertThat(EditorSettings.adminFolder(Optional.of("/admin"))).isEqualTo("admin/");
    }

    @Test
    void wordPresssFolderWithoutAPath() {
        assertThat(EditorSettings.adminFolder(Optional.empty())).isEqualTo("wp-admin/");
        assertThat(EditorSettings.adminFolder(Optional.of("/"))).isEqualTo("wp-admin/");
        assertThat(EditorSettings.adminFolder(Optional.of(" "))).isEqualTo("wp-admin/");
    }
}
