package org.quarkpress.lib.wpshortcode;

import java.util.List;

/** WordPress's strip_shortcodes_tagnames filter. */
@FunctionalInterface
public interface StripTagsHook {

    /** Returns the names {@code Shortcodes.strip} removes; they need not be registered. Must not return null. */
    List<String> filter(List<String> tags, String content);
}
