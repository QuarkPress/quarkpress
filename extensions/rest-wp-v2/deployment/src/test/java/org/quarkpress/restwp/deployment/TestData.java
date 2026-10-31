package org.quarkpress.restwp.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import java.util.List;
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

    /** A profile with the given nicename, display name and e-mail address. */
    public static UserId profile(AgroalDataSource dataSource, String nicename, String displayName, String email) {
        String subject = nicename + "-" + UUID.randomUUID();
        Jdbc.update(
                dataSource,
                "insert into qp_user_profile (subject, display_name, nicename, email) values (?, ?, ?, ?)",
                subject,
                displayName,
                nicename,
                email);
        return UserId.of(Jdbc.count(dataSource, "select id from qp_user_profile where subject = ?", subject));
    }

    /** A post of {@code author} on {@code site}, written past the service. */
    public static PostId post(AgroalDataSource dataSource, SiteId site, UserId author, String type, String status) {
        PostId post = post(dataSource, site, type, status, "p-" + UUID.randomUUID(), "post");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
        return post;
    }

    /** Makes {@code user} a network administrator (a super admin). */
    public static void superAdmin(AgroalDataSource dataSource, UserId user) {
        Jdbc.update(dataSource, "insert into qp_network_admin (user_id) values (?)", user.value());
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

    /** The id of the term with {@code slug} in {@code taxonomy} on {@code site}; fails when there is none. */
    public static long termId(AgroalDataSource dataSource, SiteId site, String taxonomy, String slug) {
        List<String> ids = Jdbc.strings(
                dataSource,
                "select id from qp_term where site_id = ? and taxonomy = ? and slug = ?",
                site.value(),
                taxonomy,
                slug);
        if (ids.size() != 1) {
            throw new IllegalStateException("no single term " + taxonomy + "/" + slug + " on site " + site.value());
        }
        return Long.parseLong(ids.get(0));
    }

    /** The JSON text of option {@code name} of {@code site}, or null when the site has no such option. */
    public static String optionJson(AgroalDataSource dataSource, SiteId site, String name) {
        List<String> values = Jdbc.strings(
                dataSource, "select value from qp_option where site_id = ? and name = ?", site.value(), name);
        return values.isEmpty() ? null : values.get(0);
    }

    /** Deletes option {@code name} of {@code site}, past the services. */
    public static void deleteOption(AgroalDataSource dataSource, SiteId site, String name) {
        Jdbc.update(dataSource, "delete from qp_option where site_id = ? and name = ?", site.value(), name);
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
