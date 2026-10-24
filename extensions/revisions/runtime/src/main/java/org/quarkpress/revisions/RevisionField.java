package org.quarkpress.revisions;

import java.util.EnumSet;
import java.util.Set;

/** A field a revision copies and a restore puts back: the post's title, content or excerpt. */
public enum RevisionField {
    TITLE,
    CONTENT,
    EXCERPT;

    /** The three fields, in this order. */
    public static Set<RevisionField> all() {
        return EnumSet.allOf(RevisionField.class);
    }
}
