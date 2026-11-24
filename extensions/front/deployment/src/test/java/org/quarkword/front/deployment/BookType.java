package org.quarkword.front.deployment;

import org.quarkword.posts.PostType;

/** The behaviour reference's {@code book}: public, with an archive, rewrite slug {@code books} under the front. */
@PostType(
        key = "book",
        publicly = true,
        hasArchive = true,
        rewriteSlug = "books",
        supports = {"title", "editor"})
final class BookType {}
