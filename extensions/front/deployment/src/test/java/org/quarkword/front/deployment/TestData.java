package org.quarkword.front.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import io.agroal.api.AgroalDataSource;
import java.util.Map;
import java.util.UUID;
import org.quarkword.core.SiteId;
import org.quarkword.persistence.runtime.SiteDefaults;

/** Rows written with plain JDBC: sites with their default options, and options. */
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
}
