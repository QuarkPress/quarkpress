package org.quarkpress.restwp.deployment;

import org.quarkpress.posts.PostType;

/**
 * A custom post type that is not public, so not publicly queryable either: internal records an application keeps as
 * posts, which the front-end page never shows.
 */
@PostType(
        key = "ledger",
        name = "Ledgers",
        singularName = "Ledger",
        supports = {"title", "editor"})
public final class LedgerType {

    private LedgerType() {}
}
