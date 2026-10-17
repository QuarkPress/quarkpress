package org.quarkpress.lib.wpshortcode;

import java.util.Map;

/** WordPress's shortcode_atts_{tag} filter, registered for one tag. */
@FunctionalInterface
public interface AttributeDefaultsHook {

    /**
     * Returns the merged attributes; may add or change keys. The input maps are unmodifiable: return a new map.
     * Must not return null.
     */
    Map<String, String> filter(
            Map<String, String> out, Map<String, String> defaults, ShortcodeAttributes attributes, String tag);
}
