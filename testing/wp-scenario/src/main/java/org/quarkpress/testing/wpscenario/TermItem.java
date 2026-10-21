package org.quarkpress.testing.wpscenario;

import java.util.Objects;
import java.util.Optional;

/** One element of an assignment: a term reference (an id on WordPress's side) or a name. */
public record TermItem(Optional<String> term, Optional<String> name) {

    public TermItem {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(name, "name");
        if (term.isPresent() == name.isPresent()) {
            throw new IllegalArgumentException("a scenario term item gives either term or name");
        }
        term.ifPresent(ref -> ScenarioRules.requireTermRef(ref, false));
        name.ifPresent(ScenarioRules::requireTermName);
    }

    public static TermItem term(String ref) {
        return new TermItem(Optional.of(ref), Optional.empty());
    }

    public static TermItem name(String name) {
        return new TermItem(Optional.empty(), Optional.of(name));
    }
}
