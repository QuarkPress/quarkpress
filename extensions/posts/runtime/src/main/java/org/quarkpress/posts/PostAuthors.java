package org.quarkpress.posts;

import io.quarkus.security.identity.SecurityIdentity;
import java.util.Collection;
import org.quarkpress.core.UserId;

/** What callers may know about the posts of an author on the bound site. */
public interface PostAuthors {

    /**
     * Whether {@code author} has a post of one of {@code types} the current identity may count, as WordPress's
     * {@code count_user_posts} counts them for the REST user read rule: a published post, or a private one the caller
     * may read (its own, or of a type whose {@code read_private_posts} the caller holds). Types that are not registered
     * are left out.
     */
    boolean hasPostsVisibleToCaller(UserId author, Collection<String> types);

    boolean hasPostsVisibleToCaller(SecurityIdentity identity, UserId author, Collection<String> types);
}
