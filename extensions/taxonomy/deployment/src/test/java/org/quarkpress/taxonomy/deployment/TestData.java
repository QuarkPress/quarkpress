package org.quarkpress.taxonomy.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.TermId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.SiteDefaults;

/** Rows written with plain JDBC: profiles, sites with their defaults, memberships, options, posts and terms. */
public final class TestData {

    /** The main site, which the persistence migrations create and the taxonomy migration gives its category. */
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

    /** A new site with the options WordPress gives a new site and no terms ({@code default_category} is 0). */
    public static SiteId bareSite(AgroalDataSource dataSource) {
        String domain = "terms-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qp_site (domain, path, name) values (?, '/', 'terms')", domain);
        SiteId site = SiteId.of(Jdbc.count(dataSource, "select id from qp_site where domain = ?", domain));
        for (Map.Entry<String, JsonNode> option :
                SiteDefaults.forNewSite("terms").entrySet()) {
            siteOption(dataSource, site, option.getKey(), option.getValue().toString());
        }
        return site;
    }

    /**
     * A new site on its own domain, with the options WordPress gives a new site and its Uncategorized category named by
     * {@code default_category}, as a site created through the multisite module has them.
     */
    public static SiteId site(AgroalDataSource dataSource) {
        SiteId site = bareSite(dataSource);
        TermId uncategorized = term(dataSource, site, "category", "Uncategorized", "uncategorized", null);
        siteOption(dataSource, site, "default_category", Long.toString(uncategorized.value()));
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

    /** An option's stored JSON text; empty when the site has no such option. */
    public static List<String> option(AgroalDataSource dataSource, SiteId site, String name) {
        return Jdbc.strings(
                dataSource, "select value from qp_option where site_id = ? and name = ?", site.value(), name);
    }

    /** The site's default category, as its {@code default_category} option names it. */
    public static TermId defaultCategory(AgroalDataSource dataSource, SiteId site) {
        return TermId.of(
                Long.parseLong(option(dataSource, site, "default_category").get(0)));
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

    /** A term row written past the service ({@code parent} null for none); its count is 0. */
    public static TermId term(
            AgroalDataSource dataSource, SiteId site, String taxonomy, String name, String slug, TermId parent) {
        Jdbc.update(
                dataSource,
                "insert into qp_term (site_id, taxonomy, name, slug, parent_id, description)"
                        + " values (?, ?, ?, ?, ?, '')",
                site.value(),
                taxonomy,
                name,
                slug,
                parent == null ? null : parent.value());
        return TermId.of(Jdbc.count(
                dataSource,
                "select id from qp_term where site_id = ? and taxonomy = ? and slug = ?",
                site.value(),
                taxonomy,
                slug));
    }

    /** An assignment row written past the service; counts are not touched. */
    public static void assign(AgroalDataSource dataSource, SiteId site, PostId post, TermId term) {
        Jdbc.update(
                dataSource,
                "insert into qp_term_assignment (site_id, post_id, term_id) values (?, ?, ?)",
                site.value(),
                post.value(),
                term.value());
    }
}
