package org.quarkpress.lib.wxr;

import java.util.Set;

/** Namespace URIs of WXR and the canonical prefixes the library names fields by ("wp:post_id", "dc:creator"). */
final class WxrNames {

    static final String WP_1_2 = "http://wordpress.org/export/1.2/";
    static final String EXCERPT_1_2 = "http://wordpress.org/export/1.2/excerpt/";
    static final String CONTENT = "http://purl.org/rss/1.0/modules/content/";
    static final String DC = "http://purl.org/dc/elements/1.1/";
    static final String WFW = "http://wellformedweb.org/CommentAPI/";

    /** The six URIs WordPress has bound to "wp": http and https, versions 1.0 to 1.2. */
    static final Set<String> WP = Set.of(
            "http://wordpress.org/export/1.0/",
            "http://wordpress.org/export/1.1/",
            "http://wordpress.org/export/1.2/",
            "https://wordpress.org/export/1.0/",
            "https://wordpress.org/export/1.1/",
            "https://wordpress.org/export/1.2/");

    static final Set<String> EXCERPT = Set.of(
            "http://wordpress.org/export/1.0/excerpt/",
            "http://wordpress.org/export/1.1/excerpt/",
            "http://wordpress.org/export/1.2/excerpt/",
            "https://wordpress.org/export/1.0/excerpt/",
            "https://wordpress.org/export/1.1/excerpt/",
            "https://wordpress.org/export/1.2/excerpt/");

    private WxrNames() {}

    /** "wp", "excerpt", "content" or "dc" for a URI WordPress uses; null for any other. */
    static String knownPrefix(String uri) {
        if (WP.contains(uri)) {
            return "wp";
        }
        if (EXCERPT.contains(uri)) {
            return "excerpt";
        }
        if (CONTENT.equals(uri)) {
            return "content";
        }
        if (DC.equals(uri)) {
            return "dc";
        }
        return null;
    }
}
