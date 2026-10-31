package org.quarkpress.restwp.runtime;

import java.util.List;

/** What {@code _embed} asks for: nothing, every relation, or the listed relations. */
public record EmbedChoice(Kind kind, List<String> relations) {

    public enum Kind {
        NONE,
        ALL,
        THESE
    }

    public static final EmbedChoice NONE = new EmbedChoice(Kind.NONE, List.of());
    public static final EmbedChoice ALL = new EmbedChoice(Kind.ALL, List.of());

    public EmbedChoice {
        relations = List.copyOf(relations);
    }
}
