package org.quarkpress.revisions.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.SiteDefaults;

/** Rows written with plain JDBC: profiles, sites with their defaults, memberships, options, posts and revisions. */
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

    /** A new site on its own domain with the options WordPress gives a new site (the taxonomy module is absent). */
    public static SiteId site(AgroalDataSource dataSource) {
        String domain = "revisions-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qp_site (domain, path, name) values (?, '/', 'revisions')", domain);
        SiteId site = SiteId.of(Jdbc.count(dataSource, "select id from qp_site where domain = ?", domain));
        for (Map.Entry<String, JsonNode> option :
                SiteDefaults.forNewSite("revisions").entrySet()) {
            siteOption(dataSource, site, option.getKey(), option.getValue().toString());
        }
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

    /** Switches a per-site feature off for {@code site} through its stored switches. */
    public static void featureOff(AgroalDataSource dataSource, SiteId site, String key) {
        siteOption(dataSource, site, "qp_features", "{\"" + key + "\": false}");
    }

    /** A post row with WordPress-like defaults and no author, written past the service. */
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

    /** A post row by {@code author}, written past the service. */
    public static PostId postBy(
            AgroalDataSource dataSource, SiteId site, UserId author, String type, String status, String title) {
        PostId post = post(dataSource, site, type, status, "", title);
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
        return post;
    }

    /**
     * A revision row written past the service: {@code author} null for none, {@code meta} a JSON object text such as
     * {@code {"footnotes": ["[]"]}}, {@code createdAt} an ISO instant such as {@code 2026-01-15T12:00:00Z}. The
     * row is written with plain JDBC, so this computes {@code autosave_owner} as the entity does: the author of an
     * autosave, else null.
     */
    public static RevisionId revision(
            AgroalDataSource dataSource,
            SiteId site,
            PostId post,
            UserId author,
            String title,
            String content,
            String excerpt,
            String meta,
            boolean autosave,
            String createdAt) {
        String marker = "rev-" + UUID.randomUUID();
        Jdbc.update(
                dataSource,
                "insert into qp_revision (site_id, post_id, author_id, title, content, excerpt, meta, autosave,"
                        + " autosave_owner, created_at) values (?, ?, ?, ?, ?, ?, " + TestDb.json() + ", ?, ?, "
                        + TestDb.instant() + ")",
                site.value(),
                post.value(),
                author == null ? null : author.value(),
                title,
                content,
                excerpt + marker,
                meta,
                autosave,
                autosave && author != null ? author.value() : null,
                TestDb.instantText(createdAt));
        RevisionId id = RevisionId.of(Jdbc.count(
                dataSource,
                "select id from qp_revision where site_id = ? and excerpt = ?",
                site.value(),
                excerpt + marker));
        Jdbc.update(dataSource, "update qp_revision set excerpt = ? where id = ?", excerpt, id.value());
        return id;
    }
}
