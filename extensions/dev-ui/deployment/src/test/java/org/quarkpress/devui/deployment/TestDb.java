package org.quarkpress.devui.deployment;

/**
 * The database the container-backed tests use: {@code -DtestDb=postgresql} (default) or {@code mysql}, passed by Maven
 * as the environment variable {@code QUARKPRESS_TEST_DB}. Copied from the scheduling deployment test helper of that
 * name.
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
