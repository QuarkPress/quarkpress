package org.quarkpress.restwp.deployment;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource.DataSourceLiteral;
import io.quarkus.arc.Arc;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.quarkpress.core.SiteId;

/**
 * Plain JDBC reads that bypass Hibernate and its tenant filter. Same as the posts deployment test helper of that name,
 * plus shortcuts on QuarkPress's datasource of the running application.
 */
public final class Jdbc {

    private Jdbc() {}

    public static List<String> strings(DataSource dataSource, String sql, Object... params) {
        List<String> values = new ArrayList<>();
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, params);
                ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
        return values;
    }

    public static long count(DataSource dataSource, String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, params);
                ResultSet rows = statement.executeQuery()) {
            rows.next();
            return rows.getLong(1);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    public static int update(DataSource dataSource, String sql, Object... params) {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = prepare(connection, sql, params)) {
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** QuarkPress's datasource of the running application. */
    public static AgroalDataSource dataSource() {
        return Arc.container()
                .select(AgroalDataSource.class, new DataSourceLiteral("quarkpress"))
                .get();
    }

    /** Sets option {@code name} of {@code site} to {@code json} (a JSON text), past the services. */
    public static void option(SiteId site, String name, String json) {
        TestData.siteOption(dataSource(), site, name, json);
    }

    /** The meta of profile {@code user} as the database returns the JSON text. */
    public static String profileMeta(long user) {
        List<String> meta = strings(dataSource(), "select meta from qp_user_profile where id = ?", user);
        if (meta.isEmpty()) {
            throw new IllegalStateException("no profile " + user);
        }
        return meta.get(0);
    }

    private static PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        for (int i = 0; i < params.length; i++) {
            statement.setObject(i + 1, params[i]);
        }
        return statement;
    }
}
