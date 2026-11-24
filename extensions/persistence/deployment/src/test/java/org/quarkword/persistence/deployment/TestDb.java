package org.quarkword.persistence.deployment;

/**
 * The database the container-backed tests use: {@code -DtestDb=postgresql} (default) or {@code mysql}.
 * Maven passes the choice as the environment variable {@code QUARKWORD_TEST_DB}, because the QuarkWord
 * namespace check fails the start on undeclared {@code quarkword.*} system properties (environment variables are
 * not checked).
 */
public final class TestDb {

    private TestDb() {}

    public static String kind() {
        String kind = System.getenv("QUARKWORD_TEST_DB");
        if (kind == null || kind.isBlank()) {
            kind = "postgresql";
        }
        if (!kind.equals("postgresql") && !kind.equals("mysql")) {
            throw new IllegalStateException("QUARKWORD_TEST_DB must be postgresql or mysql, not " + kind);
        }
        return kind;
    }
}
