# QuarkPress Persistence

`org.quarkpress:quarkpress-persistence` gives QuarkPress its own datasource and Hibernate ORM persistence unit, both
named `quarkpress`, with Flyway migrations for PostgreSQL and MySQL. It stores sites, site options, network options
and per-site feature switches, and keeps every site's rows away from the other sites.

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-persistence</artifactId>
</dependency>
<dependency>
  <groupId>io.quarkus</groupId>
  <artifactId>quarkus-jdbc-postgresql</artifactId> <!-- or quarkus-jdbc-mysql -->
</dependency>
```

Public API lives in `org.quarkpress.persistence`: `SiteScope`, `NetworkScope`, `NetworkScopePolicy`, `Options`,
`NetworkOptions`, `FeatureToggles`, `SiteScopedNativeSql` and `QuarkPressPersistence`. Entities and
`org.quarkpress.persistence.runtime` are implementation and may change, with three exceptions that QuarkPress modules
build on and that are kept stable as their SPI although they live in the runtime package: `SiteScopedEntity` (with its
`FILTER` name and `assignSite`) and `TenantIds.param`.

## Configuration

The only required setting is the database kind of the `quarkpress` datasource; Dev Services starts a database in dev
and test mode:

```properties
quarkus.datasource."quarkpress".db-kind=postgresql
# production:
quarkus.datasource."quarkpress".jdbc.url=jdbc:postgresql://db/quarkpress
quarkus.datasource."quarkpress".username=quarkpress
quarkus.datasource."quarkpress".password=...
```

The build stops with an explanation when the datasource is missing or its kind has no migrations.

Everything else has defaults (shipped with ordinal 100) that `application.properties` may override:

- the persistence unit `quarkpress` uses the datasource `quarkpress`, attaches the entities of the `org.quarkpress`
  packages, manages no schema itself, and stores times in UTC;
- its second-level and query caches are off and must stay off (see "Site isolation");
- its JSON columns are read and written by its own Jackson mapper, so the application's `ObjectMapper` settings do not
  change how options are stored, and the application's own unit is untouched;
- Flyway runs at start from `db/quarkpress/common` and `db/quarkpress/<vendor>` into the history table
  `qp_schema_history`, out of order (modules added later still migrate), with `baseline-on-migrate=true` and
  `baseline-version=0`, so on a database the application already populated every QuarkPress script still runs.

PostgreSQL and MySQL are tested on every change; MySQL must be 8.0.16 or newer (CHECK constraints, which keep option
values valid JSON, are enforced from 8.0.16). MariaDB is best effort and untested:
it uses the MySQL migrations, so set `quarkpress.persistence.migration-vendor=mysql`.

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.persistence.migration-vendor` | the datasource's db-kind | folder of vendor migrations: `postgresql` or `mysql` (build time) |
| `quarkpress.main-site-id` | `1` | the site every request belongs to without the multisite module (run time) |
| `quarkpress.multisite.enabled` | `false` | set by the multisite module; turns off the single-site resolver (build time) |

The `quarkpress.*` namespace is reserved: a key under it that no module declares fails the start (environment variables are not checked). Application settings
use their own prefix.

Without the multisite module the application does not start unless the main site's row exists in `qp_site`; the
migrations create site 1 (`localhost`, `/`).

## Running beside your application

QuarkPress can share your application's database. Checklist for the application:

1. **Packages.** Attach your own entities to your persistence unit with `quarkus.hibernate-orm.packages=<your
   packages>`; QuarkPress's unit claims `org.quarkpress`.
2. **Own datasource.** Set `quarkus.datasource."quarkpress".*`. It may reuse your JDBC URL and credentials, for example
   `quarkus.datasource."quarkpress".jdbc.url=${quarkus.datasource.jdbc.url}`; turn its Dev Services off
   (`quarkus.datasource."quarkpress".devservices.enabled=false`) when it should use your dev database.
3. **Flyway baseline on both sides.** The two Flyway runs happen in an order you cannot control, and whichever runs
   second finds a non-empty schema. QuarkPress already ships `baseline-on-migrate=true` and `baseline-version=0` for
   its own Flyway; set `quarkus.flyway.baseline-on-migrate=true` and `quarkus.flyway.baseline-version=0` on yours. Your
   Flyway keeps scanning `db/migration`; QuarkPress's migrations live under `db/quarkpress` with their own history
   table.
4. **JSON columns and a customized `ObjectMapper`.** If your application customizes its `ObjectMapper` and its own
   entities have JSON columns, Quarkus refuses to start your persistence unit until you either provide a
   `@JsonFormat @PersistenceUnitExtension` `FormatMapper` for your default unit or set
   `quarkus.hibernate-orm.mapping.format.global=ignore`. This is a Quarkus rule; QuarkPress's unit is not affected.
   Never change the global format setting to make QuarkPress's unit work: it has its own mapper.
5. **MariaDB.** Set `quarkpress.persistence.migration-vendor=mysql`.
6. **Configuration prefix.** Keep your own settings out of `quarkpress.*`.
7. **Access.** Never read or write `qp_` tables directly; use QuarkPress services and events. See "Limits for
   application code".

## Site isolation

Every site-scoped row carries `site_id`. Two mechanisms keep sites apart:

- **An always-enabled Hibernate filter** (`qp_site`) limits queries, loads by id and filtered collections to the
  current site. The network scope passes `-1` and sees every site; an unresolved binding passes `0` and sees nothing.
  A thread without a request context fails instead of seeing any site.
- **A stamping listener** stamps inserts with the current site and refuses an insert for another site. Updates and
  deletes are compared with the site the Java object had when it was **loaded or stored** (a snapshot), not with its
  `site_id` field, so a row loaded around the filter (for example by native SQL) still cannot be written from another
  site, and an object that was never loaded or stored is refused. The site of a stored row cannot change. Nothing is
  written while no site is bound, and an insert in the network scope must name its site.

What the filter and the listener cannot see is closed by configuration and by build checks:

- The second-level and query caches are off for the unit (cached rows and query results bypass the filter). Turning
  `second-level-caching-enabled` back on, or setting the Hibernate cache properties through `unsupported-properties`,
  fails the build, and so does `@Cacheable` or `@Cache` on a site-scoped entity.
- **`StatelessSession` is banned** for the unit: an update of a detached object whose id was swapped bypasses the
  stored-site snapshot, and stateless DML is not filtered by site. Injecting one (also as `Instance` or `Provider`)
  for the `quarkpress` unit fails the build anywhere in the application.
- HQL bulk `update` and `delete` statements are not seen by the listener; each one names the site (see "Rules for
  module authors").

### Build checks of QuarkPress classes

The build reads the bytecode and mapping annotations of every class in an `org.quarkpress` package and fails when one:

- maps a site-scoped entity, or a collection of site-scoped elements, without `@Filter(name = "qp_site")`;
- runs native SQL (`createNativeQuery` and its variants, JDBC `prepareStatement`, `prepareCall`, `execute*`,
  `addBatch`, or the Vert.x SQL client) while holding a string that names a site-scoped table, unless the class is
  annotated `@SiteScopedNativeSql`; SQL in `@NamedNativeQuery`, `@Formula`, `@Subselect`, `@SQLInsert` and similar
  annotations counts too;
- writes an HQL `insert` (with or without `into`) into a site-scoped entity, or assigns `siteId` in an HQL `set`
  clause, a Panache `update(...)` string, a criteria update or a criteria insert;
- calls `disableFilter`, `enableFilter` or `getEnabledFilter` for the site filter (a new parameter value could show
  every site);
- refers to a `StatelessSession` type (Hibernate ORM or Hibernate Reactive) or a Hibernate method about stateless
  sessions;
- outside the core and persistence modules, calls `SiteBinding.bind` (requests are bound by site resolution; work for
  another binding goes through `SiteScope` or `NetworkScope`).

## Binding work to a site

HTTP requests are bound when the binding is first read (without the multisite module: to the main site). Other code
binds explicitly:

- `SiteScope.run(siteId, work)` / `call(siteId, work)` runs work for one site; inside it, `SiteContext` answers for
  that site and queries see only its rows.
- `NetworkScope.run(work)` / `call(work)` runs work across all sites: queries see every site's rows, and inserts of
  site rows must name their site. It is reserved for QuarkPress's multisite, security and scheduling modules and for
  callers that a `NetworkScopePolicy` bean allows; anyone else gets a `SecurityException`. This rule guards against
  mistakes; it is not a security boundary.

Both follow the same rules:

- **Same binding joins.** When the caller's request is already bound to the target, the work runs in the caller's
  request context and joins its transaction (or starts one when none is active). It commits and rolls back with the
  caller. A caller whose binding has not been read yet counts as a different binding.
- **A different binding runs apart.** Otherwise (another site, the network, an unbound request, or no request context)
  the work runs on a new virtual thread with no context propagated, in a fresh request context bound to the target and
  a transaction of its own; the caller waits for it. It commits or rolls back independently of the caller, and the
  site seen by the caller, by other threads sharing the caller's context and by tasks that captured it never changes.
- **Not on the event loop.** A scope for a different binding opened on a Vert.x event-loop thread is refused with
  `IllegalStateException`.
- Nested scopes for different sites each hold their own transaction and database connection until they finish; deep
  nesting can exhaust the connection pool.
- The work must finish before it returns: a returned `CompletionStage` or `Uni` is not awaited.
- What the work throws, exception or error, is rethrown unchanged; a failed rollback is added to it as suppressed.

A request's binding is written once (see the core module): nothing rebinds a request, and the scopes never change a
binding other code can see.

## Options

`Options` writes the current site's options (`SiteContext.options()` reads them, through the request cache);
`NetworkOptions` reads and writes network options; `FeatureToggles` switches features per site or as network
defaults. Site options and network options live in separate tables (`qp_option`, `qp_network_option`).

### Behaviour compared with WordPress

| QuarkPress | WordPress | Behaviour |
|---|---|---|
| `Options.set(name, value)` | `update_option` | creates (autoloaded) or changes the value; returns whether the value changed. An unchanged value returns false and writes nothing |
| `Options.set(name, value, autoload)` | `update_option` with autoload | a new option, or an existing one whose value changes, takes `autoload`. An unchanged value returns false and keeps the flag |
| `Options.setAutoload(name, autoload)` | `wp_set_option_autoload` | changes only the flag; false when the option does not exist or the flag is already set; fires no event |
| `Options.add(name, value, autoload)` | `add_option` | false, and nothing changes, when the option already exists |
| `Options.delete(name)` | `delete_option` | returns whether the option existed |

- **Names** are PHP-trimmed (space, tab, line feed, carriage return, NUL, vertical tab); an empty name and `"0"` are
  refused, as WordPress refuses them. Names hold at most 191 characters (code points, the same limit on both
  databases), no NUL inside and no unpaired surrogate.
- **Change detection** compares numbers by value (`1`, `1.0` and `1L` are the same) and object keys **in order**:
  `{"a":1,"b":2}` and `{"b":2,"a":1}` differ, as PHP's comparison of the serialized arrays does. Option arrays in
  WordPress are ordered (roles, sidebars, widgets).
- **Events.** A write that creates, changes or deletes a value fires `OptionChanged` (or `NetworkOptionChanged`) inside
  the transaction; an observer that throws rolls the transaction back, whatever the exception type.
- **Errors.** An invalid name or value throws `IllegalArgumentException` before any SQL and leaves the caller's
  transaction usable.
- **Request cache.** A write drops the writing request's cached reads of the option (and of the feature switches), and
  drops them again when the transaction commits or rolls back, so a value another thread of the request cached while
  the transaction was open is not kept.
- **Reads** of the store join the caller's transaction or run in a short one of their own. Reading another site's
  options from a site-bound request finds nothing (the filter); use a `SiteScope` for that site.
- **Typed reads** (`string`, `number`, `flag`) and the **time zone** follow PHP's conversions and WordPress's rules,
  with the differences listed in the core module's README.

### Values

Values are JSON (`JsonNode`) and must be storable the same way on both databases:

- not JSON `null` as a whole value (delete the option instead); `null` inside arrays and objects is allowed;
- U+0000 is allowed in strings and keys (WordPress data can contain NUL); unpaired surrogates are refused (they have
  no UTF-8 form);
- integers within 64 bits, as PHP integers; other numbers finite and within the range of a double;
- at most 64 nested arrays or objects.

Values keep their text: PostgreSQL stores them as `json` and MySQL as `longtext` checked with `json_valid`, so object
keys keep their insertion order on both. This deviates from the original design, which planned `jsonb` on PostgreSQL and
`json` on MySQL: both of those reorder object keys. The cost is that values cannot use `jsonb` operators or indexes;
QuarkPress compares values only by equality. Decimals read back as `BigDecimal` (trailing zeros kept), integers as
int or long nodes, so a value reads back the same on both databases.

### Name collation

Option names compare case- and accent-sensitively on both databases (`utf8mb4_bin` on MySQL, the default
deterministic collation on PostgreSQL): `blogname` and `BlogName` are two options. WordPress's MySQL tables use
`utf8mb4_unicode_520_ci`, where they would be the same option; QuarkPress deliberately differs so both databases
answer alike. Names reach the database already trimmed, and a name with surrounding whitespace is refused before any
query, so MySQL's trailing-space comparison never matches `"foo "` with `"foo"`.

## Feature switches

`FeatureToggles.setForSite` / `clearForSite` change the current site's switch (stored in the site option
`qp_features`) and fire `SiteFeatureToggled` when the site's effective value changes. `setNetworkDefault` /
`clearNetworkDefault` change the network default (network option `qp_feature_defaults`) and fire
`NetworkOptionChanged`. Unknown keys are refused with `IllegalArgumentException`. An observer that throws rolls the
change back, whatever the exception type.

Each change locks what it reads until its transaction ends (`SELECT ... FOR UPDATE`: the site's `qp_site` row and its
`qp_features` option, or the `qp_feature_defaults` network option), so two concurrent changes of different keys both
survive. The effective values before and after a site change are computed from the stored switches, not from the
request's cached feature values. Stored members that are not booleans, and a stored value that is not an object, are
ignored.

A site's switches are read for that site whatever the request is bound to (another site's are read in a scope of its
own). The writing request's cached feature values are dropped on every write and again when its transaction
completes; other requests, and a request whose write ran inside a scope for another binding, see the change from their
next request.

## Limits for application code

QuarkPress checks its own classes (`org.quarkpress` packages) at build time. **Application code outside those packages
is not scanned**, so it must not:

- run native SQL or JDBC on `qp_` tables: the site filter does not see native SQL;
- open a `StatelessSession` for the `quarkpress` unit (`SessionFactory.openStatelessSession()` and similar). Injecting
  one fails the build everywhere, but opening one from the session factory is not detected;
- call `disableFilter("qp_site")` or `enableFilter("qp_site")` (or change its parameter);
- write HQL `insert` statements into QuarkPress entities, or assignments to `siteId`;
- run bulk `update` or `delete` statements on QuarkPress entities without a site condition (the listener does not see
  them);
- turn the second-level or query cache back on for the `quarkpress` unit (the build fails for the settings QuarkPress
  knows) or keep references to QuarkPress entities across requests or scopes for different sites;
- call `SiteBinding.bind`, in particular with `Binding.NETWORK`: that is `NetworkScope` without its caller rule.

Rows that application code loads around the filter still cannot be written from another site: writes are compared
with the site the row was loaded with.

The build checks of QuarkPress classes guard against mistakes, not against deliberate evasion. They do not see:

- SQL held in a non-final or computed static field, or in a field of another class that is not a compile-time
  constant;
- table names built at run time (`"qp_" + suffix`, `String.format` with a variable);
- HQL attribute names held in variables (`"set o." + field + " = :v"`) and criteria paths built from variables;
- reflection and method handles looked up by name (for example the `NETWORK` field, `SiteBinding.bind` or
  `Session.disableFilter` invoked reflectively);
- SQL defined in one class (a helper or constants holder without a native call) and run from another class;
- mapping files (`orm.xml`): named queries, formulas and caching configured there are not read;
- the HQL `sql(...)` function and other native fragments inside HQL strings.

`@SiteScopedNativeSql` covers only the annotated class, not its nested classes. A Panache `update(...)` whose query
string cannot be traced counts every string of its method, so it fails the build rather than slipping through.

## Rules for module authors

- Site-scoped entities extend `SiteScopedEntity` and carry `@Filter(name = SiteScopedEntity.FILTER)`, and so does
  every collection of site-scoped elements; site-scoped entities are never cached. The build checks all three.
- Every site-scoped query also names the site (`where siteId = :siteId`, with `TenantIds.param(site)`), on top of the
  filter. Every HQL bulk `update`/`delete` on a site-scoped entity names the site, and each such statement gets a guard
  test on both databases: the listener does not see bulk statements.
- No HQL `insert` into site-scoped entities, no assignments to `siteId`, no `disableFilter`/`enableFilter` of the site
  filter and no `StatelessSession` in QuarkPress classes; the build rejects them.
- Native SQL on a site-scoped table only in classes annotated `@SiteScopedNativeSql`, taking the site explicitly.
  Declare new site-scoped tables with `SiteScopedTableBuildItem`.
- Every foreign key from a site-scoped table to another site-scoped table is composite and includes the site:
  `foreign key (site_id, x_id) references x_table (site_id, id)`, with `unique (site_id, id)` on the parent. The row's
  own `site_id` (stamped by the listener) is then part of the reference, so the database refuses a row that points at
  another site's row, on both databases and whatever the application code does. The entity may keep mapping the
  association by id alone.
- Inserts in the network scope name their site (`assignSite`).
- Reads are `@Transactional`; writes are `@Transactional(dontRollbackOn = IllegalArgumentException.class)`, so a
  validation error leaves the caller's transaction usable.
- Each module owns one migration version band, declared with `MigrationLocationBuildItem`; two modules claiming the
  same band, or one module claiming two, fail the build. Persistence owns `V1_*`. MySQL tables use
  `ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_bin`; case-insensitive comparisons are written explicitly.

## Design decisions

- Isolation through an always-enabled Hibernate filter on `site_id` and a listener that stamps and guards writes. The
  first choice, Hibernate's `@TenantId` on a `String` field mapped to the `BIGINT` column, failed on PostgreSQL (the
  tenant parameter is bound as text). It is worth re-evaluating when Quarkus supports numeric tenant ids; it would
  replace only `SiteScopedEntity`, its parameter supplier and the listener, since every query already goes through
  `TenantIds.param`.
- A named datasource and persistence unit `quarkpress`, with defaults shipped in the extension, so QuarkPress never
  takes over the application's default unit.
- Sites, the site context, the site filter and the single-site resolver live in core and persistence; multisite adds
  domains and resolver strategies.
- Migrations under `db/quarkpress/{common,postgresql,mysql}`, one version band per module, run out of order.
- Site options and network options in separate tables.
- Option values in text-preserving JSON columns (`json` / `longtext`), so key order survives.
- Blocking Hibernate ORM with Panache repositories.
