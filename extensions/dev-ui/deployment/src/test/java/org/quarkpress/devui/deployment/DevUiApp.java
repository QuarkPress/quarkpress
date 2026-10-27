package org.quarkpress.devui.deployment;

import io.quarkus.test.QuarkusDevModeTest;
import io.quarkus.test.QuarkusExtensionTest;
import org.jboss.shrinkwrap.api.asset.StringAsset;

/**
 * Test applications of the dev-ui extension on QuarkPress's datasource; Dev Services starts the database. No Keycloak,
 * no OIDC tenant and no scheduler. {@link #dev} runs the application in dev mode, where the Dev UI exists;
 * {@link #nonDevelopment} runs it in test mode, where it must not.
 */
public final class DevUiApp {

    private DevUiApp() {}

    /** A dev-mode application holding {@code classes}. */
    public static QuarkusDevModeTest dev(Class<?>... classes) {
        String properties = "quarkus.datasource.\"quarkpress\".db-kind=" + TestDb.kind() + "\n"
                + "quarkus.datasource.devservices.enabled=false\n"
                + "quarkus.keycloak.devservices.enabled=false\n"
                + "quarkus.oidc.tenant-enabled=false\n"
                + "quarkus.scheduler.enabled=false\n"
                + "quarkus.http.port=0\n"
                + "quarkus.http.ssl-port=0\n";
        return new QuarkusDevModeTest()
                .withApplicationRoot(jar ->
                        jar.addClasses(classes).addAsResource(new StringAsset(properties), "application.properties"));
    }

    /** A test-mode (not development) application holding {@code classes} and the helpers its test class needs. */
    public static QuarkusExtensionTest nonDevelopment(Class<?>... classes) {
        return new QuarkusExtensionTest()
                .withApplicationRoot(jar -> jar.addClasses(classes).addClasses(DevUiApp.class, TestDb.class))
                .overrideConfigKey("quarkus.datasource.\"quarkpress\".db-kind", TestDb.kind())
                .overrideConfigKey("quarkus.datasource.devservices.enabled", "false")
                .overrideConfigKey("quarkus.keycloak.devservices.enabled", "false")
                .overrideConfigKey("quarkus.oidc.tenant-enabled", "false")
                .overrideConfigKey("quarkus.scheduler.enabled", "false")
                .overrideConfigKey("quarkus.http.test-port", "0")
                .overrideConfigKey("quarkus.http.test-ssl-port", "0");
    }
}
