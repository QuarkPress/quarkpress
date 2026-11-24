package org.quarkword.front.deployment;

import org.quarkword.posts.PostType;

/** A public type whose rewrite is not {@code with_front}: its links ignore the structure's front. */
@PostType(
        key = "novel",
        publicly = true,
        hasArchive = true,
        rewriteSlug = "novels",
        withFront = false,
        supports = {"title", "editor"})
final class NovelType {}
