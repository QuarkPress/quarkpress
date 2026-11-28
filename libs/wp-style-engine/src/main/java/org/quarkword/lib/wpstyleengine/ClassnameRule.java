package org.quarkword.lib.wpstyleengine;

import java.util.Objects;

/**
 * One class name of a block style definition: the {@code pattern} is emitted as it is when {@code presetType} is
 * {@code null}, otherwise only for a preset of that type, with {@code $slug} replaced by the preset's slug.
 */
record ClassnameRule(String pattern, String presetType) {

    ClassnameRule {
        Objects.requireNonNull(pattern, "pattern");
    }
}
