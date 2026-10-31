package org.quarkpress.persistence.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The options a new site starts with: the values WordPress stores for a new site, for the options QuarkPress uses, in
 * the JSON type of the value WordPress has after sanitizing it ({@code start_of_week} is the number 1,
 * {@code gmt_offset} the text "0"). WordPress itself reads every stored scalar back as text; QuarkPress keeps the types.
 * {@code default_category} is 0 until a module creates the default category. {@code use_smilies},
 * {@code default_post_format}, {@code site_icon} and {@code WPLANG} hold the texts WordPress stores ("1", "0", "0" and
 * ""). {@code WPLANG} is a new site's only: WordPress's installer writes none for an installation's first site, which
 * therefore reads the default language, so the migrations that seed the sites existing when they run leave it out.
 * {@code home}, {@code siteurl} and {@code blog_public} are not options in QuarkPress: they come from the site record.
 * The title is stored as given (WordPress stores it HTML-escaped).
 */
public final class SiteDefaults {

    private SiteDefaults() {}

    /** The default options of a site titled {@code title}, in WordPress's order; the values are new on every call. */
    public static Map<String, JsonNode> forNewSite(String title) {
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
        defaults.put("default_comment_status", TextNode.valueOf("open"));
        defaults.put("default_ping_status", TextNode.valueOf("open"));
        defaults.put("use_smilies", TextNode.valueOf("1"));
        defaults.put("default_post_format", TextNode.valueOf("0"));
        defaults.put("site_icon", TextNode.valueOf("0"));
        defaults.put("WPLANG", TextNode.valueOf(""));
        defaults.put(FeatureOptions.SITE, JsonNodeFactory.instance.objectNode());
        return Collections.unmodifiableMap(defaults);
    }
}
