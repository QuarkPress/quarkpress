package org.quarkpress.lib.wpkses;

import java.util.Objects;

/**
 * A named kses context, as WordPress passes to wp_kses_allowed_html. The built-in names resolve to WordPress's
 * tables; any other name resolves to the "data" table unless the {@link Kses.Builder} registers an allowlist for
 * it or an {@link AllowedHtmlFilter} changes it.
 */
public record KsesContext(String name) implements AllowedHtml {

    public static final KsesContext POST = new KsesContext("post");
    public static final KsesContext DATA = new KsesContext("data");
    public static final KsesContext STRIP = new KsesContext("strip");
    public static final KsesContext ENTITIES = new KsesContext("entities");
    public static final KsesContext USER_DESCRIPTION = new KsesContext("user_description");
    public static final KsesContext PRE_USER_DESCRIPTION = new KsesContext("pre_user_description");
    public static final KsesContext PRE_TERM_DESCRIPTION = new KsesContext("pre_term_description");
    public static final KsesContext PRE_COMMENT_CONTENT = new KsesContext("pre_comment_content");

    public KsesContext {
        Objects.requireNonNull(name, "name");
    }

    /** Any context name, for example the name of the WordPress filter a value comes from. */
    public static KsesContext named(String name) {
        return new KsesContext(name);
    }
}
