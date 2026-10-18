package org.quarkpress.multisite.deployment;

/**
 * The database the container-backed tests use: {@code -DtestDb=postgresql} (default) or {@code mysql}.
 * Maven passes the choice as the environment variable {@code QUARKPRESS_TEST_DB}, because the QuarkPress
 * namespace check fails the start on undeclared {@code quarkpress.*} system properties (environment variables are
 * not checked).
 * Same as the persistence deployment test helper of that name.
 */
public final class TestDb {

    private TestDb() {}

    public static String kind() {
        String kind = System.getenv("QUARKPRESS_TEST_DB");
        if (kind == null || kind.isBlank()) {
            kind = "postgresql";
        }
        if (!kind.equals("postgresql") && !kind.equals("mysql")) {
            throw new IllegalStateException("QUARKPRESS_TEST_DB must be postgresql or mysql, not " + kind);
        }
        return kind;
    }
}
