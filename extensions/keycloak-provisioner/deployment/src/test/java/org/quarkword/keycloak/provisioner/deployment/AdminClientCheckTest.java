package org.quarkword.keycloak.provisioner.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.runtime.LaunchMode;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkword.keycloak.provisioner.runtime.AdminClientCheck;
import org.quarkword.keycloak.provisioner.runtime.AdminClientCheck.Findings;

/**
 * The start-up check of the admin client's settings: in production it refuses the extension's default credentials
 * and an unset realm, and passes a client-credentials setup; in dev and test it checks nothing.
 */
class AdminClientCheckTest {

    private static final String ADMIN = "quarkus.keycloak.admin-client.";

    private static Map<String, String> clientCredentials() {
        Map<String, String> settings = new HashMap<>();
        settings.put(ADMIN + "server-url", "https://id.example.com");
        settings.put(ADMIN + "realm", "site");
        settings.put(ADMIN + "client-id", "quarkword-provisioner");
        settings.put(ADMIN + "client-secret", "s3cret");
        settings.put(ADMIN + "grant-type", "CLIENT_CREDENTIALS");
        settings.put("quarkword.keycloak-provisioner.realm", "site");
        return settings;
    }

    @Test
    void aClientCredentialsSetupPasses() {
        Findings findings = AdminClientCheck.check(LaunchMode.NORMAL, clientCredentials());
        assertThat(findings.errors()).isEmpty();
        assertThat(findings.warnings()).isEmpty();
    }

    @Test
    void theGrantTypeIsReadInEveryFormQuarkusAccepts() {
        for (String form : new String[] {"client_credentials", "client-credentials", "Client_Credentials"}) {
            Map<String, String> settings = clientCredentials();
            settings.put(ADMIN + "grant-type", form);
            assertThat(AdminClientCheck.check(LaunchMode.NORMAL, settings).errors())
                    .as(form)
                    .isEmpty();
        }
    }

    @Test
    void theDefaultAdminCredentialsAreRefusedInProduction() {
        Map<String, String> settings = new HashMap<>();
        settings.put(ADMIN + "server-url", "https://id.example.com");
        settings.put("quarkword.keycloak-provisioner.realm", "site");

        Findings findings = AdminClientCheck.check(LaunchMode.NORMAL, settings);

        assertThat(findings.errors()).hasSize(1);
        assertThat(findings.errors().get(0))
                .contains("quarkus.keycloak.admin-client")
                .contains("default")
                .contains("CLIENT_CREDENTIALS");
    }

    @Test
    void theDefaultCredentialsSetExplicitlyAreRefusedToo() {
        Map<String, String> settings = clientCredentials();
        settings.put(ADMIN + "grant-type", "PASSWORD");
        settings.put(ADMIN + "username", "admin");
        settings.put(ADMIN + "password", "admin");
        assertThat(AdminClientCheck.check(LaunchMode.NORMAL, settings).errors()).hasSize(1);

        settings.put(ADMIN + "grant-type", "password");
        assertThat(AdminClientCheck.check(LaunchMode.NORMAL, settings).errors()).hasSize(1);
    }

    @Test
    void aPasswordGrantWithOtherCredentialsPasses() {
        Map<String, String> settings = clientCredentials();
        settings.put(ADMIN + "grant-type", "PASSWORD");
        settings.put(ADMIN + "username", "provisioner");
        settings.put(ADMIN + "password", "long-and-random");
        assertThat(AdminClientCheck.check(LaunchMode.NORMAL, settings).errors()).isEmpty();
    }

    @Test
    void theAdminUsernameWithAnotherPasswordIsAWarning() {
        Map<String, String> settings = clientCredentials();
        settings.put(ADMIN + "grant-type", "PASSWORD");
        settings.put(ADMIN + "username", "admin");
        settings.put(ADMIN + "password", "long-and-random");

        Findings findings = AdminClientCheck.check(LaunchMode.NORMAL, settings);

        assertThat(findings.errors()).isEmpty();
        assertThat(findings.warnings()).hasSize(1);
        assertThat(findings.warnings().get(0))
                .contains("quarkus.keycloak.admin-client.username")
                .contains("CLIENT_CREDENTIALS")
                .doesNotContain("long-and-random");

        // the default password too is an error, not also a warning
        settings.remove(ADMIN + "password");
        Findings defaults = AdminClientCheck.check(LaunchMode.NORMAL, settings);
        assertThat(defaults.errors()).hasSize(1);
        assertThat(defaults.warnings()).isEmpty();

        // the client-credentials grant ignores the username
        settings.put(ADMIN + "grant-type", "CLIENT_CREDENTIALS");
        assertThat(AdminClientCheck.check(LaunchMode.NORMAL, settings).warnings())
                .isEmpty();
    }

    @Test
    void anUnsetRealmIsRefusedInProduction() {
        Map<String, String> settings = clientCredentials();
        settings.remove("quarkword.keycloak-provisioner.realm");

        Findings findings = AdminClientCheck.check(LaunchMode.NORMAL, settings);

        assertThat(findings.errors()).hasSize(1);
        assertThat(findings.errors().get(0)).contains("quarkword.keycloak-provisioner.realm");

        settings.put("quarkword.keycloak-provisioner.realm", " ");
        assertThat(AdminClientCheck.check(LaunchMode.NORMAL, settings).errors()).hasSize(1);
    }

    @Test
    void bothProblemsAreReportedTogether() {
        assertThat(AdminClientCheck.check(LaunchMode.NORMAL, Map.of()).errors()).hasSize(2);
    }

    @Test
    void aServerWithoutHttpsIsAWarningOnly() {
        Map<String, String> settings = clientCredentials();
        settings.put(ADMIN + "server-url", "http://keycloak:8080");

        Findings findings = AdminClientCheck.check(LaunchMode.NORMAL, settings);

        assertThat(findings.errors()).isEmpty();
        assertThat(findings.warnings()).hasSize(1);
        assertThat(findings.warnings().get(0)).contains("https").contains("quarkus.keycloak.admin-client.server-url");
        assertThat(findings.warnings().get(0)).doesNotContain("s3cret");

        settings.put(ADMIN + "server-url", "HTTPS://ID.EXAMPLE.COM");
        assertThat(AdminClientCheck.check(LaunchMode.NORMAL, settings).warnings())
                .isEmpty();
    }

    @Test
    void devAndTestAreNotChecked() {
        for (LaunchMode mode : new LaunchMode[] {LaunchMode.DEVELOPMENT, LaunchMode.TEST}) {
            Findings findings = AdminClientCheck.check(mode, Map.of(ADMIN + "server-url", "http://localhost:8180"));
            assertThat(findings.errors()).as(mode.name()).isEmpty();
            assertThat(findings.warnings()).as(mode.name()).isEmpty();
        }
    }
}
