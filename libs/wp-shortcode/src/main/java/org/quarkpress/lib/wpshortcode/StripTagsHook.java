package org.quarkpress.lib.wpshortcode;

import java.util.List;

/** WordPress's strip_shortcodes_tagnames filter. */
@FunctionalInterface
public interface StripTagsHook {

    /**
     * Returns the names {@link Shortcodes#strip(String)} removes; they need not be registered. The input list
     * is unmodifiable: return a new list. Must not return null or a list containing null.
     */
    List<String> filter(List<String> tags, String content);
}
