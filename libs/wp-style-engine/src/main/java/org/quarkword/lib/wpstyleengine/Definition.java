package org.quarkword.lib.wpstyleengine;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One row of WordPress's block style definitions table.
 *
 * @param group the group key ({@code color}, {@code border}, ...)
 * @param key the definition's key in its group
 * @param path where the value sits in a block style object
 * @param defaultProperty the CSS property for a scalar value, or {@code null}
 * @param individualPattern the property for one side or corner, with one {@code %s}, or {@code null}
 * @param cssVars preset type to custom-property pattern, in order; {@code $slug} stands for the slug
 * @param classnames the class names, in order
 * @param valueFunc a custom handler, or {@code null}
 */
record Definition(
        String group,
        String key,
        List<String> path,
        String defaultProperty,
        String individualPattern,
        Map<String, String> cssVars,
        List<ClassnameRule> classnames,
        ValueFunc valueFunc) {

    Definition {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(key, "key");
        path = List.copyOf(path);
        cssVars = Collections.unmodifiableMap(new LinkedHashMap<>(cssVars));
        classnames = List.copyOf(classnames);
    }
}
