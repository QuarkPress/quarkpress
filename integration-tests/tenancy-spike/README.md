# Tenancy spike

Checks that an auto-enabled Hibernate filter on `site_id` and an entity listener that stamps and guards writes
isolate sites from each other, on PostgreSQL and MySQL, in JVM mode and as a native executable. Each criterion is an
HTTP endpoint (`GET /spike/{criterion}` answers `ok`), so the same tests run against the native executable.

```bash
.superpowers/m1a/mvn -pl integration-tests/tenancy-spike -am verify -DtestDb=postgresql
.superpowers/m1a/mvn -pl integration-tests/tenancy-spike -am verify -DtestDb=mysql
```

A Hibernate `@TenantId` `String` field on the `BIGINT` `site_id` column was tried first. It failed on PostgreSQL
because the tenant parameter is bound as `varchar` (`operator does not exist: bigint = character varying`), so the
filter and listener are the mechanism.

## Known gaps

The filter and the listener alone leave these paths open. The spike does not close them; the `persistence`
extension does.

| Path | Effect | Closed by |
|---|---|---|
| `StatelessSession` reads | no filter: other sites' rows are read | build failure on injecting a stateless session for the `quarkpress` unit |
| `StatelessSession` writes | no listener: rows written for any site | the same build failure |
| Collection loads | a collection without `@Filter` loads other sites' elements | build check: every collection of site-scoped elements carries the site filter |
| Second-level cache | cached entities served without the filter | unit ships with the second-level cache off; build fails on cacheable site-scoped entities |
| Query cache | cached results served to another site | unit ships with the query cache off |
| HQL `insert` | bypasses the listener, stores rows for another site | build-time query scanner rejects `insert into` a site-scoped entity |
| HQL `set siteId` | moves rows to another site | the scanner rejects assignments to `siteId` |
| Native load, then a setter | the guard trusts the in-memory site | guard compares against the loaded site; no setter after load |
| Rebinding a shared request-scoped site | propagated tasks see the new site | work for another site runs in a fresh request context |
| `disableFilter("qp_site")` | isolation off for the session | the scanner rejects it in QuarkPress code; documented host-application limit |
| Native SQL | never filtered | site-scoped native SQL only in annotated classes that take the site explicitly; documented host-application limit |
| No-site inserts | the listener stamps site 0; only the foreign key refuses it | the listener refuses site 0 explicitly |
