package org.quarkpress.security.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.ActivateRequestContext;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.UserMeta;
import org.quarkpress.security.UserTarget;

/**
 * User meta in the profile's {@code meta} object, except the keys QuarkPress keeps there for itself. A change checks the capability first, then locks the profile row
 * (read again under the lock) and rewrites the object, keeping the order of its keys; so concurrent changes of one
 * user's meta, and a login that refreshes the profile, apply one after the other.
 */
@ApplicationScoped
public class JpaUserMeta implements UserMeta {

    /** The longest key. */
    static final int MAX_KEY = 255;

    /**
     * The prefix of the keys QuarkPress keeps for itself in the meta object (such as the bootstrap mark that
     * {@link ProfileProvisioning} sets and clears), in any ASCII case: never read or written through this API.
     */
    static final String RESERVED_PREFIX = "quarkpress_";

    @Inject
    UserProfileRepository profiles;

    @Inject
    CapabilityGuard guard;

    @Override
    @ActivateRequestContext
    @Transactional
    public Optional<JsonNode> get(UserId user, String key) {
        Objects.requireNonNull(user, "user");
        requireKey(key);
        UserProfileEntity profile = profiles.findById(user.value());
        if (profile == null || !(profile.meta instanceof ObjectNode meta)) {
            return Optional.empty();
        }
        return Optional.ofNullable(meta.get(key)).map(JsonNode::deepCopy);
    }

    @Override
    @Transactional
    public void set(UserId user, String key, JsonNode value) {
        Objects.requireNonNull(user, "user");
        requireKey(key);
        Objects.requireNonNull(value, "value");
        guard.requireHere("edit_user", Optional.of(new UserTarget(user)));
        UserProfileEntity profile = locked(user)
                .orElseThrow(() -> new IllegalArgumentException("user " + user.value() + " does not exist"));
        ObjectNode meta = objectOf(profile);
        meta.set(key, value.deepCopy());
        profile.meta = meta;
        profiles.flush();
    }

    @Override
    @Transactional
    public boolean delete(UserId user, String key) {
        Objects.requireNonNull(user, "user");
        requireKey(key);
        guard.requireHere("edit_user", Optional.of(new UserTarget(user)));
        Optional<UserProfileEntity> profile = locked(user);
        if (profile.isEmpty()) {
            return false;
        }
        ObjectNode meta = objectOf(profile.get());
        if (!meta.has(key)) {
            return false;
        }
        meta.remove(key);
        profile.get().meta = meta;
        profiles.flush();
        return true;
    }

    @Override
    public String siteKey(SiteId site, String name) {
        return siteKeyOf(site, name);
    }

    /**
     * WordPress's table prefix for a site id: the bare prefix for site 1, the prefix and the id for any other site,
     * whichever site is the main one.
     */
    static String siteKeyOf(SiteId site, String name) {
        Objects.requireNonNull(site, "site");
        Objects.requireNonNull(name, "name");
        return site.value() == 1 ? "wp_" + name : "wp_" + site.value() + "_" + name;
    }

    /** A copy of the profile's meta object (an empty one if the stored value is no object). */
    private static ObjectNode objectOf(UserProfileEntity profile) {
        return profile.meta instanceof ObjectNode meta ? meta.deepCopy() : JsonNodeFactory.instance.objectNode();
    }

    /** The profile row, locked until the transaction ends (see {@link ProfileRowLock}). */
    private Optional<UserProfileEntity> locked(UserId user) {
        return ProfileRowLock.lock(profiles, user, "change of user meta: change it in a separate transaction");
    }

    private static void requireKey(String key) {
        Objects.requireNonNull(key, "key");
        if (key.isBlank()) {
            throw new IllegalArgumentException("a user meta key must not be empty or blank");
        }
        if (key.length() > MAX_KEY) {
            throw new IllegalArgumentException(
                    "a user meta key has at most " + MAX_KEY + " characters: " + key.length());
        }
        if (key.regionMatches(true, 0, RESERVED_PREFIX, 0, RESERVED_PREFIX.length())) {
            throw new IllegalArgumentException("user meta keys starting with " + RESERVED_PREFIX
                    + " are reserved for QuarkPress's own marks: " + key);
        }
    }
}
