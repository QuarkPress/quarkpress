package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.Filter;
import org.hibernate.annotations.JdbcType;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.QuarkPressJsonJdbcType;
import org.quarkpress.persistence.runtime.SiteScopedEntity;

/**
 * The file side of an attachment post: one row per attachment, keyed by the post's id.
 *
 * <p>{@code storageKey} is the main file's full storage key, the {@code sites/<id>/} folder of a site other than the
 * main one included; the path relative to the site's uploads folder is derived from it, never stored. The JSON
 * columns keep their key order: {@code sizes} holds the generated sizes in generation order, each in WordPress's entry
 * shape ({@code file}, {@code width}, {@code height}, {@code mime-type}, {@code filesize}); {@code extra} holds every
 * other attachment metadata key (audio and video keys, the source image, imported unknown keys) in order;
 * {@code imageMeta} is null until the file's embedded data is read. {@code width} and {@code height} are null when the
 * main file's dimensions are unknown, whatever its type. {@code storedBytes} counts every file of the attachment: the
 * main file, the untouched original and the sizes.
 */
@Entity
@Table(name = "qp_media")
@Filter(name = SiteScopedEntity.FILTER)
public class MediaEntity extends SiteScopedEntity {

    // the values of processing
    public static final String NONE = "none";
    public static final String PENDING = "pending";
    public static final String PARTIAL = "partial";
    public static final String DONE = "done";
    public static final String FAILED = "failed";

    @Id
    @Column(name = "post_id")
    public Long postId;

    @Column(name = "storage_key", nullable = false, length = 1024)
    public String storageKey;

    /** The untouched upload when the main file is a scaled, rotated or converted copy of it; null otherwise. */
    @Column(name = "original_key", length = 1024)
    public String originalKey;

    @Column(name = "mime_type", nullable = false, length = 100)
    public String mimeType;

    /** Bytes of the main file. */
    @Column(name = "file_size", nullable = false)
    public long fileSize;

    @Column(name = "width")
    public Integer width;

    @Column(name = "height")
    public Integer height;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "sizes", nullable = false)
    public JsonNode sizes;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "image_meta")
    public JsonNode imageMeta;

    @JdbcTypeCode(SqlTypes.JSON)
    @JdbcType(QuarkPressJsonJdbcType.class)
    @Column(name = "extra", nullable = false)
    public JsonNode extra;

    @Column(name = "alt_text", nullable = false)
    public String altText;

    /** One of {@link #NONE}, {@link #PENDING}, {@link #PARTIAL}, {@link #DONE}, {@link #FAILED}. */
    @Column(name = "processing", nullable = false, length = 10)
    public String processing;

    @Column(name = "stored_bytes", nullable = false)
    public long storedBytes;

    @Version
    @Column(name = "version", nullable = false)
    public int version;

    /**
     * A new row of {@code site} for the attachment {@code post}: empty alt text, no sizes, no further metadata, no
     * processing; the caller sets the key, the type and the sizes of the files.
     */
    public static MediaEntity create(SiteId site, PostId post) {
        MediaEntity media = new MediaEntity();
        media.assignSite(site);
        media.postId = post.value();
        media.altText = "";
        media.sizes = JsonNodeFactory.instance.objectNode();
        media.extra = JsonNodeFactory.instance.objectNode();
        media.processing = NONE;
        return media;
    }
}
