package org.quarkpress.security.deployment;

import io.agroal.api.AgroalDataSource;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/** Rows written with plain JDBC for capability tests: profiles, sites, memberships, options, network admins. */
public final class TestData {

    private TestData() {}

    public static UserId profile(AgroalDataSource dataSource, String label) {
        String subject = label + "-" + UUID.randomUUID();
        Jdbc.update(
                dataSource,
                "insert into qp_user_profile (subject, display_name, nicename) values (?, ?, ?)",
                subject,
                label,
                "n-" + UUID.randomUUID().toString().replace("-", "").substring(0, 30));
        return UserId.of(Jdbc.count(dataSource, "select id from qp_user_profile where subject = ?", subject));
    }

    public static String subject(AgroalDataSource dataSource, UserId user) {
        return Jdbc.strings(dataSource, "select subject from qp_user_profile where id = ?", user.value())
                .get(0);
    }

    public static SiteId site(AgroalDataSource dataSource) {
        String domain = "caps-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qp_site (domain, path, name) values (?, '/', 'caps')", domain);
        return SiteId.of(Jdbc.count(dataSource, "select id from qp_site where domain = ?", domain));
    }

    /** A membership with {@code roles} (a JSON array) and {@code capabilities} (a JSON object). */
    public static void member(
            AgroalDataSource dataSource, SiteId site, UserId user, String roles, String capabilities) {
        Jdbc.update(
                dataSource,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + TestDb.json()
                        + ", " + TestDb.json() + ")",
                site.value(),
                user.value(),
                roles,
                capabilities);
    }

    public static void siteOption(AgroalDataSource dataSource, SiteId site, String name, String json) {
        Jdbc.update(dataSource, "delete from qp_option where site_id = ? and name = ?", site.value(), name);
        Jdbc.update(
                dataSource,
                "insert into qp_option (site_id, name, value, autoload) values (?, ?, " + TestDb.json() + ", true)",
                site.value(),
                name,
                json);
    }

    public static void networkOption(AgroalDataSource dataSource, String name, String json) {
        Jdbc.update(dataSource, "delete from qp_network_option where name = ?", name);
        Jdbc.update(
                dataSource,
                "insert into qp_network_option (name, value) values (?, " + TestDb.json() + ")",
                name,
                json);
    }

    public static void networkAdmin(AgroalDataSource dataSource, UserId user) {
        Jdbc.update(dataSource, "insert into qp_network_admin (user_id) values (?)", user.value());
    }

    /** A network option's stored JSON text, if any. */
    public static Optional<String> networkOptionValue(AgroalDataSource dataSource, String name) {
        String sql = TestDb.kind().equals("postgresql")
                ? "select cast(value as text) from qp_network_option where name = ?"
                : "select value from qp_network_option where name = ?";
        List<String> values = Jdbc.strings(dataSource, sql, name);
        return values.isEmpty() ? Optional.empty() : Optional.of(values.get(0));
    }

    /** Puts a network option back as it was: its earlier value, or absent. */
    public static void restoreNetworkOption(AgroalDataSource dataSource, String name, Optional<String> earlier) {
        if (earlier.isPresent()) {
            networkOption(dataSource, name, earlier.get());
        } else {
            Jdbc.update(dataSource, "delete from qp_network_option where name = ?", name);
        }
    }

    /** The stored creation instant of a membership, computed by the database (MySQL stores UTC wall time). */
    public static Instant membershipCreatedAt(AgroalDataSource dataSource, SiteId site, UserId user) {
        String micros = TestDb.kind().equals("postgresql")
                ? "select cast(round(extract(epoch from created_at) * 1000000) as bigint)"
                : "select timestampdiff(microsecond, '1970-01-01 00:00:00', created_at)";
        long value = Jdbc.count(
                dataSource,
                micros + " from qp_site_membership where site_id = ? and user_id = ?",
                site.value(),
                user.value());
        return Instant.EPOCH.plus(value, ChronoUnit.MICROS);
    }
}
