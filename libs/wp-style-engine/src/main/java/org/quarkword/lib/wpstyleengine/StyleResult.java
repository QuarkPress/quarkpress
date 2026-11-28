package org.quarkword.lib.wpstyleengine;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * What a block style object gives. The map of declarations is unmodifiable, but a value that comes straight from the
 * input is the caller's own node, not a copy: a nested object or array of the input changed after the call shows in
 * the result too. Values are not copied so that a deeply nested input is never walked.
 *
 * @param css the sanitised CSS, or {@code ""} when there is none
 * @param declarations the raw, unsanitised declarations in order; values that never render (numbers, booleans,
 *     nested arrays, rejected text) are kept as they came. Empty when there are none.
 * @param classnames the class names joined by single spaces, each once, or {@code ""} when there are none
 */
public record StyleResult(String css, Map<String, JsonNode> declarations, String classnames) {

    /** No CSS, no declarations, no class names. */
    public static final StyleResult EMPTY = new StyleResult("", Map.of(), "");

    public StyleResult {
        Objects.requireNonNull(css, "css");
        Objects.requireNonNull(classnames, "classnames");
        declarations = Collections.unmodifiableMap(new LinkedHashMap<>(declarations));
    }

    /** True when there is no CSS, no declaration and no class name. */
    public boolean isEmpty() {
        return css.isEmpty() && declarations.isEmpty() && classnames.isEmpty();
    }
}
