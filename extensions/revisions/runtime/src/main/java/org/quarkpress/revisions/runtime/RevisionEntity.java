package org.quarkpress.revisions.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.QuarkPressJsonJdbcType;
import org.quarkpress.persistence.runtime.SiteScopedEntity;
import org.quarkpress.revisions.RevisionSnapshot;

/**
 * A revision or autosave of a post. The row has no version: it is only written under the site's post lock.
 *
 * <p>{@code autosaveOwner} is derived: the entity sets it to {@code autosave ? authorId : null} before every insert
 * and update, because the unique key on the post and the owner is what allows one autosave per user and post. It is a
 * plain column, not a generated one: the database also sets it to null when the profile is deleted.
 */
@Entity
@Table(name = "qp_revision")
@Filter(name = SiteScopedEntity.FILTER)
public class RevisionEntity extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "post_id", nullable = false)
    public long postId;

    @Column(name = "author_id")
    public Long authorId;

    @Column(name = "title", nullable = false)
    public String title;

    @Column(name = "content", nullable = false)
    public String content;

    @Column(name = "excerpt", nullable = false)
    public String excerpt;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "meta", nullable = false)
    public JsonNode meta;

    @Column(name = "autosave", nullable = false)
    public boolean autosave;

    @Column(name = "autosave_owner")
    private Long autosaveOwner;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @PrePersist
    @PreUpdate
    void syncAutosaveOwner() {
        autosaveOwner = autosave ? authorId : null;
    }

    /** A new row of {@code site} for {@code post} with empty texts and meta, and no author. */
    public static RevisionEntity create(SiteId site, PostId post) {
        RevisionEntity revision = new RevisionEntity();
        revision.assignSite(site);
        revision.postId = post.value();
        revision.title = "";
        revision.content = "";
        revision.excerpt = "";
        revision.meta = JsonNodeFactory.instance.objectNode();
        return revision;
    }

    /** The row as an immutable record. */
    public RevisionSnapshot toSnapshot() {
        Map<String, List<JsonNode>> values = new LinkedHashMap<>();
        if (meta != null && meta.isObject()) {
            meta.properties().forEach(entry -> {
                List<JsonNode> list = new ArrayList<>();
                if (entry.getValue().isArray()) {
                    entry.getValue().forEach(list::add);
                }
                values.put(entry.getKey(), list);
            });
        }
        return new RevisionSnapshot(
                RevisionId.of(id),
                site(),
                PostId.of(postId),
                Optional.ofNullable(authorId).map(UserId::of),
                title,
                content,
                excerpt,
                values,
                autosave,
                createdAt);
    }
}
