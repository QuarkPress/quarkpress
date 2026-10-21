package org.quarkpress.testing.wpscenario;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What one side observed in a scenario, normalized for comparison: one text value per aspect, in recording order.
 * Aspects are {@code op<n>:<kind>} (and {@code op<n>:read_options:<name>}) for the n-th operation, and
 * {@code site#<k>} for the final record of the k-th created site. Sites are named by creation order: {@code #0} is the
 * main site. Post scenarios add {@code op<n>:read_post:<field>} and {@code post#<k>:<field>}, the final record of the
 * k-th inserted post; posts are named {@code p<k>} by insertion order. Term scenarios add
 * {@code op<n>:get_term:<field>} and {@code term#<ref>:<field>}, the final record of a created term ({@code t<k>}) or a
 * default term ({@code default-category}, {@code default-genre}).
 */
public final class Outcome {

    /** A lookup that found no site (WordPress's redirect and "not installed" both map to it:
     * QuarkPress answers "no site" where WordPress redirects or reports not installed; a deliberate difference). */
    public static final String NONE = "none";

    /**
     * What WordPress's trash, untrash and delete answer when they did nothing (false or null); the other answer is
     * {@code done:<status before>}.
     */
    public static final String NOTHING = "nothing";

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

    /** A post inserted, named by its insertion index. */
    public void postCreated(int op, int index) {
        put("op" + op + ":insert_post", "created p" + index);
    }

    /** A query's posts, by reference, and the number it counts as found; no posts show as {@code (none)}. */
    public void queried(int op, List<String> refs, long found) {
        put("op" + op + ":query_posts", (refs.isEmpty() ? "(none)" : String.join(",", refs)) + " found=" + found);
    }

    /** A post record: one aspect per field, named {@code <prefix>:<field>}. */
    public void observed(String prefix, ObservedPost post) {
        post.fields().forEach((field, value) -> put(prefix + ":" + field, value));
    }

    /** A term created, named by creation order. */
    public void termCreated(int op, String ref) {
        put("op" + op + ":insert_term", "created " + ref);
    }

    /**
     * Terms an op answered, by reference ({@code <ref>=<count>} where the op reports counts: post_terms and
     * query_terms); none show as {@code (none)}.
     */
    public void termRefs(int op, String kind, List<String> refs) {
        put("op" + op + ":" + kind, refs.isEmpty() ? "(none)" : String.join(",", refs));
    }

    /** A term record: one aspect per field, named {@code <prefix>:<field>}. */
    public void observedTerm(String prefix, ObservedTerm term) {
        term.fields().forEach((field, value) -> put(prefix + ":" + field, value));
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
