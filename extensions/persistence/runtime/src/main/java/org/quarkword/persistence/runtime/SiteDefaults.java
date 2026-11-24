package org.quarkword.persistence.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * The options a new site starts with: the values WordPress stores for a new site, for the options QuarkWord uses, in
 * the JSON type of the value WordPress has after sanitizing it ({@code start_of_week} is the number 1,
 * {@code gmt_offset} the text "0"). WordPress itself reads every stored scalar back as text; QuarkWord keeps the types.
 * {@code default_category} is 0 until a module creates the default category. {@code use_smilies},
 * {@code default_post_format}, {@code site_icon} and {@code WPLANG} hold the texts WordPress stores ("1", "0", "0" and
 * ""). {@code WPLANG} is a new site's only: WordPress's installer writes none for an installation's first site, which
 * therefore reads the default language, so the migrations that seed the sites existing when they run leave it out.
 * {@code permalink_structure} is the pretty structure WordPress gives a network's new sites (QuarkWord can always
 * rewrite, so a single site gets it too instead of WordPress's installer's loopback test); {@code category_base} and
 * {@code tag_base} are empty, {@code wp_attachment_pages_enabled} the text "0" and {@code wp_page_for_privacy_policy}
 * the number 0. {@code home} and {@code siteurl} are the site's address ({@code scheme://domain/path}, no trailing
 * slash), so they need it: {@link #forNewSite(String, String)}. {@code blog_public} is not an option in QuarkWord: it
 * comes from the site record. The title is stored as given (WordPress stores it HTML-escaped).
 */
public final class SiteDefaults {

    private SiteDefaults() {}

    private static final String DEFAULT_PERMALINK_STRUCTURE = "/%year%/%monthnum%/%day%/%postname%/";

    /**
     * The default options of a site titled {@code title} without {@code home} and {@code siteurl}, in WordPress's
     * order; the values are new on every call.
     */
    public static Map<String, JsonNode> forNewSite(String title) {
        return Collections.unmodifiableMap(defaults(title));
    }

    /**
     * The default options of a site titled {@code title} at {@code address} (see {@link #address}), {@code home} and
     * {@code siteurl} included; the values are new on every call.
     */
    public static Map<String, JsonNode> forNewSite(String title, String address) {
        Objects.requireNonNull(address, "address");
        Map<String, JsonNode> defaults = new LinkedHashMap<>();
        defaults.put("siteurl", TextNode.valueOf(address));
        defaults.put("home", TextNode.valueOf(address));
        defaults.putAll(defaults(title));
        return Collections.unmodifiableMap(defaults);
    }

    /** The names of every default option, {@code home} and {@code siteurl} included. */
    public static Set<String> names() {
        return forNewSite("", "").keySet();
    }

    /**
     * A site's address as {@code home} and {@code siteurl} store it: {@code scheme://domain} followed by {@code path}
     * without its trailing slash (the root path {@code /} adds nothing).
     */
    public static String address(String scheme, String domain, String path) {
        Objects.requireNonNull(scheme, "scheme");
        Objects.requireNonNull(domain, "domain");
        Objects.requireNonNull(path, "path");
        int end = path.length();
        while (end > 0 && path.charAt(end - 1) == '/') {
            end--;
        }
        return scheme + "://" + domain + path.substring(0, end);
    }

    private static Map<String, JsonNode> defaults(String title) {
        Map<String, JsonNode> defaults = new LinkedHashMap<>();
        defaults.put("blogname", TextNode.valueOf(title));
        defaults.put("blogdescription", TextNode.valueOf(""));
        defaults.put("timezone_string", TextNode.valueOf(""));
        defaults.put("gmt_offset", TextNode.valueOf("0"));
        defaults.put("date_format", TextNode.valueOf("F j, Y"));
        defaults.put("time_format", TextNode.valueOf("g:i a"));
        defaults.put("start_of_week", IntNode.valueOf(1));
        defaults.put("posts_per_page", IntNode.valueOf(10));
        defaults.put("default_category", IntNode.valueOf(0));
        defaults.put("show_on_front", TextNode.valueOf("posts"));
        defaults.put("page_on_front", IntNode.valueOf(0));
        defaults.put("page_for_posts", IntNode.valueOf(0));
        defaults.put("permalink_structure", TextNode.valueOf(DEFAULT_PERMALINK_STRUCTURE));
        defaults.put("category_base", TextNode.valueOf(""));
        defaults.put("tag_base", TextNode.valueOf(""));
        defaults.put("wp_attachment_pages_enabled", TextNode.valueOf("0"));
        defaults.put("wp_page_for_privacy_policy", IntNode.valueOf(0));
        defaults.put("default_comment_status", TextNode.valueOf("open"));
        defaults.put("default_ping_status", TextNode.valueOf("open"));
        defaults.put("use_smilies", TextNode.valueOf("1"));
        defaults.put("default_post_format", TextNode.valueOf("0"));
        defaults.put("site_icon", TextNode.valueOf("0"));
        defaults.put("WPLANG", TextNode.valueOf(""));
        defaults.put(FeatureOptions.SITE, JsonNodeFactory.instance.objectNode());
        return defaults;
    }
}
