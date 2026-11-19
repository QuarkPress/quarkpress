package org.quarkpress.keycloak.provisioner.runtime;

import io.quarkus.runtime.LaunchMode;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Singleton;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.jboss.logging.Logger;

/**
 * Checks at start-up, in production only, that the provisioner does not run on the admin client's default
 * credentials ({@code admin}/{@code admin} through {@code admin-cli} in {@code master}) and knows its realm. Dev and
 * test use Dev Services' admin and are not checked.
 */
@Singleton
public class AdminClientCheck {

    private static final Logger LOG = Logger.getLogger(AdminClientCheck.class);

    static final String ADMIN = "quarkus.keycloak.admin-client.";

    static final String REALM = "quarkpress.keycloak-provisioner.realm";

    /** The settings the check reads; their values are never put in a message. */
    static final List<String> KEYS =
            List.of(ADMIN + "server-url", ADMIN + "grant-type", ADMIN + "username", ADMIN + "password", REALM);

    /** What the check found: errors fail the start, warnings are logged. */
    public record Findings(List<String> errors, List<String> warnings) {

        public Findings {
            errors = List.copyOf(errors);
            warnings = List.copyOf(warnings);
        }
    }

    void onStart(@Observes StartupEvent start) {
        Config config = ConfigProvider.getConfig();
        Map<String, String> settings = new HashMap<>();
        for (String key : KEYS) {
            config.getOptionalValue(key, String.class).ifPresent(value -> settings.put(key, value));
        }
        Findings findings = check(LaunchMode.current(), settings);
        for (String warning : findings.warnings()) {
            LOG.warn(warning);
        }
        if (!findings.errors().isEmpty()) {
            throw new IllegalStateException(String.join(" ", findings.errors()));
        }
    }

    /**
     * Checks {@code settings} (configuration keys to values; an absent key has the admin client's default) for
     * launch mode {@code mode}.
     */
    public static Findings check(LaunchMode mode, Map<String, String> settings) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (mode != LaunchMode.NORMAL) {
            return new Findings(errors, warnings);
        }
        boolean adminUser = passwordGrant(settings.get(ADMIN + "grant-type"))
                && "admin".equals(settings.getOrDefault(ADMIN + "username", "admin"));
        if (adminUser && "admin".equals(settings.getOrDefault(ADMIN + "password", "admin"))) {
            errors.add("quarkpress-keycloak-provisioner refuses to start with the default Keycloak admin credentials of"
                    + " quarkus.keycloak.admin-client (admin/admin): use quarkus.keycloak.admin-client.grant-type="
                    + "CLIENT_CREDENTIALS with a confidential client of the realm whose service account holds only the"
                    + " realm-management roles manage-users and view-users, and its secret from the environment.");
        } else if (adminUser) {
            warnings.add("quarkus.keycloak.admin-client.username is admin: the provisioner signs in as Keycloak's"
                    + " administrator. Prefer quarkus.keycloak.admin-client.grant-type=CLIENT_CREDENTIALS with a client"
                    + " whose service account holds only the realm-management roles manage-users and view-users.");
        }
        String realm = settings.get(REALM);
        if (realm == null || realm.isBlank()) {
            errors.add("quarkpress.keycloak-provisioner.realm is required by quarkpress-keycloak-provisioner: the"
                    + " realm users are created in.");
        }
        String serverUrl = settings.get(ADMIN + "server-url");
        if (serverUrl != null && !serverUrl.regionMatches(true, 0, "https://", 0, "https://".length())) {
            warnings.add("quarkus.keycloak.admin-client.server-url does not use https: the provisioner sends password"
                    + " hashes and the admin client's credentials to Keycloak over it.");
        }
        return new Findings(errors, warnings);
    }

    /** Whether the grant type is the password grant, the admin client's default, in any form Quarkus reads. */
    private static boolean passwordGrant(String grantType) {
        if (grantType == null) {
            return true;
        }
        return grantType.trim().replace('-', '_').toUpperCase(Locale.ROOT).equals("PASSWORD");
    }
}
