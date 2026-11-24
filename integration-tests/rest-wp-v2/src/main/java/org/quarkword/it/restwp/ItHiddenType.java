package org.quarkword.it.restwp;

import org.quarkword.posts.PostType;

/**
 * The custom post type {@code qw_hidden} of the REST parity comparison: public, but not shown in the REST API (no REST
 * base), so an attachment whose parent is of this type answers as WordPress's does for a parent type REST does not
 * show. The WordPress side of the comparison registers the same type when a scenario turns on its must-use plugin's
 * {@code qw_parity_hidden_type} switch through an option setup step.
 */
@PostType(key = "qw_hidden", publicly = true)
public final class ItHiddenType {

    private ItHiddenType() {}
}
