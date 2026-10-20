# QuarkPress Core

`org.quarkpress:quarkpress-core` is the API every QuarkPress module builds on: the current site, typed ids, site
options as read by WordPress code, per-site features, events, rendering filters and the clock. It has no persistence
and no HTTP; the persistence module (`quarkpress-persistence`) stores sites, options and feature switches.

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-core</artifactId>
</dependency>
```

Public API lives in `org.quarkpress.core`; `org.quarkpress.core.runtime` is implementation and may change.

## Configuration namespace

The `quarkpress.*` configuration keys are reserved for QuarkPress. At startup QuarkPress fails on any key under that prefix
that no installed module declares (each module declares its keys; indexed lists such as `resolvers[0]` are accepted,
sub-keys of plain settings are not), so a typo cannot silently leave a setting at its default. Every config source is
checked except environment variables and `.env` entries, whose names are ambiguous (`QUARKPRESS_A_B` could be `a.b` or `a-b`). Application settings use their own prefix. For the
same reason the repository's own build passes the test database as `-DtestDb`, not as a `quarkpress.*` property.

## The current site

Inject `SiteContext` anywhere. It answers for the site the current request is bound to:

- `isResolved()`; `siteId()` and `site()` (both throw `SiteNotResolvedException` without a site; `site()` also when
  the site's row does not exist);
- `options()`: the site's options, read through a per-request cache (see below);
- `zone()`: the site's time zone (see below);
- `features()`: the site's per-site features; without a site every feature is off.

### Binding

Every request context holds one binding: a site, the whole network, or nothing (unresolved). The binding is
**written once**. The first read asks the resolvers with `SiteRequest.none()` and publishes their answer; binding the
same value again is a no-op, and binding a different value throws `IllegalStateException`. A binding never changes
while other threads can see it: work for another site runs in a fresh request context through the persistence
module's `SiteScope` or `NetworkScope`, never by rebinding.

Without the multisite module every request, HTTP or not, belongs to the main site: the persistence module's
single-site resolver answers `quarkpress.main-site-id` without touching the database. The multisite module (`quarkpress-multisite`)
binds HTTP requests from their host and path before any QuarkPress persistence use.

### Resolvers

A `SiteResolver` bean answers which site a `SiteRequest` (scheme, host, port, path) belongs to. All resolvers are
asked in order of `@Priority`, **higher values first** (the order ArC uses for `@All` lists; WordPress runs lower hook
priorities first, QuarkPress does not), and resolvers with equal priority in order of their fully qualified class name,
so the order never depends on discovery order. The first non-empty answer wins. A resolver that throws stops the
resolution (no lower resolver answers in its place), and a resolver that returns `null` fails with a message naming
it. A resolver must not read the current site while it is asked (that throws `SiteNotResolvedException`), and for
`SiteRequest.none()` it must answer without the QuarkPress persistence unit, whose session depends on the answer.

### HTTP layers

The multisite extension's HTTP layer decides a request's site before any QuarkPress code runs (before authentication)
and hands the decision to the request's binding through the internal `PresetSite` bean. The binding adopts it the
first time it is read, and the resolvers are not asked for that request. `SiteContext.redirect()` returns the redirect
decided with it. A preset is a site or "no site", never the network; a `PresetSite` bean outside the multisite
extension fails the build.

## Site options

`SiteContext.options()` reads the current site's options. All autoloaded options of the site are read together the
first time one is needed; other names are read one by one, once per request. Values are copies. Writers (the
persistence module's `Options`) drop what they change from the writing request's cache, also when their transaction
rolls back; other requests read the new value from their next read of the database.

Option names follow WordPress's rules: surrounding space, tab, line feed, carriage return, NUL and vertical tab are
trimmed (PHP's `trim`), and an empty name or `"0"` is refused with `IllegalArgumentException` (WordPress treats both
as empty). A name holds at most 191 characters (code points), no NUL and no unpaired surrogate.

The typed readers convert values the way WordPress code sees them:

| Method | Answer |
|---|---|
| `get(name)` | the JSON value, or empty |
| `string(name, fallback)` | text as is; numbers and booleans as PHP converts them to strings (`true` is `"1"`, `false` is `""`, `5.0` is `"5"`, floats with PHP's precision of 14 digits); otherwise `fallback` |
| `number(name, fallback)` | an integer within 64 bits, or a text holding exactly a decimal integer (optional `-`, digits only); otherwise `fallback`. Stricter than PHP's `(int)`, which reads a leading number and truncates floats |
| `flag(name, fallback)` | false for `false`, `0`, `0.0`, `""`, `"0"`, empty arrays and objects and JSON `null`; true for everything else (also `"0.0"` and `"false"`, as in PHP); `fallback` when the option does not exist |

### Time zone

`zone()` follows WordPress's rules:

1. `timezone_string`, when it is an IANA region id such as `Europe/Paris` (matched ignoring case and PHP-trimmed
   whitespace, at most 64 characters);
2. otherwise `gmt_offset` in hours, read as PHP's `(float)` cast reads it (a leading number, the rest ignored; `true`
   counts as 1) and cut to whole hours and whole minutes the way WordPress formats the offset (`5.75` is `+05:45`,
   `-2.5` is `-02:30`);
3. otherwise UTC.

Where WordPress itself fails or Java cannot follow, QuarkPress differs on purpose:

- a `timezone_string` that is not an IANA region id falls back to `gmt_offset` (WordPress fails). This covers forms
  only Java understands (`UTC+5`, `UTC-03:30`, `UT`, `SystemV/...`), PHP's fixed-offset abbreviations (`EST`, `PST`,
  `CET` as a fixed offset, `z`) and names that exist only in one host's time zone data;
- offsets beyond 18 hours, which `java.time` cannot represent, give UTC.

## Per-site features

A module declares a feature at build time with `SiteFeatureBuildItem` (artifact `quarkpress-core-deployment-spi`): a
key of 1 to 64 characters `a-z`, `0-9`, `-`, `_` starting with a letter, the declaring module, its default and a
one-line description. Two modules declaring the same key fail the build.

A feature is on or off for a site by, in this order: the site's own switch, the network default, the declaring
module's default (`FeatureValue.source()` says which). With no resolved site, in the network scope, or on a thread
without a request context every feature counts as off.

### `@SiteFeature`

`@SiteFeature("key")` on a bean method or bean class runs that code only when the feature is on for the current site.
The site is read on every call. What happens when it is off is decided at build time, in this order:

| Annotated method | When the feature is off |
|---|---|
| Event observer (a parameter annotated `@Observes` or `@ObservesAsync`) | does nothing |
| Filter method (implements a method of an interface annotated `@FilterSpi`, such as `ContentFilter`, `TitleFilter`, `ExcerptFilter`, with the same parameter types) | returns its first argument unchanged |
| REST resource method (carries a JAX-RS path or HTTP method annotation, or its class, a superclass or an interface is a `@Path` resource) | answers **404 with an empty body** |
| Any other method | throws `SiteFeatureDisabledException` |

The 404 comes from a dedicated exception with its own `ExceptionMapper`, registered when Quarkus REST is present.
JAX-RS picks the most specific mapper, so an application's catch-all `ExceptionMapper<Throwable>` does not turn it
into another status. `SiteFeatureDisabledException` has no mapper: when a REST method calls a disabled service and does
not catch it, the application's mappers decide the answer (unmapped, Quarkus answers 500).

On a class, the annotation covers the non-private, non-static methods the class declares, the public methods and
observer methods it inherits from superclasses, and the default methods it inherits from interfaces other than JDK
(`java.*`) interfaces, whose default methods are not gated; `equals`, `hashCode` and `toString` are never gated, and a
method's own annotation wins. It is not inherited: a subclass of a gated class must carry its own.

The build fails, instead of leaving code ungated, when `@SiteFeature`:

- names a key that no installed module declares;
- sits on an interface, an annotation type (stereotype), or an abstract interface method (default methods are
  allowed);
- sits on, or a class-level annotation covers, a private or static method (private observers included), a producer
  or disposer method, a producer field, or a `@PostConstruct` / `@PreDestroy` method;
- is missing on an override of a gated method (also an override through a generic bridge) or on a subclass of a
  gated class;
- covers a filter method that does not return the type of its first parameter, or a generic filter method;
- covers methods inherited from a class or interface that is not in the bean archive index.

Default methods inherited from JDK interfaces (for example `Iterable.forEach`) are not indexed and stay ungated under
a class-level annotation; annotate an override if one must be gated.

An observer whose event is a `SiteEvent` checks the feature for the event's site, whatever the request is bound to and
also without a request context; this is what `@ObservesAsync` observers of QuarkPress events need, since they run
after the change committed, outside the request that made it.

**Interceptor order.** The gate's interceptor has priority `PLATFORM_BEFORE + 175`: after the security interceptors
(`PLATFORM_BEFORE + 150`), so a caller without access is refused before the feature is checked, and before the
transaction interceptors (`PLATFORM_BEFORE + 200`), so a disabled feature never opens a transaction.

**Reactive endpoints.** Reading a site's feature switches may query the database, which is not allowed on a Vert.x
event-loop thread. A gated REST method that would run on the event loop (one returning `Uni` or `Multi`, or marked
`@NonBlocking`) must be annotated `@Blocking` (or `@RunOnVirtualThread`).

**Caching.** Inside a request the features of each site are computed once and kept until a switch changes. A switch
change through the persistence module (`FeatureToggles`, or a write of the option that stores the switches) drops the
cached values of the writing request, and drops them again when its transaction commits or rolls back. Other requests,
and a request that changed switches inside a scope for another site (which has its own request context), see the
change from their next request. Outside a request context nothing is cached.

## Events and filters

Events (`OptionChanged`, `NetworkOptionChanged`, `SiteFeatureToggled`, `SiteCreated`, `SiteUpdated`,
`SiteFlagsChanged` and `SiteDeleted`) are immutable records fired synchronously
inside the transaction that caused them. A `MissingNode` stands for "no value": the `before` of a new option and the
`after` of a deleted one. Observers that need committed data use
`@Observes(during = TransactionPhase.AFTER_SUCCESS)`; `@ObservesAsync` is fire-and-forget.

QuarkPress services fire every event synchronously inside the transaction that caused it, and again to
`@ObservesAsync` observers once that transaction commits; asynchronous observers never hear of a change that was
rolled back.

### Network capabilities

Network services ask the `CapabilityCheck` bean before every write (`create_sites`, `delete_sites`, `manage_sites`;
see `NetworkCapabilities`). Without the security module the default check allows every caller; with it, the caller's
identity must hold the capability (network administrators in a network, and the system identity). A refused call
throws `CapabilityDeniedException`, which a host application maps to 401 for an anonymous caller and 403 otherwise (see
the security README). Do not provide a second `CapabilityCheck` bean next to the security module: the dependency is
ambiguous.

Filters are beans implementing a filter interface. `Filters.ordered` sorts an `@All List<InstanceHandle<F>>` by
`@Priority`, higher first (none counts as 0), then by the fully qualified bean class name, the same rule as for
resolvers. `Filters.apply` passes a value through the chain: the value must not be null, a filter that returns null
fails with a `NullPointerException` naming its class, and a filter that throws stops the chain with its exception.

`QuarkPressClock` is the time source of QuarkPress decisions; an application bean implementing it replaces the system
clock.

### Roles and capabilities from extensions

`RoleDefinition` describes a role (key, English display name, capabilities in order). An extension registers a role
with `RoleBuildItem` and grants a capability to roles with `CapabilityBuildItem` (both in
`quarkpress-core-deployment-spi`); the security module builds the default role table from them and fails the build
on a role key registered twice, the reserved key `super_admin`, or a capability granted to an unknown role.

## Typed ids

`SiteId`, `UserId`, `PostId`, `TermId` and `RevisionId` wrap positive 64-bit ids, so one kind of id cannot be passed
where another is expected.
