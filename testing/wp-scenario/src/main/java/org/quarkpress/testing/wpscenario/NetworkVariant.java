package org.quarkpress.testing.wpscenario;

import java.util.List;
import java.util.Objects;

/**
 * A WordPress network the scenarios run on, and the QuarkPress network configured like it: the network address (the
 * main site's domain and path), the install type, and the path segment count (QuarkPress's
 * {@code quarkpress.multisite.path-segments}; the driver applies it to WordPress through the
 * {@code site_by_path_segments_count} filter).
 */
public record NetworkVariant(String name, String domain, String path, boolean subdomainInstall, int pathSegments) {

    public static final NetworkVariant SUBDIRECTORY = new NetworkVariant("subdirectory", "localhost", "/", false, 1);
    public static final NetworkVariant SUBDIRECTORY_UNDER_NET =
            new NetworkVariant("subdirectory-net", "localhost", "/net/", false, 1);
    public static final NetworkVariant SUBDOMAIN = new NetworkVariant("subdomain", "localhost", "/", true, 1);
    public static final NetworkVariant SUBDIRECTORY_TWO_SEGMENTS =
            new NetworkVariant("subdirectory-2", "localhost", "/", false, 2);

    public static final List<NetworkVariant> ALL =
            List.of(SUBDIRECTORY, SUBDIRECTORY_UNDER_NET, SUBDOMAIN, SUBDIRECTORY_TWO_SEGMENTS);

    public NetworkVariant {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(path, "path");
        ScenarioRules.requireId(name);
        if (pathSegments < 1) {
            throw new IllegalArgumentException("a network variant compares at least one path segment");
        }
    }

    public static NetworkVariant named(String name) {
        for (NetworkVariant variant : ALL) {
            if (variant.name().equals(name)) {
                return variant;
            }
        }
        throw new IllegalArgumentException("unknown network variant '" + name
                + "'; known: subdirectory, subdirectory-net, subdomain, subdirectory-2");
    }

    /** The database the variant's installation is kept in, as a template for the scenarios' databases. */
    public String templateDatabase() {
        StringBuilder database = new StringBuilder("tpl_");
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            database.append(c == '-' ? '_' : c);
        }
        return database.toString();
    }
}
