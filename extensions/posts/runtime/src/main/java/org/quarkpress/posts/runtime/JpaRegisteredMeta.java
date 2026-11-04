package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.runtime.Startup;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.PostConstruct;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.JsonValues;
import org.quarkpress.posts.MetaContext;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaKeyProvider;
import org.quarkpress.posts.MetaWrite;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostPermissions;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.RegisteredMeta;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.QuarkPressPrincipal;

/**
 * The meta registry, built once at startup from the built-in keys and every {@link MetaKeyProvider}, and the writes
 * through it. A write sanitizes with the caller's identity, checks the caller on the post it finds, then takes the
 * site's post lock and the row lock and checks again on the locked row before changing it; a key the caller may
 * write is written whether or not it is protected. A refusal answers {@link MetaWrite#DENIED} before any lock, so it
 * never marks the caller's transaction for rollback and never makes others wait.
 */
@Startup
@ApplicationScoped
public class JpaRegisteredMeta implements RegisteredMeta {

    @Inject
    PostTypes types;

    @Inject
    Capabilities capabilities;

    @Inject
    PostPermissions permissions;

    @Inject
    SiteContext site;

    @Inject
    PostRepository posts;

    @Inject
    PostLocks locks;

    @Inject
    Acting acting;

    @Inject
    @All
    List<InstanceHandle<MetaKeyProvider>> providers;

    private List<MetaKeyDefinition> everyType = List.of();
    private Map<String, List<MetaKeyDefinition>> byType = Map.of();

    @PostConstruct
    void register() {
        List<MetaKeyDefinition> all = new ArrayList<>(BuiltInMetaKeys.all(
                types, context -> capabilities.can(context.identity(), "unfiltered_html", Optional.empty())));
        List<InstanceHandle<MetaKeyProvider>> ordered = new ArrayList<>(providers);
        ordered.sort(
                Comparator.comparing(handle -> handle.getBean().getBeanClass().getName()));
        for (InstanceHandle<MetaKeyProvider> provider : ordered) {
            all.addAll(provider.get().metaKeys(types));
        }
        List<MetaKeyDefinition> shared = new ArrayList<>();
        Map<String, List<MetaKeyDefinition>> perType = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (MetaKeyDefinition key : all) {
            check(key);
            if (!seen.add(key.subtype() + "\u0000" + key.key())) {
                throw new IllegalStateException("QuarkPress: the meta key " + key.key()
                        + " is registered more than once for "
                        + (key.subtype().isEmpty() ? "every type" : key.subtype()));
            }
            if (key.subtype().isEmpty()) {
                shared.add(key);
            } else {
                perType.computeIfAbsent(key.subtype(), type -> new ArrayList<>())
                        .add(key);
            }
        }
        for (MetaKeyDefinition key : shared) {
            for (Map.Entry<String, List<MetaKeyDefinition>> entry : perType.entrySet()) {
                if (entry.getValue().stream().anyMatch(other -> other.key().equals(key.key()))) {
                    throw new IllegalStateException("QuarkPress: the meta key " + key.key()
                            + " is registered for every type and for " + entry.getKey());
                }
            }
        }
        everyType = List.copyOf(shared);
        perType.replaceAll((type, keys) -> List.copyOf(keys));
        byType = Map.copyOf(perType);
    }

    private void check(MetaKeyDefinition key) {
        if (key.showInRest() && key.type().equals("array") && !key.schema().has("items")) {
            throw new IllegalStateException(
                    "QuarkPress: the meta key " + key.key() + " is shown in REST as an array without schema items");
        }
        if (key.revisionsEnabled() && !key.subtype().isEmpty() && !types.supports(key.subtype(), "revisions")) {
            throw new IllegalStateException("QuarkPress: the meta key " + key.key() + " keeps revisions for "
                    + key.subtype() + ", which does not support revisions");
        }
    }

    @Override
    public List<MetaKeyDefinition> keys(String type) {
        Objects.requireNonNull(type, "type");
        List<MetaKeyDefinition> keys = new ArrayList<>(everyType);
        keys.addAll(byType.getOrDefault(type, List.of()));
        return Collections.unmodifiableList(keys);
    }

    @Override
    public List<MetaKeyDefinition> restKeys(String type) {
        return keys(type).stream().filter(MetaKeyDefinition::showInRest).toList();
    }

    @Override
    public Set<String> revisionedKeys(String type) {
        Set<String> keys = new LinkedHashSet<>();
        boolean revisions = types.supports(type, "revisions");
        for (MetaKeyDefinition key : keys(type)) {
            // a key for every type keeps revisions only where the type keeps them
            if (key.revisionsEnabled() && (!key.subtype().isEmpty() || revisions)) {
                keys.add(key.key());
            }
        }
        return Collections.unmodifiableSet(keys);
    }

    @Override
    public boolean mayEdit(SecurityIdentity identity, PostSnapshot post, MetaKeyDefinition key, String capability) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(capability, "capability");
        boolean allowed = !key.isProtected();
        if (key.auth().isPresent()) {
            allowed = key.auth().get().allowed(allowed, key.key(), post.id(), user(identity), capability);
        }
        return allowed && permissions.can(identity, "edit_post", post);
    }

    @Override
    public JsonNode sanitize(PostSnapshot post, MetaKeyDefinition key, JsonNode value) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (value.isNull()) {
            throw new IllegalArgumentException("a registered meta value cannot be JSON null");
        }
        return sanitize(key, post, value, acting.identity());
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public List<JsonNode> stored(PostId post, String key) {
        Objects.requireNonNull(key, "key");
        return MetaRows.values(find(site.siteId(), post).meta, key);
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public MetaWrite updateSingle(PostId post, MetaKeyDefinition key, JsonNode value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        if (value.isNull()) {
            throw new IllegalArgumentException("a registered meta value cannot be JSON null; delete the key instead");
        }
        SiteId siteId = site.siteId();
        PostEntity unlocked = find(siteId, post);
        SecurityIdentity identity = acting.identity();
        JsonNode sanitized = sanitize(key, unlocked.toSnapshot(), value, identity);
        List<JsonNode> stored = MetaRows.values(unlocked.meta, key.key());
        if (stored.size() == 1 && sameStored(stored.get(0), sanitized)) {
            return MetaWrite.UNCHANGED;
        }
        if (!mayEdit(identity, unlocked.toSnapshot(), key, "edit_post_meta")) {
            return MetaWrite.DENIED;
        }
        PostEntity row = lock(siteId, post);
        if (!mayEdit(identity, row.toSnapshot(), key, "edit_post_meta")) {
            return MetaWrite.DENIED;
        }
        ObjectNode meta = MetaRows.copy(row.meta);
        if (!MetaRows.update(meta, key.key(), sanitized)) {
            return MetaWrite.UNCHANGED;
        }
        row.meta = meta;
        return MetaWrite.WRITTEN;
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public MetaWrite delete(PostId post, MetaKeyDefinition key) {
        Objects.requireNonNull(key, "key");
        SiteId siteId = site.siteId();
        PostEntity unlocked = find(siteId, post);
        SecurityIdentity identity = acting.identity();
        if (!mayEdit(identity, unlocked.toSnapshot(), key, "delete_post_meta")) {
            return MetaWrite.DENIED;
        }
        if (MetaRows.values(unlocked.meta, key.key()).isEmpty()) {
            return MetaWrite.UNCHANGED;
        }
        PostEntity row = lock(siteId, post);
        if (!mayEdit(identity, row.toSnapshot(), key, "delete_post_meta")) {
            return MetaWrite.DENIED;
        }
        ObjectNode meta = MetaRows.copy(row.meta);
        if (!MetaRows.delete(meta, key.key())) {
            return MetaWrite.UNCHANGED;
        }
        row.meta = meta;
        return MetaWrite.WRITTEN;
    }

    @Override
    @Transactional(
            dontRollbackOn = {IllegalArgumentException.class, ForbiddenException.class, UnauthorizedException.class})
    public MetaWrite replace(PostId post, MetaKeyDefinition key, List<JsonNode> values) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(values, "values");
        for (JsonNode value : values) {
            if (value == null || value.isNull()) {
                throw new IllegalArgumentException("a registered meta value cannot be JSON null");
            }
        }
        SiteId siteId = site.siteId();
        PostEntity unlocked = find(siteId, post);
        SecurityIdentity identity = acting.identity();
        if (!mayEdit(identity, unlocked.toSnapshot(), key, "edit_post_meta")) {
            return MetaWrite.DENIED;
        }
        List<JsonNode> requested = new ArrayList<>();
        for (JsonNode value : values) {
            requested.add(sanitize(key, unlocked.toSnapshot(), value, identity));
        }
        PostEntity row = lock(siteId, post);
        if (!mayEdit(identity, row.toSnapshot(), key, "edit_post_meta")) {
            return MetaWrite.DENIED;
        }
        ObjectNode meta = MetaRows.copy(row.meta);
        List<JsonNode> stored = MetaRows.values(meta, key.key());
        boolean[] matched = new boolean[stored.size()];
        List<JsonNode> added = new ArrayList<>();
        for (JsonNode value : requested) {
            int found = -1;
            for (int i = 0; i < stored.size() && found < 0; i++) {
                if (!matched[i] && sameStored(stored.get(i), value)) {
                    found = i;
                }
            }
            if (found >= 0) {
                matched[found] = true;
            } else {
                added.add(value);
            }
        }
        ArrayNode kept = JsonNodeFactory.instance.arrayNode();
        for (int i = 0; i < stored.size(); i++) {
            if (matched[i]) {
                kept.add(stored.get(i));
            }
        }
        if (added.isEmpty() && kept.size() == stored.size()) {
            return MetaWrite.UNCHANGED;
        }
        added.forEach(kept::add);
        if (kept.isEmpty()) {
            meta.remove(key.key());
        } else {
            meta.set(key.key(), kept);
        }
        row.meta = meta;
        return MetaWrite.WRITTEN;
    }

    private PostEntity find(SiteId siteId, PostId post) {
        Objects.requireNonNull(post, "post");
        return posts.findPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
    }

    private PostEntity lock(SiteId siteId, PostId post) {
        locks.lock(siteId);
        return posts.lockPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
    }

    private static JsonNode sanitize(
            MetaKeyDefinition key, PostSnapshot post, JsonNode value, SecurityIdentity identity) {
        JsonNode sanitized = value;
        if (key.sanitizer().isPresent()) {
            MetaContext context = new MetaContext(post.id(), post.type(), key.key(), identity);
            sanitized = Objects.requireNonNull(key.sanitizer().get().sanitize(value, context), "sanitized value");
        }
        if (sanitized.isNull()) {
            throw new IllegalArgumentException("the sanitizer of the meta key " + key.key() + " returned JSON null");
        }
        return JsonValues.requireStorable(sanitized);
    }

    private static Optional<UserId> user(SecurityIdentity identity) {
        return QuarkPressPrincipal.of(identity).map(QuarkPressPrincipal::user);
    }

    /** WordPress's comparison of a stored value with a new one: scalars as PHP strings, containers by value. */
    static boolean sameStored(JsonNode stored, JsonNode value) {
        if (stored.isValueNode() && value.isValueNode()) {
            return phpText(stored).equals(phpText(value));
        }
        return JsonValues.sameValue(stored, value);
    }

    private static String phpText(JsonNode value) {
        if (value.isNull()) {
            return "";
        }
        if (value.isBoolean()) {
            return value.booleanValue() ? "1" : "";
        }
        if (value.isNumber() && !value.isIntegralNumber()) {
            return PhpJsonText.encode(value);
        }
        return value.asText();
    }
}
