package org.quarkpress.lib.wxr;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Channel-level fields read so far; the first occurrence of each wins. */
final class SiteFields {

    static final Set<String> NAMES = Set.of(
            "title",
            "link",
            "description",
            "pubDate",
            "language",
            "generator",
            "wp:wxr_version",
            "wp:base_site_url",
            "wp:base_blog_url");

    private final Map<String, String> values = new HashMap<>();

    void putIfAbsent(String name, String value) {
        values.putIfAbsent(name, value);
    }

    String get(String name) {
        return values.get(name);
    }

    WxrSite snapshot() {
        return new WxrSite(
                get("title"),
                get("link"),
                get("description"),
                get("pubDate"),
                get("language"),
                get("wp:wxr_version"),
                get("wp:base_site_url"),
                get("wp:base_blog_url"),
                get("generator"));
    }
}
