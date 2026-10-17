package org.quarkpress.it.tenancy;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.hibernate.annotations.FilterDef;
import org.hibernate.annotations.ParamDef;

/**
 * Site-scoped rows, isolated by an auto-enabled filter on {@code site_id} and a listener that stamps and guards
 * writes. The value -1 stands for the whole network, 0 for no site.
 *
 * <p>A Hibernate {@code @TenantId} String field on this BIGINT column was tried first and failed on PostgreSQL: the
 * tenant filter parameter is bound as varchar ("operator does not exist: bigint = character varying").
 *
 * <p>Known gaps: the filter and the listener alone do not isolate these paths. The {@code persistence} extension
 * closes each one; the spike leaves them open.
 *
 * <ul>
 *   <li>{@code StatelessSession} reads: no filter, so other sites' rows are read. Closed by a build failure on
 *       injecting a stateless session for the {@code quarkpress} unit.
 *   <li>{@code StatelessSession} writes: no entity listener, so rows are written for any site. Same build failure.
 *   <li>Collection loads: a collection without its own {@code @Filter} loads other sites' elements. Closed by a build
 *       check that every collection of site-scoped elements carries the site filter.
 *   <li>Second-level cache: a cached entity is served without the filter. Closed by shipping the unit with the
 *       second-level cache off and failing the build on cacheable site-scoped entities.
 *   <li>Query cache: a cached result is served to another site. Closed by shipping the query cache off.
 *   <li>HQL {@code insert}: bypasses the listener and stores rows for another site. Closed by the build-time query
 *       scanner rejecting {@code insert into} a site-scoped entity.
 *   <li>HQL {@code set siteId}: moves rows to another site. Closed by the same scanner rejecting assignments to
 *       {@code siteId}.
 *   <li>Native load, then a setter: the guard trusts the in-memory site, so a row loaded around the filter can be
 *       changed. Closed by guarding against the loaded site and removing the setter after load.
 *   <li>Rebinding a shared request-scoped site: tasks that propagate the request context see the new site. Closed by
 *       running work for another site in a fresh request context.
 *   <li>{@code disableFilter("qp_site")}: turns isolation off for the session. Closed by the scanner rejecting it in
 *       QuarkPress code; documented as a host-application limit.
 *   <li>Native SQL: never filtered. Closed by allowing site-scoped native SQL only in annotated classes that take the
 *       site explicitly; documented as a host-application limit.
 *   <li>No-site inserts: the listener stamps site 0, and only the foreign key refuses it. Closed by the listener
 *       refusing site 0 explicitly.
 * </ul>
 */
@MappedSuperclass
@FilterDef(
        name = SiteScopedEntity.FILTER,
        autoEnabled = true,
        applyToLoadByKey = true,
        defaultCondition = "(:siteId = -1 or site_id = :siteId)",
        parameters = @ParamDef(name = "siteId", type = Long.class, resolver = CurrentSiteIdSupplier.class))
@EntityListeners(SiteStampListener.class)
public abstract class SiteScopedEntity {

    public static final String FILTER = "qp_site";

    @Column(name = "site_id", nullable = false, updatable = false)
    protected Long siteId;

    public String siteId() {
        return siteId == null ? null : siteId.toString();
    }

    /** The value that stands for {@code tenantId} in a query parameter compared with {@code siteId}. */
    static Object siteValue(String tenantId) {
        return Long.valueOf(tenantId);
    }
}
