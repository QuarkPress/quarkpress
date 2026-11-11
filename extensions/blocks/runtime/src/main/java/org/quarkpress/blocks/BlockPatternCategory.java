package org.quarkpress.blocks;

import java.util.Objects;
import java.util.Optional;

/** A block pattern category: its name (slug), and its label and description when set. */
public record BlockPatternCategory(String name, Optional<String> label, Optional<String> description) {

    public BlockPatternCategory {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(description, "description");
    }
}
