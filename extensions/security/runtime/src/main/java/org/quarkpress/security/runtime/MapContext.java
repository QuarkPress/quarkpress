package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/** What capability mapping may ask about the check it is mapping. */
public interface MapContext {

    /** Whether the installation is a network. */
    boolean multisite();

    /** The checking user; empty when logged out and for the system. */
    Optional<UserId> user();

    /** The site the check is for; empty for none. */
    Optional<SiteId> site();

    /**
     * Whether the checking user counts as a super admin for the mapping rules: in a network, a network administrator;
     * on a single site, a user who holds {@code delete_users}. The system counts as one.
     */
    boolean isSuperAdmin();

    /** Whether another user is a network administrator (in a network; false on a single site). */
    boolean isSuperAdmin(UserId user);

    /** A full check of {@code capability} for the checking user on the same site. */
    boolean userCan(String capability);

    Optional<JsonNode> siteOption(String name);

    Optional<JsonNode> networkOption(String name);

    /** Whether {@code unfiltered_html} is refused to everyone. */
    boolean disallowUnfilteredHtml();
}
