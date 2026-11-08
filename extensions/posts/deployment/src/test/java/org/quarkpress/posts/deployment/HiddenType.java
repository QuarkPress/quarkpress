package org.quarkpress.posts.deployment;

import org.quarkpress.posts.PostType;

/** A public type not shown in REST (no REST base), as a parent of attachments the REST read decision refuses. */
@PostType(
        key = "hidden",
        publicly = true,
        supports = {"title", "editor"})
final class HiddenType {}
