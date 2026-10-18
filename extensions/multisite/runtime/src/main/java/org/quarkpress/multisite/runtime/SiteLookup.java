package org.quarkpress.multisite.runtime;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.QuarkPressPersistence;
import org.quarkpress.persistence.runtime.QuarkPressRuntimeConfig;

/**
 * Looks sites up by address with plain JDBC on QuarkPress's datasource. It reads only network tables, needs no
 * request context or site binding, and never opens a session of the persistence unit, so resolvers may call it while
 * the request is not bound yet. Arguments are normalized.
 */
@ApplicationScoped
public class SiteLookup {

    /**
     * The most domains or paths one lookup compares. A lookup compares at most {@code MAX_PATH_LENGTH / 2 + 1} paths
     * (a candidate longer than a stored path is never built), so this is a generous upper bound that also covers the
     * most configurable path segments.
     */
    static final int MAX_CANDIDATES = SiteAddresses.MAX_PATH_LENGTH / 2 + MultisiteSettingsView.MAX_PATH_SEGMENTS + 2;

    /** The network's address: the main site's domain and path. */
    public record Address(String domain, String path) {}

    /** A matching alias, with the address of the site it belongs to. */
    public record AliasHit(SiteId site, boolean redirectToPrimary, String domain, String path) {}

    @Inject
    @DataSource(QuarkPressPersistence.UNIT)
    AgroalDataSource dataSource;

    @Inject
    QuarkPressRuntimeConfig config;

    private interface Row<T> {
        T read(ResultSet rows) throws SQLException;
    }

    public Optional<Address> network() {
        return query(
                "select domain, path from qp_site where id = ?",
                List.of(config.mainSiteId()),
                rows -> new Address(rows.getString(1), rows.getString(2)));
    }

    /** The alias among {@code domains}, the longest first. */
    public Optional<AliasHit> alias(List<String> domains) {
        String sql = "select d.site_id, d.redirect_to_primary, s.domain, s.path from qp_site_domain d"
                + " join qp_site s on s.id = d.site_id where d.domain in (" + marks(domains.size()) + ")"
                + " order by char_length(d.domain) desc limit 1";
        return query(
                sql,
                domains,
                rows -> new AliasHit(
                        SiteId.of(rows.getLong(1)), rows.getBoolean(2), rows.getString(3), rows.getString(4)));
    }

    /** The site on one of {@code domains} at one of {@code paths}: the longest domain, then the longest path, wins. */
    public Optional<SiteId> site(List<String> domains, List<String> paths) {
        String sql = "select id from qp_site where domain in (" + marks(domains.size()) + ") and path in ("
                + marks(paths.size()) + ") order by char_length(domain) desc, char_length(path) desc limit 1";
        List<Object> parameters = new ArrayList<>(domains);
        parameters.addAll(paths);
        return query(sql, parameters, rows -> SiteId.of(rows.getLong(1)));
    }

    static String marks(int count) {
        if (count < 1 || count > MAX_CANDIDATES) {
            throw new IllegalArgumentException("a site lookup compares 1 to " + MAX_CANDIDATES + " values: " + count);
        }
        return "?, ".repeat(count - 1) + "?";
    }

    private <T> Optional<T> query(String sql, List<?> parameters, Row<T> row) {
        if (EventLoops.onEventLoop()) {
            throw new IllegalStateException(
                    "QuarkPress looks sites up with blocking JDBC and must not do so on an event loop thread");
        }
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            for (int i = 0; i < parameters.size(); i++) {
                statement.setObject(i + 1, parameters.get(i));
            }
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(row.read(rows)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("QuarkPress could not look up sites: " + e.getMessage(), e);
        }
    }
}
