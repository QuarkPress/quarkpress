package org.quarkpress.posts.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import java.util.Objects;
import org.hibernate.Session;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.SiteScopedNativeSql;
import org.quarkpress.persistence.runtime.Dialects;

/**
 * Finds the posts of a site whose featured image is a given attachment, with each database's own JSON reads: any
 * {@code _thumbnail_id} value, read as text, equal to the id's decimal text. A value stored as the number {@code 12}
 * and one stored as the text {@code "12"} both match; {@code "012"} and {@code 12.0} do not.
 */
@SiteScopedNativeSql
@ApplicationScoped
public class FeaturedImageReferences {

    // each value is compared as text on both databases: on PostgreSQL the elements are read as text, which a number
    // parameter never equals; a value that is not an array names nothing
    private static final String POSTGRESQL = "select id from qp_post where site_id = ?1 and exists (select 1 from"
            + " jsonb_array_elements_text(case when jsonb_typeof(cast(meta as jsonb) -> '_thumbnail_id') = 'array'"
            + " then cast(meta as jsonb) -> '_thumbnail_id' else cast('[]' as jsonb) end) as v(t) where v.t = ?2)"
            + " order by id";
    // a join, not exists: MySQL 9.5 was seen answering no row for the same condition as a correlated exists
    private static final String MYSQL = "select distinct p.id from qp_post p, json_table(p.meta,"
            + " '$._thumbnail_id[*]' columns (t varchar(1024) path '$' null on error)) as v"
            + " where p.site_id = ?1 and v.t = ?2 order by p.id";

    @Inject
    PostRepository posts;

    /** The ids of the posts of {@code site} with a {@code _thumbnail_id} value naming {@code id}, ascending. */
    public List<Long> postsNaming(SiteId site, long id) {
        Objects.requireNonNull(site, "site");
        Session session = posts.getSession();
        return session.createNativeQuery(Dialects.postgresql(session) ? POSTGRESQL : MYSQL, Long.class)
                .setParameter(1, site.value())
                .setParameter(2, Long.toString(id))
                .getResultList();
    }
}
