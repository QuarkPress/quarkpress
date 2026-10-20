package org.quarkpress.posts;

import java.util.List;
import java.util.Optional;
import org.quarkpress.core.PostTypeDefinition;
import org.quarkpress.security.PostTypeCapabilities;

/**
 * The registered post types: WordPress's built-in {@code post}, {@code page}, {@code attachment} and
 * {@code wp_block}, then the types extensions register, in that order. Fixed when the application is built.
 */
public interface PostTypes {

    List<PostTypeDefinition> all();

    Optional<PostTypeDefinition> find(String key);

    boolean registered(String key);

    /** Whether the type is registered and hierarchical. */
    boolean hierarchical(String key);

    /** Whether the type is registered and supports {@code feature}. */
    boolean supports(String key, String feature);

    /** The types a query for "any" type covers: those not excluded from search, in registration order. */
    List<String> searchable();

    /** The type's capability names; empty for a type that is not registered. */
    Optional<PostTypeCapabilities> capabilities(String key);

    /** The type's capability names, or the {@code post} type's for a type that is not registered. */
    PostTypeCapabilities capabilitiesOrPost(String key);
}
