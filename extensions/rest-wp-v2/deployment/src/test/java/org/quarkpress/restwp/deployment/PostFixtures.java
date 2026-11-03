package org.quarkpress.restwp.deployment;

import io.agroal.api.AgroalDataSource;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * Post rows for the content route tests, written past the service with every column a test may need: type, status,
 * slug, title, content, excerpt, author, parent, menu order, the GMT date (null for a floating draft), the
 * modification date, password and stickiness. Dates are ISO instants such as {@code 2026-01-15T10:00:00Z}.
 */
public final class PostFixtures {

    private PostFixtures() {}

    /** A post to insert; {@link #builder} gives WordPress-like defaults. */
    public static final class Builder {
        private final SiteId site;
        private String type = "post";
        private String status = "publish";
        private String slug;
        private String title = "A post";
        private String content = "";
        private String excerpt = "";
        private Long author;
        private Long parent;
        private int menuOrder;
        private String publishedAt = "2026-01-10T10:00:00Z";
        private String modifiedAt = "2026-01-10T10:00:00Z";
        private String password = "";
        private boolean sticky;

        private Builder(SiteId site) {
            this.site = site;
        }

        public Builder type(String value) {
            type = value;
            return this;
        }

        public Builder status(String value) {
            status = value;
            return this;
        }

        public Builder slug(String value) {
            slug = value;
            return this;
        }

        public Builder title(String value) {
            title = value;
            return this;
        }

        public Builder content(String value) {
            content = value;
            return this;
        }

        public Builder excerpt(String value) {
            excerpt = value;
            return this;
        }

        public Builder author(long value) {
            author = value;
            return this;
        }

        public Builder parent(PostId value) {
            parent = value.value();
            return this;
        }

        public Builder menuOrder(int value) {
            menuOrder = value;
            return this;
        }

        /** The GMT date; null leaves it floating (a draft without a date). */
        public Builder publishedAt(String iso) {
            publishedAt = iso;
            return this;
        }

        public Builder modifiedAt(String iso) {
            modifiedAt = iso;
            return this;
        }

        public Builder password(String value) {
            password = value;
            return this;
        }

        public Builder sticky() {
            sticky = true;
            return this;
        }

        public PostId insert(AgroalDataSource data) {
            String guid = "guid-" + UUID.randomUUID();
            String name = slug == null ? "p-" + UUID.randomUUID() : slug;
            List<Object> params = new ArrayList<>(List.of(site.value(), type, status, name, title, content, excerpt));
            params.add(author);
            params.add(parent);
            params.add(menuOrder);
            if (publishedAt != null) {
                params.add(instantText(publishedAt));
            }
            params.add(instantText(modifiedAt));
            params.add(instantText(modifiedAt));
            params.add(password);
            params.add(sticky);
            params.add(guid);
            Jdbc.update(
                    data,
                    "insert into qp_post (site_id, type, status, slug, title, content, excerpt, author_id, parent_id,"
                            + " menu_order, published_at, created_at, modified_at, comment_status, ping_status,"
                            + " password, sticky, guid) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
                            + (publishedAt == null ? "null" : instant()) + ", " + instant() + ", " + instant()
                            + ", 'open', 'open', ?, ?, ?)",
                    params.toArray());
            return PostId.of(Jdbc.count(data, "select id from qp_post where guid = ?", guid));
        }
    }

    public static Builder builder(SiteId site) {
        return new Builder(site);
    }

    /** A published post of {@code author} dated {@code publishedAtIso}. */
    public static PostId published(AgroalDataSource data, SiteId site, UserId author, String title, String iso) {
        return builder(site)
                .author(author.value())
                .title(title)
                .publishedAt(iso)
                .modifiedAt(iso)
                .insert(data);
    }

    /** The placeholder for a UTC instant given as ISO text in plain JDBC. */
    static String instant() {
        return TestDb.kind().equals("postgresql") ? "cast(? as timestamp with time zone)" : "cast(? as datetime(6))";
    }

    /** The parameter for {@link #instant}: the ISO text on PostgreSQL, {@code Y-m-d H:i:s} in UTC on MySQL. */
    static String instantText(String iso) {
        return TestDb.kind().equals("postgresql") ? iso : iso.replace("T", " ").replace("Z", "");
    }
}
