package org.quarkpress.it.restwp;

import org.quarkpress.posts.PostType;

/**
 * The custom post type {@code qp_hidden} of the REST parity comparison: public, but not shown in the REST API (no REST
 * base), so an attachment whose parent is of this type answers as WordPress's does for a parent type REST does not
 * show. The WordPress side of the comparison registers the same type when a scenario turns on its must-use plugin's
 * {@code qp_parity_hidden_type} switch through an option setup step.
 */
@PostType(key = "qp_hidden", publicly = true)
public final class ItHiddenType {

    private ItHiddenType() {}
}
