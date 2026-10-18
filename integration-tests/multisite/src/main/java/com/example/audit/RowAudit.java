package com.example.audit;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Host-application code that counts a site's rows around the site filter, to check that deleting a site removes
 * them.
 */
@ApplicationScoped
public class RowAudit {

    private static final List<String> TABLES = List.of("qp_option", "qp_site_domain", "it_note", "it_note_tag");

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    public Map<String, Long> count(long site) {
        Map<String, Long> counts = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            for (String table : TABLES) {
                try (PreparedStatement statement =
                        connection.prepareStatement("select count(*) from " + table + " where site_id = ?")) {
                    statement.setLong(1, site);
                    try (ResultSet rows = statement.executeQuery()) {
                        rows.next();
                        counts.put(table, rows.getLong(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return counts;
    }
}
