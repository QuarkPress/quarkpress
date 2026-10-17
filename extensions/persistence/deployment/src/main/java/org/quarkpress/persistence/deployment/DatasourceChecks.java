package org.quarkpress.persistence.deployment;

import java.util.Optional;

/** Messages for datasource settings the build cannot work with. */
final class DatasourceChecks {

    static final String DB_KIND = "quarkus.datasource.\"quarkpress\".db-kind";
    static final String VENDOR = "quarkpress.persistence.migration-vendor";

    private DatasourceChecks() {}

    static String missingDatasource() {
        return "QuarkPress needs its own datasource named \"quarkpress\". Set " + DB_KIND
                + " to postgresql or mysql; in production also set quarkus.datasource.\"quarkpress\".jdbc.url,"
                + " quarkus.datasource.\"quarkpress\".username and quarkus.datasource.\"quarkpress\".password."
                + " It may point at the same database as the application's own datasource.";
    }

    static Optional<String> vendorProblem(String dbKind, String vendor) {
        if (vendor.equals("postgresql") || vendor.equals("mysql")) {
            return Optional.empty();
        }
        if (dbKind.equals("mariadb")) {
            return Optional.of("MariaDB uses the MySQL migrations of QuarkPress: set " + VENDOR + "=mysql");
        }
        String setting = vendor.equals(dbKind) ? "" : " and " + VENDOR + " is '" + vendor + "'";
        return Optional.of("QuarkPress has migrations for postgresql and mysql, but " + DB_KIND + " is '" + dbKind + "'"
                + setting + ". Use db-kind postgresql or mysql, or set " + VENDOR + " to one of them.");
    }
}
