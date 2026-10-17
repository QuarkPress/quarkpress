package org.quarkpress.lib.wxr;

import java.util.List;
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

    /**
     * The prefixes WordPress writes and the URIs they mean, for a document that uses them without declaring them.
     * The lenient pre-filter binds each undeclared one to {@link #UNDECLARED} plus the prefix, so that the reader can
     * tell which were bound for it and report those when they are used.
     */
    static final List<String> PREFIXES = List.of("wp", "excerpt", "content", "dc", "wfw");

    private static final List<String> PREFIX_URIS = List.of(WP_1_2, EXCERPT_1_2, CONTENT, DC, WFW);

    /** The URI base the pre-filter binds undeclared WordPress prefixes to; never one a document means. */
    static final String UNDECLARED = "urn:x-quarkpress:wxr:undeclared:";

    private WxrNames() {}

    /**
     * The prefix behind a URI the pre-filter bound to an undeclared prefix, or null for any other URI.
     */
    static String undeclaredPrefix(String uri) {
        if (!uri.startsWith(UNDECLARED)) {
            return null;
        }
        String prefix = uri.substring(UNDECLARED.length());
        return PREFIXES.contains(prefix) ? prefix : null;
    }

    /** The URI WordPress binds {@code prefix} (one of {@link #PREFIXES}) to. */
    static String uriOf(String prefix) {
        return PREFIX_URIS.get(PREFIXES.indexOf(prefix));
    }

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
