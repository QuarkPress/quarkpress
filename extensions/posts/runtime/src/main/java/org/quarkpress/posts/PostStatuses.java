package org.quarkpress.posts;

import java.util.List;
import java.util.Optional;
import org.quarkpress.core.PostStatusDefinition;
import org.quarkpress.security.PostVisibility;

/** The registered post statuses: WordPress's twelve built-in ones, then those extensions register. */
public interface PostStatuses {

    List<PostStatusDefinition> all();

    Optional<PostStatusDefinition> find(String name);

    boolean registered(String name);

    /** Whether the status is registered with a floating date (draft, pending, auto-draft by default). */
    boolean floating(String name);

    /** The registered public statuses ({@code publish} by default). */
    List<String> publicStatuses();

    /** The registered private statuses ({@code private} by default). */
    List<String> privateStatuses();

    /** The registered statuses a query for "any" status leaves out (trash and auto-draft by default). */
    List<String> excludedFromSearch();

    /** How the status counts when reading is checked: public, private, another registered status, or unregistered. */
    PostVisibility visibility(String name);
}
