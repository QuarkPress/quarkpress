package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.JsonValues;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostMeta;

/**
 * Post meta on the post row's meta object, written under the site's post lock and the row lock. A write checks the
 * caller first, then takes the locks and checks again on the locked row.
 */
@ApplicationScoped
public class JpaPostMeta implements PostMeta {

    @Inject
    SiteContext site;

    @Inject
    PostRepository posts;

    @Inject
    PostLocks locks;

    @Inject
    Acting acting;

    @Inject
    PostCapabilityFacts facts;

    @Override
    @Transactional
    public List<JsonNode> get(PostId post, String key) {
        Objects.requireNonNull(key, "key");
        return MetaRows.values(readable(post).meta, key);
    }

    @Override
    @Transactional
    public Map<String, List<JsonNode>> all(PostId post) {
        return readable(post).toSnapshot().meta();
    }

    @Override
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean add(PostId post, String key, JsonNode value, boolean unique) {
        JsonNode stored = JsonValues.requireStorable(Objects.requireNonNull(value, "value"));
        return write(post, key, meta -> MetaRows.add(meta, key, stored, unique));
    }

    @Override
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean update(PostId post, String key, JsonNode value) {
        JsonNode stored = JsonValues.requireStorable(Objects.requireNonNull(value, "value"));
        return write(post, key, meta -> MetaRows.update(meta, key, stored));
    }

    @Override
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean delete(PostId post, String key) {
        return write(post, key, meta -> MetaRows.delete(meta, key));
    }

    @Override
    @Transactional(dontRollbackOn = IllegalArgumentException.class)
    public boolean delete(PostId post, String key, JsonNode value) {
        return write(post, key, meta -> MetaRows.delete(meta, key, value));
    }

    private PostEntity readable(PostId post) {
        SiteId siteId = site.siteId();
        PostEntity row = posts.findPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.requireReadable(siteId, facts.of(row.toSnapshot()));
        return row;
    }

    private boolean write(PostId post, String key, Predicate<ObjectNode> change) {
        Objects.requireNonNull(post, "post");
        Objects.requireNonNull(key, "key");
        SiteId siteId = site.siteId();
        PostEntity unlocked = posts.findPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(unlocked.toSnapshot())));
        acting.requireUnprotectedKey(key);
        if (!MetaRows.validKey(key)) {
            return false;
        }
        locks.lock(siteId);
        PostEntity row = posts.lockPost(siteId, post).orElseThrow(() -> PostException.invalidPost(post));
        acting.require(siteId, "edit_post", Optional.of(facts.of(row.toSnapshot())));
        ObjectNode meta = MetaRows.copy(row.meta);
        if (!change.test(meta)) {
            return false;
        }
        row.meta = meta;
        return true;
    }
}
