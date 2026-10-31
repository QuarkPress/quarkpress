package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.quarkpress.restwp.SettingDefinition;

/**
 * The settings WordPress registers for REST in a default install, in its order, with its titles and descriptions:
 * the general, writing and reading settings, then the site logo and icon. The site URL and the administrator's e-mail
 * address are settings on a single site only. Whether the application is a network is decided when it is built.
 */
public final class BuiltInSettings {

    private static volatile boolean multisite;

    private BuiltInSettings() {}

    /** Records whether the multisite module is present and turned on; called once when the application starts. */
    public static void installMultisite(boolean enabled) {
        multisite = enabled;
    }

    static boolean multisite() {
        return multisite;
    }

    /**
     * Whether {@code setting} is the single-site {@code url}, the site's address. QuarkPress takes the address from the
     * site record, so the setting reads it from there and a write of it changes nothing (WordPress stores it in its
     * {@code siteurl} option, which moves the site).
     */
    static boolean isSiteAddress(SettingDefinition setting) {
        return setting.restName().equals("url") && setting.optionName().equals("siteurl");
    }

    /** WordPress's settings for a single site, or for a network's sites when {@code network}. */
    static List<SettingDefinition> of(boolean network) {
        List<SettingDefinition> settings = new ArrayList<>();
        settings.add(text("title", "blogname", "Title", "Site title."));
        settings.add(text("description", "blogdescription", "Tagline", "Site tagline."));
        if (!network) {
            settings.add(setting("url", "siteurl", "string", "", "Site URL.", null)
                    .format("uri")
                    .build());
            settings.add(setting(
                            "email",
                            "admin_email",
                            "string",
                            "",
                            "This address is used for admin purposes, like new user notification.",
                            null)
                    .format("email")
                    .build());
        }
        settings.add(text("timezone", "timezone_string", "", "A city in the same timezone as you."));
        settings.add(text("date_format", "date_format", "", "A date format for all date strings."));
        settings.add(text("time_format", "time_format", "", "A time format for all time strings."));
        settings.add(integer(
                "start_of_week", "start_of_week", "", "A day number of the week that the week should start on."));
        settings.add(setting("language", "WPLANG", "string", "", "WordPress locale code.", TextNode.valueOf("en_US"))
                .build());
        settings.add(setting(
                        "use_smilies",
                        "use_smilies",
                        "boolean",
                        "",
                        "Convert emoticons like :-) and :-P to graphics on display.",
                        BooleanNode.TRUE)
                .build());
        settings.add(integer("default_category", "default_category", "", "Default post category."));
        settings.add(text("default_post_format", "default_post_format", "", "Default post format."));
        settings.add(setting(
                        "posts_per_page",
                        "posts_per_page",
                        "integer",
                        "Maximum posts per page",
                        "Blog pages show at most.",
                        IntNode.valueOf(10))
                .build());
        settings.add(text("show_on_front", "show_on_front", "Show on front", "What to show on the front page"));
        settings.add(integer(
                "page_on_front",
                "page_on_front",
                "Page on front",
                "The ID of the page that should be displayed on the front page"));
        settings.add(integer(
                "page_for_posts", "page_for_posts", "", "The ID of the page that should display the latest posts"));
        settings.add(setting(
                        "default_ping_status",
                        "default_ping_status",
                        "string",
                        "",
                        "Allow link notifications from other blogs (pingbacks and trackbacks) on new articles.",
                        null)
                .openOrClosed()
                .build());
        settings.add(setting(
                        "default_comment_status",
                        "default_comment_status",
                        "string",
                        "Allow comments on new posts",
                        "Allow people to submit comments on new posts.",
                        null)
                .openOrClosed()
                .build());
        settings.add(integer("site_logo", "site_logo", "Logo", "Site logo."));
        settings.add(integer("site_icon", "site_icon", "Icon", "Site icon."));
        return List.copyOf(settings);
    }

    private static SettingDefinition text(String name, String option, String title, String description) {
        return setting(name, option, "string", title, description, null).build();
    }

    private static SettingDefinition integer(String name, String option, String title, String description) {
        return setting(name, option, "integer", title, description, null).build();
    }

    private static Draft setting(
            String name, String option, String type, String title, String description, JsonNode defaultValue) {
        ObjectNode schema = JsonNodeFactory.instance.objectNode();
        schema.put("title", title);
        schema.put("description", description);
        return new Draft(name, option, type, schema, defaultValue);
    }

    private record Draft(String name, String option, String type, ObjectNode schema, JsonNode defaultValue) {

        Draft format(String format) {
            schema.put("format", format);
            return this;
        }

        Draft openOrClosed() {
            schema.putArray("enum").add("open").add("closed");
            return this;
        }

        SettingDefinition build() {
            return new SettingDefinition(name, option, type, Optional.ofNullable(defaultValue), schema, true);
        }
    }
}
