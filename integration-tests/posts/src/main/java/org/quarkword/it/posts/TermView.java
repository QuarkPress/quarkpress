package org.quarkword.it.posts;

import org.quarkword.taxonomy.TermSnapshot;

public record TermView(long id, String taxonomy, String name, String slug, String description, long parent, int count) {

    static TermView of(TermSnapshot term) {
        return new TermView(
                term.id().value(),
                term.taxonomy(),
                term.name(),
                term.slug(),
                term.description(),
                term.parent().map(parent -> parent.value()).orElse(0L),
                term.count());
    }
}
