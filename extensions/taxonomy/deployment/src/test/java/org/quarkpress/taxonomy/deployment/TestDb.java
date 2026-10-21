package org.quarkpress.taxonomy.deployment;

/**
 * The database the container-backed tests use: {@code -DtestDb=postgresql} (default) or {@code mysql}, passed by Maven
 * as the environment variable {@code QUARKPRESS_TEST_DB}. Same as the posts deployment test helper of that name.
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

    /** The placeholder for a JSON text parameter of plain JDBC: PostgreSQL needs a cast into its json type. */
    public static String json() {
        return kind().equals("postgresql") ? "cast(? as json)" : "?";
    }

    /** The current time as each database writes it into a timestamp column (UTC on MySQL). */
    public static String now() {
        return kind().equals("postgresql") ? "now()" : "utc_timestamp(6)";
    }
}
