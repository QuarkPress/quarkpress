package org.quarkpress.keycloak.provisioner.deployment;

import io.quarkus.test.QuarkusExtensionTest;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;
import java.util.Optional;
import java.util.UUID;
import org.quarkpress.security.NewUser;
import org.quarkpress.security.PasswordHash;

/**
 * Test applications with the security module and this provisioner on QuarkPress's datasource. Dev Services start the
 * database and Keycloak, which imports the security module's dev realm ({@value #REALM}); the provisioner works in that
 * realm through Dev Services' admin. No password-hash provider is loaded into Keycloak: these tests store hashes, they
 * never sign in with them.
 */
public final class ProvisionerApp {

    /** The realm Dev Services import. */
    public static final String REALM = "quarkpress";

    /** A phpass hash WordPress 7.1.3 made (the provider's fixture row phpass-ascii). */
    public static final String PHPASS = "$P$BQbwYReOXihWWJAdvqcXgGXK.RFhzU1";

    /** Another phpass hash WordPress 7.1.3 made (the provider's fixture row phpass-utf8). */
    public static final String PHPASS_OTHER = "$P$B3ErBeOHJwPVmhOOm6nYNFam34YTbM.";

    private ProvisionerApp() {}

    /** An application with Keycloak from Dev Services. */
    public static QuarkusExtensionTest app(Class<?>... classes) {
        return base(classes)
                // Keycloak can take more than the default minute to start on shared CI runners
                .overrideConfigKey("quarkus.devservices.timeout", "5M");
    }

    /** An application without Keycloak, whose admin client points at a local port nothing listens on. */
    public static QuarkusExtensionTest withoutKeycloak(Class<?>... classes) {
        return base(classes)
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideRuntimeConfigKey(
                        "quarkus.keycloak.admin-client.server-url", "http://127.0.0.1:" + closedPort());
    }

    private static QuarkusExtensionTest base(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(
                        jar -> jar.addClasses(classes).addClasses(ProvisionerApp.class, TestDb.class, Failures.class))
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0");
    }

    /** A local port that was free a moment ago: connections to it are refused at once. */
    static int closedPort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** A lower-case username no other test uses. */
    public static String username() {
        StringBuilder word = new StringBuilder("k");
        for (char c : UUID.randomUUID().toString().toCharArray()) {
            if (c >= 'a' && c <= 'f') {
                word.append(c);
            } else if (c >= '0' && c <= '9') {
                word.append((char) ('g' + (c - '0')));
            }
            if (word.length() == 12) {
                break;
            }
        }
        return word.toString();
    }

    /** John Doe with an e-mail of his own and no password hash, under {@code username}. */
    public static NewUser john(String username) {
        return new NewUser(
                username,
                Optional.of(username + "@example.com"),
                "John Doe",
                Optional.of("John"),
                Optional.of("Doe"),
                Optional.empty());
    }

    /** John Doe with an e-mail of his own and the WordPress hash {@code hash}, under {@code username}. */
    public static NewUser john(String username, String hash) {
        return new NewUser(
                username,
                Optional.of(username + "@example.com"),
                "John Doe",
                Optional.of("John"),
                Optional.of("Doe"),
                Optional.of(PasswordHash.wordpress(hash)));
    }
}
