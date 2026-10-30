package org.quarkpress.security;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * A user's meta: JSON values under keys of 1 to 255 characters that are not blank, stored with the profile in the order
 * they were first written. Keys are refused with {@link IllegalArgumentException} otherwise.
 */
public interface UserMeta {

    /** The value under {@code key}; empty when the user has none or does not exist. Needs no capability. */
    Optional<JsonNode> get(UserId user, String key);

    /**
     * Writes the value under {@code key}, in place when the key exists. Requires {@code edit_user} on {@code user} for
     * the current identity (oneself always); takes the profile row lock.
     *
     * @throws IllegalArgumentException when the user does not exist
     */
    void set(UserId user, String key, JsonNode value);

    /**
     * Removes the value under {@code key}; whether there was one. Requires {@code edit_user} on {@code user} for the
     * current identity (oneself always); takes the profile row lock.
     */
    boolean delete(UserId user, String key);

    /**
     * WordPress's per-site key: "wp_" + name for site 1, "wp_" + siteId + "_" + name for any other site, as WordPress
     * prefixes by site id whichever site is the network's main one.
     */
    String siteKey(SiteId site, String name);
}
