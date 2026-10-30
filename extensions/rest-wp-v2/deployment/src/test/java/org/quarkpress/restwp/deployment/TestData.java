package org.quarkpress.restwp.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import java.util.Map;
import java.util.UUID;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.SiteDefaults;

/** Rows written with plain JDBC: profiles, sites with their default options, memberships, options and posts. */
public final class TestData {

    /** The main site, which the persistence migrations create. */
    public static final SiteId MAIN = SiteId.of(1);

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

    /** A membership with {@code roles}, a JSON array such as {@code ["editor"]}. */
    public static void member(AgroalDataSource dataSource, SiteId site, UserId user, String roles) {
        Jdbc.update(
                dataSource,
                "insert into qp_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + TestDb.json()
                        + ", '{}')",
                site.value(),
                user.value(),
                roles);
    }

    /** A user who is a member of the main site with {@code role}. */
    public static UserId memberOfMain(AgroalDataSource dataSource, String role) {
        UserId user = profile(dataSource, role);
        member(dataSource, MAIN, user, "[\"" + role + "\"]");
        return user;
    }

    /** A new site on its own domain, with the options WordPress gives a new site. */
    public static SiteId site(AgroalDataSource dataSource) {
        String domain = "rest-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qp_site (domain, path, name) values (?, '/', 'rest')", domain);
        SiteId site = SiteId.of(Jdbc.count(dataSource, "select id from qp_site where domain = ?", domain));
        for (Map.Entry<String, JsonNode> option :
                SiteDefaults.forNewSite("rest").entrySet()) {
            siteOption(dataSource, site, option.getKey(), option.getValue().toString());
        }
        return site;
    }

    /** A new site whose time zone option names {@code zone}. */
    public static SiteId siteIn(AgroalDataSource dataSource, String zone) {
        SiteId site = site(dataSource);
        siteOption(dataSource, site, "timezone_string", "\"" + zone + "\"");
        return site;
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

    /** A post row with WordPress-like defaults, written past the service. */
    public static PostId post(
            AgroalDataSource dataSource, SiteId site, String type, String status, String slug, String title) {
        String guid = "guid-" + UUID.randomUUID();
        Jdbc.update(
                dataSource,
                "insert into qp_post (site_id, type, status, slug, title, content, excerpt, created_at, modified_at,"
                        + " comment_status, ping_status, guid) values (?, ?, ?, ?, ?, '', '', " + TestDb.now() + ", "
                        + TestDb.now() + ", 'open', 'open', ?)",
                site.value(),
                type,
                status,
                slug,
                title,
                guid);
        return PostId.of(Jdbc.count(dataSource, "select id from qp_post where guid = ?", guid));
    }
}
