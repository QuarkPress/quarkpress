package org.quarkword.posts.deployment;

import org.quarkword.posts.PostType;

/** A public custom type with the default query variable ({@code book}), for links of custom types. */
@PostType(
        key = "book",
        publicly = true,
        supports = {"title", "editor"})
final class BookType {}
