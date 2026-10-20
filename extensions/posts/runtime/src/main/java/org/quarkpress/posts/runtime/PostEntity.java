package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
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
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.runtime.QuarkPressJsonJdbcType;
import org.quarkpress.persistence.runtime.SiteScopedEntity;
import org.quarkpress.posts.PostSnapshot;

/**
 * A post of any type. {@code publishedAt} is the GMT date (null while a draft has no date); {@code meta} is a JSON
 * object of key to array of values. The parent is kept by id; the database refuses a parent of another site.
 */
@Entity
@Table(name = "qp_post")
@Filter(name = SiteScopedEntity.FILTER)
public class PostEntity extends SiteScopedEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    public Long id;

    @Column(name = "type", nullable = false, length = 20)
    public String type;

    @Column(name = "status", nullable = false, length = 20)
    public String status;

    @Column(name = "slug", nullable = false, length = 200)
    public String slug;

    @Column(name = "title", nullable = false)
    public String title;

    @Column(name = "content", nullable = false)
    public String content;

    @Column(name = "excerpt", nullable = false)
    public String excerpt;

    @Column(name = "author_id")
    public Long authorId;

    @Column(name = "parent_id")
    public Long parentId;

    @Column(name = "menu_order", nullable = false)
    public int menuOrder;

    @Column(name = "published_at")
    public Instant publishedAt;

    @Column(name = "created_at", nullable = false)
    public Instant createdAt;

    @Column(name = "modified_at", nullable = false)
    public Instant modifiedAt;

    @Column(name = "comment_status", nullable = false, length = 20)
    public String commentStatus;

    @Column(name = "ping_status", nullable = false, length = 20)
    public String pingStatus;

    @Column(name = "password", nullable = false, length = 255)
    public String password;

    @Column(name = "sticky", nullable = false)
    public boolean sticky;

    @Column(name = "guid", nullable = false, length = 255)
    public String guid;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "meta", nullable = false)
    public JsonNode meta;

    @Version
    @Column(name = "version", nullable = false)
    public int version;

    /** A new row of {@code site} with empty meta; the caller fills the other columns. */
    public static PostEntity create(SiteId site) {
        PostEntity post = new PostEntity();
        post.assignSite(site);
        post.meta = JsonNodeFactory.instance.objectNode();
        post.password = "";
        post.slug = "";
        post.guid = "";
        return post;
    }

    /** The row as an immutable record. */
    public PostSnapshot toSnapshot() {
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
        return new PostSnapshot(
                PostId.of(id),
                site(),
                type,
                status,
                slug,
                title,
                content,
                excerpt,
                Optional.ofNullable(authorId).map(UserId::of),
                Optional.ofNullable(parentId).map(PostId::of),
                menuOrder,
                Optional.ofNullable(publishedAt),
                createdAt,
                modifiedAt,
                commentStatus,
                pingStatus,
                password,
                sticky,
                guid,
                values,
                version);
    }
}
