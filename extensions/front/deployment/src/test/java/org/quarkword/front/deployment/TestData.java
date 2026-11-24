package org.quarkword.front.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteId;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;
import org.quarkword.persistence.runtime.SiteDefaults;

/** Rows written with plain JDBC: sites with their default options, options, profiles, posts and terms. */
public final class TestData {

    /** The main site, which the persistence migrations create. */
    public static final SiteId MAIN = SiteId.of(1);

    private TestData() {}

    /**
     * A new site on its own domain, with the options WordPress gives a new site; {@code home} and {@code siteurl} are
     * {@code http://<domain>}.
     */
    public static SiteId site(AgroalDataSource dataSource) {
        String domain = "front-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qw_site (domain, path, name) values (?, '/', 'front')", domain);
        SiteId site = SiteId.of(Jdbc.count(dataSource, "select id from qw_site where domain = ?", domain));
        for (Map.Entry<String, JsonNode> option :
                SiteDefaults.forNewSite("front", "http://" + domain).entrySet()) {
            siteOption(dataSource, site, option.getKey(), option.getValue().toString());
        }
        return site;
    }

    /** The domain of {@code site}, for a request's {@code Host} header. */
    public static String domain(AgroalDataSource dataSource, SiteId site) {
        return Jdbc.strings(dataSource, "select domain from qw_site where id = ?", site.value())
                .get(0);
    }

    public static void siteOption(AgroalDataSource dataSource, SiteId site, String name, String json) {
        Jdbc.update(dataSource, "delete from qw_option where site_id = ? and name = ?", site.value(), name);
        Jdbc.update(
                dataSource,
                "insert into qw_option (site_id, name, value, autoload) values (?, ?, " + TestDb.json() + ", true)",
                site.value(),
                name,
                json);
    }

    /** A text option: {@code value} as a JSON string. */
    public static void textOption(AgroalDataSource dataSource, SiteId site, String name, String value) {
        siteOption(dataSource, site, name, "\"" + value + "\"");
    }

    /** Removes the option {@code name} of {@code site}. */
    public static void dropOption(AgroalDataSource dataSource, SiteId site, String name) {
        Jdbc.update(dataSource, "delete from qw_option where site_id = ? and name = ?", site.value(), name);
    }

    /** A profile with this display name and nicename (nicenames are unique across the installation). */
    public static UserId profile(AgroalDataSource dataSource, String displayName, String nicename) {
        String subject = displayName + "-" + UUID.randomUUID();
        Jdbc.update(
                dataSource,
                "insert into qw_user_profile (subject, display_name, nicename) values (?, ?, ?)",
                subject,
                displayName,
                nicename);
        return UserId.of(Jdbc.count(dataSource, "select id from qw_user_profile where subject = ?", subject));
    }

    /** A membership with {@code roles}, a JSON array such as {@code ["author"]}. */
    public static void member(AgroalDataSource dataSource, SiteId site, UserId user, String roles) {
        Jdbc.update(
                dataSource,
                "insert into qw_site_membership (site_id, user_id, roles, capabilities) values (?, ?, " + TestDb.json()
                        + ", '{}')",
                site.value(),
                user.value(),
                roles);
    }

    /**
     * A post row written past the service, dated {@code dateIso} (an ISO instant such as {@code 2025-11-20T10:15:30Z}):
     * a published-like post has it as {@code published_at}; a post whose date floats ({@code floating}) has no
     * {@code published_at} and the date as its modification time. {@code author} and {@code parent} may be null.
     */
    public static PostId post(
            AgroalDataSource dataSource,
            SiteId site,
            String type,
            String status,
            String slug,
            UserId author,
            PostId parent,
            String dateIso,
            boolean floating) {
        String guid = "guid-" + UUID.randomUUID();
        List<Object> params = new ArrayList<>(List.of(site.value(), type, status, slug, slug));
        params.add(author == null ? null : author.value());
        params.add(parent == null ? null : parent.value());
        if (!floating) {
            params.add(TestDb.instantText(dateIso));
        }
        params.add(TestDb.instantText(dateIso));
        params.add(TestDb.instantText(dateIso));
        params.add(guid);
        Jdbc.update(
                dataSource,
                "insert into qw_post (site_id, type, status, slug, title, content, excerpt, author_id, parent_id,"
                        + " published_at, created_at, modified_at, comment_status, ping_status, guid) values"
                        + " (?, ?, ?, ?, ?, '', '', ?, ?, " + (floating ? "null" : TestDb.instant()) + ", "
                        + TestDb.instant() + ", " + TestDb.instant() + ", 'open', 'open', ?)",
                params.toArray());
        return PostId.of(Jdbc.count(dataSource, "select id from qw_post where guid = ?", guid));
    }

    /** A term row written past the service ({@code parent} null for none). */
    public static TermId term(
            AgroalDataSource dataSource, SiteId site, String taxonomy, String name, String slug, TermId parent) {
        Jdbc.update(
                dataSource,
                "insert into qw_term (site_id, taxonomy, name, slug, parent_id, description)"
                        + " values (?, ?, ?, ?, ?, '')",
                site.value(),
                taxonomy,
                name,
                slug,
                parent == null ? null : parent.value());
        return TermId.of(Jdbc.count(
                dataSource,
                "select id from qw_term where site_id = ? and taxonomy = ? and slug = ?",
                site.value(),
                taxonomy,
                slug));
    }

    /** An assignment row written past the service; counts are not touched. */
    public static void assign(AgroalDataSource dataSource, SiteId site, PostId post, TermId term) {
        Jdbc.update(
                dataSource,
                "insert into qw_term_assignment (site_id, post_id, term_id) values (?, ?, ?)",
                site.value(),
                post.value(),
                term.value());
    }
}
