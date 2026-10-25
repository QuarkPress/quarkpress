package org.quarkpress.scheduling.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.SiteDefaults;

/** Rows written with plain JDBC: profiles, sites with their defaults, memberships, options and posts. */
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
        String domain = "scheduling-" + UUID.randomUUID() + ".test";
        Jdbc.update(dataSource, "insert into qp_site (domain, path, name) values (?, '/', 'scheduling')", domain);
        SiteId site = SiteId.of(Jdbc.count(dataSource, "select id from qp_site where domain = ?", domain));
        for (Map.Entry<String, JsonNode> option :
                SiteDefaults.forNewSite("scheduling").entrySet()) {
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

    /** Switches a per-site feature on for {@code site} through its stored switches. */
    public static void featureOn(AgroalDataSource dataSource, SiteId site, String key) {
        siteOption(dataSource, site, "qp_features", "{\"" + key + "\": true}");
    }

    /**
     * A scheduled post ({@code future}, type {@code post}) due at {@code publishedAtIso}, an ISO instant such as
     * {@code 2026-01-15T12:00:00Z}; created and modified now.
     */
    public static PostId scheduled(AgroalDataSource dataSource, SiteId site, String title, String publishedAtIso) {
        String guid = "guid-" + UUID.randomUUID();
        Jdbc.update(
                dataSource,
                "insert into qp_post (site_id, type, status, slug, title, content, excerpt, published_at,"
                        + " created_at, modified_at, comment_status, ping_status, guid) values (?, 'post', 'future',"
                        + " '', ?, '', '', " + TestDb.instant() + ", " + TestDb.now() + ", " + TestDb.now()
                        + ", 'open', 'open', ?)",
                site.value(),
                title,
                TestDb.instantText(publishedAtIso),
                guid);
        return PostId.of(Jdbc.count(dataSource, "select id from qp_post where guid = ?", guid));
    }

    /**
     * A trashed post whose meta remembers WordPress's trash time ({@code _wp_trash_meta_time}, seconds) and the
     * status it had ({@code publish}); {@code trashTimeOrNull} null writes an empty meta, as a row trashed past
     * the service would have.
     */
    public static PostId trashed(
            AgroalDataSource dataSource, SiteId site, String type, String title, Long trashTimeOrNull) {
        PostId post = post(dataSource, site, type, "trash", "", title);
        String meta = trashTimeOrNull == null
                ? "{}"
                : "{\"_wp_trash_meta_status\":[\"publish\"],\"_wp_trash_meta_time\":[" + trashTimeOrNull + "]}";
        setMeta(dataSource, post, meta);
        return post;
    }

    /** An auto-draft, never published, last modified at {@code modifiedAtIso} (an ISO instant). */
    public static PostId autoDraft(
            AgroalDataSource dataSource, SiteId site, String type, String title, String modifiedAtIso) {
        PostId post = post(dataSource, site, type, "auto-draft", "", title);
        Jdbc.update(dataSource, "update qp_post set published_at = null where id = ?", post.value());
        setModifiedAt(dataSource, post, modifiedAtIso);
        return post;
    }

    /** Replaces a post's meta with {@code json}, a JSON object text. */
    public static void setMeta(AgroalDataSource dataSource, PostId post, String json) {
        Jdbc.update(dataSource, "update qp_post set meta = " + TestDb.json() + " where id = ?", json, post.value());
    }

    /** Sets a post's modification time to {@code iso}, an ISO instant. */
    public static void setModifiedAt(AgroalDataSource dataSource, PostId post, String iso) {
        Jdbc.update(
                dataSource,
                "update qp_post set modified_at = " + TestDb.instant() + " where id = ?",
                TestDb.instantText(iso),
                post.value());
    }

    /** A post's status, read past the service; empty text when the row is gone. */
    public static String status(AgroalDataSource dataSource, PostId post) {
        List<String> statuses = Jdbc.strings(dataSource, "select status from qp_post where id = ?", post.value());
        return statuses.isEmpty() ? "" : statuses.get(0);
    }

    public static boolean exists(AgroalDataSource dataSource, PostId post) {
        return Jdbc.count(dataSource, "select count(*) from qp_post where id = ?", post.value()) > 0;
    }
}
