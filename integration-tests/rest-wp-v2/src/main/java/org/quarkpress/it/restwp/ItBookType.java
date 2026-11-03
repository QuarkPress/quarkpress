package org.quarkpress.it.restwp;

import org.quarkpress.posts.PostType;

/**
 * The custom post type {@code book} of the REST parity comparison: public, in REST under {@code books}, labelled
 * Books/Book, supporting title, editor, author, excerpt, thumbnail, revisions, custom fields and autosave, with no
 * taxonomy. The WordPress side of the comparison registers the same type through its must-use plugin.
 */
@PostType(
        key = "book",
        name = "Books",
        singularName = "Book",
        publicly = true,
        restBase = "books",
        supports = {"title", "editor", "author", "excerpt", "thumbnail", "revisions", "custom-fields", "autosave"})
public final class ItBookType {

    private ItBookType() {}
}
