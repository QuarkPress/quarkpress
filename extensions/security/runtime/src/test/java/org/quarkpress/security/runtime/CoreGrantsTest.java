package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreGrantsTest {

    private static Map<String, Boolean> administrator() {
        return AllCaps.of(RoleTables.byKey(BuiltInRoles.all()), List.of("administrator"), Map.of());
    }

    @Test
    void anAdministratorGetsTheFilterGrantsOnASingleSite() {
        Map<String, Boolean> granted = CoreGrants.apply(administrator(), false);
        assertThat(granted)
                .containsEntry("install_languages", true)
                .containsEntry("resume_plugins", true)
                .containsEntry("resume_themes", true)
                .containsEntry("view_site_health_checks", true);
    }

    @Test
    void inANetworkSiteHealthIsNotGranted() {
        Map<String, Boolean> granted = CoreGrants.apply(administrator(), true);
        assertThat(granted).containsEntry("install_languages", true).doesNotContainKey("view_site_health_checks");
    }

    @Test
    void anEditorGetsNothingAndDenialsCountAsAbsent() {
        Map<String, Boolean> editor = AllCaps.of(RoleTables.byKey(BuiltInRoles.all()), List.of("editor"), Map.of());
        assertThat(CoreGrants.apply(editor, false))
                .doesNotContainKeys("install_languages", "resume_plugins", "resume_themes", "view_site_health_checks");
        Map<String, Boolean> denied = AllCaps.of(
                RoleTables.byKey(BuiltInRoles.all()), List.of("administrator"), Map.of("activate_plugins", false));
        assertThat(CoreGrants.apply(denied, false)).doesNotContainKey("resume_plugins");
    }

    @Test
    void theInputIsNotChanged() {
        Map<String, Boolean> admin = administrator();
        CoreGrants.apply(admin, false);
        assertThat(admin).doesNotContainKey("install_languages");
    }
}
