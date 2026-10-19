package org.quarkpress.testing.wpscenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * What one side observed in a scenario, normalized for comparison: one text value per aspect, in recording order.
 * Aspects are {@code op<n>:<kind>} (and {@code op<n>:read_options:<name>}) for the n-th operation, and
 * {@code site#<k>} for the final record of the k-th created site. Sites are named by creation order: {@code #0} is the
 * main site.
 */
public final class Outcome {

    /** A lookup that found no site (WordPress's redirect and "not installed" both map to it:
     * QuarkPress answers "no site" where WordPress redirects or reports not installed; a deliberate difference). */
    public static final String NONE = "none";

    private final Map<String, String> facts = new LinkedHashMap<>();

    public void created(int op, int site) {
        put("op" + op + ":create_site", "created " + siteName(site));
    }

    public void refused(int op, String category) {
        put("op" + op + ":create_site", "refused:" + category);
    }

    public void lookedUp(int op, String site) {
        put("op" + op + ":lookup", site);
    }

    /** {@code ok}, {@code failed}, or {@code missing} when the site was never created. */
    public void flagSet(int op, String result) {
        put("op" + op + ":set_flag", result);
    }

    /** An option's value as text; null for a missing option or site. */
    public void option(int op, String name, String text) {
        put("op" + op + ":read_options:" + name, text == null ? "(missing)" : text);
    }

    /** A user WordPress created, with the nicename and display name it stored. */
    public void userCreated(int op, String nicename, String displayName) {
        put("op" + op + ":create_user", "nicename=" + nicename + " display=" + displayName);
    }

    /** A user WordPress refused to create, with its error code. */
    public void userRefused(int op, String code) {
        put("op" + op + ":create_user", "refused:" + code);
    }

    /** The result of a user, role or option change: ok, failed, missing, or changed=true|false. */
    public void done(int op, String kind, String result) {
        put("op" + op + ":" + kind, result);
    }

    public void answer(int op, boolean can) {
        put("op" + op + ":can", can ? "yes" : "no");
    }

    /**
     * A nicename QuarkPress derived from its profile id ({@code user-<id>}, with any suffix) shown as
     * {@code user-#<index>}: the two sides number users differently. Scenario usernames never read as such a
     * nicename (the rules refuse them), so only fallback nicenames are rewritten.
     */
    public static String nicename(String nicename, long id, int index) {
        String own = "user-" + id;
        if (nicename.equals(own) || nicename.startsWith(own + "-")) {
            return "user-#" + index + nicename.substring(own.length());
        }
        return nicename;
    }

    public void site(
            int index,
            String domain,
            String path,
            int publicFlag,
            boolean archived,
            boolean mature,
            boolean spam,
            boolean deleted) {
        put(
                "site" + siteName(index),
                domain + path + " public=" + publicFlag + " archived=" + bit(archived) + " mature=" + bit(mature)
                        + " spam=" + bit(spam) + " deleted=" + bit(deleted));
    }

    public Map<String, String> facts() {
        return Collections.unmodifiableMap(facts);
    }

    public static String siteName(int index) {
        return "#" + index;
    }

    /** A default title {@code Site <id>} is shown as {@code Site #<index>}: the two sides number sites differently. */
    public static String title(String value, long id, int index) {
        return value.equals("Site " + id) ? "Site " + siteName(index) : value;
    }

    private void put(String aspect, String value) {
        if (facts.putIfAbsent(aspect, value) != null) {
            throw new IllegalStateException("the aspect " + aspect + " was recorded twice");
        }
    }

    private static String bit(boolean value) {
        return value ? "1" : "0";
    }
}
