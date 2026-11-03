package org.quarkpress.restwp.deployment;

import org.quarkpress.posts.PostType;

/**
 * The custom post type {@code book} of the content route tests: public, in REST under {@code books}, supporting
 * title, editor, author, excerpt, thumbnail, revisions, custom fields and autosave, with no taxonomy; the same
 * registration as the parity comparison's {@code ItBookType}.
 */
@PostType(
        key = "book",
        name = "Books",
        singularName = "Book",
        publicly = true,
        restBase = "books",
        supports = {"title", "editor", "author", "excerpt", "thumbnail", "revisions", "custom-fields", "autosave"})
public final class BookType {

    private BookType() {}
}
