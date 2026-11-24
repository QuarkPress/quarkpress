# QuarkWord — Project Specification

> Version 0.1 (draft) · 2026-10-10 · Owner: JN
> Home: https://quarkword.org · Code: https://github.com/QuarkWord

---

## 1. Mission

QuarkWord brings the **business features of WordPress** — posts, pages, taxonomies, media, comments, users and roles, multisite, the block editor, imports — to the **Java/Quarkus ecosystem** as a set of independent, composable Quarkus extensions and plain Java libraries.

It is **not a port** of WordPress. It reproduces *what WordPress does for its users*, not *how WordPress is coded*. Where WordPress's design is shaped by PHP's history or by plugin backward compatibility, QuarkWord uses the modern Java/Quarkus way instead.

Goals:

1. A Java developer can add a blog/CMS to any Quarkus application by adding a few dependencies.
2. A WordPress site owner can move to QuarkWord with real tooling: content import, database conversion, password-compatible user migration, and a familiar editor (Gutenberg).
3. Every piece is usable on its own, including in non-QuarkWord projects (e.g. alongside SunsetERP).
4. Make the Quarkus ecosystem more attractive for content-driven applications.

Non-goals:

- Running WordPress PHP plugins or themes.
- Installing plugins at runtime through a web UI.
- Copying WordPress's internal APIs (`add_action`, `the_loop`, global state).

---

## 2. Guiding principles

These are rules. When a design decision conflicts with one, the principle wins unless the spec is amended.

1. **Quarkus and Java best practices first.** Build-time over runtime, CDI over globals, typed APIs over strings, configuration via `@ConfigMapping`, native-image compatible.
2. **Everything is optional.** Each feature is its own module. No module may require a UI, a specific database vendor, a specific storage backend, or a specific identity provider beyond the documented SPI.
3. **Headless core.** The core has no UI. Every capability is reachable through Java APIs and a REST API. Every UI (Gutenberg admin, HTMX admin, Dev UI, a dev's own UI) is a replaceable consumer; removing it must not break anything else.
4. **Modules are chosen at build time.** Adding a dependency to `pom.xml` / `build.gradle` makes a module available to the application (all sites). Per-site enabling/disabling is runtime configuration evaluated by code; it never loads or unloads classes.
5. **WordPress compatibility lives in clearly named modules.** Anything that exists only to match WordPress (WP REST v2 shape, WP database schema, WP hook names, PHP serialization) is isolated in modules prefixed `wp-` or suffixed `-wp`. The native model never bends to fit them.
6. **Original implementation.** WordPress source may be read to understand how a feature behaves. QuarkWord code is an original Java/Quarkus design, never a translation or copy of WordPress PHP. See §13.
7. **Both Postgres and MySQL from day one.** Every persistence feature is tested on both in CI.
8. **Generic pieces have zero Quarkus dependency.** Parsers and format libraries are plain Java so anyone can use them.

---

## 3. Technical baseline

| Item | Decision | Notes |
|---|---|---|
| Java | 21 minimum, CI on 21 and 25 | Revisit when Quarkus moves its baseline |
| Quarkus | Latest Quarkus platform (LTS stream preferred), pinned in `quarkword-bom` | QuarkWord releases follow Quarkus releases closely |
| Build | Maven (Quarkiverse conventions); Gradle consumers supported | Extensions use the standard runtime + deployment module layout |
| groupId | `org.quarkword` | Standalone libs: `org.quarkword.lib` |
| Package root | `org.quarkword` | |
| Databases | PostgreSQL and MySQL (both first-class) | MariaDB best-effort |
| Native image | Required to build and pass tests for every runtime module | |
| Licence (core, libs) | Apache License 2.0 | |
| Licence (UI/theme repos using WP assets) | GPL v2 or later | Kept in separate repositories |
| Frontend preference | Vanilla HTML/CSS, HTMX, Alpine.js, vanilla JS | No React/Angular in QuarkWord-authored UIs (Gutenberg is the exception, as a consumed third-party package) |

---

## 4. Repositories (github.com/QuarkWord)

| Repo | Contents | Licence |
|---|---|---|
| `quarkword` | Monorepo: BOM, all Quarkus extensions, standalone libs, integration tests | Apache 2.0 |
| `quarkword-admin-gutenberg` | Admin app consuming `@wordpress/*` npm packages via `package.json`, talking to `quarkword-rest-wp-v2` | GPL v2+ |
| `quarkword-admin-htmx` | *(post-1.0)* Native admin built with Qute + HTMX + Alpine.js | Apache 2.0 |
| `quarkword-themes` | Starter Qute themes; any WP-derived CSS/images | GPL v2+ (per theme if needed) |
| `quarkword-keycloak` | Keycloak SPI providers (WordPress password hash verifier) | Apache 2.0 |
| `quarkword-plugins-*` | *(post-1.0)* Clean-room re-implementations of popular WordPress plugin features | Per repo |
| `QuarkWord.github.io` | Project website for quarkword.org, built with Roq | Apache 2.0 (content CC BY 4.0) |

---

## 5. Architecture

### 5.1 Layers

```
  UIs (optional, separate)      admin-gutenberg · admin-htmx · Dev UI · your own
           │ REST / GraphQL
  APIs                          rest-wp-v2 · rest (native) · graphql
           │ Java API
  Features                      posts/pages · taxonomy · media · comments · revisions
                                scheduling · search · feeds · sitemap · notifications
           │ SPI
  Core                          content model · site context · events · filter SPIs
                                persistence SPI · security model
           │
  Infrastructure (Quarkus)      Hibernate ORM · OIDC · Scheduler · Mailer · Cache ...
```

### 5.2 Multisite / multi-tenancy (1.0 requirement)

- **Site** is a first-class entity. A single-site install is a network with one site.
- **Isolation:** discriminator column (`site_id`) on every site-scoped table, using Hibernate's `@TenantId` partitioning. Verify current Quarkus support for discriminator multi-tenancy when implementing; schema-per-site is a possible later option, not 1.0.
- **Site resolution:** a `SiteResolver` SPI resolves the current site per request. Built-in strategies: by domain (domain mapping), by subdomain, by path prefix. Resolution result is exposed as a request-scoped `SiteContext`.
- **Network-level data:** sites, network settings, super-admin assignments, and users are network-scoped.
- **Users** are shared across the network; roles are per site (WordPress behavior).

### 5.3 Per-site feature enabling

- Adding a module to the build makes it available to **all** sites.
- Each module that is per-site switchable declares a feature key (e.g. `comments`).
- A site's settings hold which features are enabled. Defaults come from network settings, then module defaults.
- Modules check `SiteContext.features().isEnabled("comments")` at their entry points, idiomatically via an interceptor binding:

```java
@SiteFeature("comments")       // interceptor: no-op/404 when disabled for current site
@Path("/comments")
public class CommentResource { ... }
```

- Disabled means: REST endpoints return 404, event observers do nothing, filters pass through, admin menu entries are hidden. The code is still loaded.
- The admin and Dev UI show a read-only list of **installed** modules (generated at build time via build items) with per-site **enabled** toggles.

### 5.4 Extension points (replaces WordPress hooks)

No string-keyed global hook bus in the core. Four typed mechanisms, each the idiomatic Quarkus/CDI tool for its job:

| Need | WordPress | QuarkWord |
|---|---|---|
| Register things known at build time (post types, block types, taxonomies, admin menu entries, REST routes) | `register_post_type`, `register_block_type` | **Build items** produced in an extension's deployment module, or annotation scanning (`@PostType`, `@BlockType`) processed at build time |
| React to something happening | `do_action('publish_post')` | **Typed CDI events**: `@Observes PostPublished`, ordered with `@Priority`; `@ObservesAsync` for fire-and-forget |
| Transform a value through a chain | `apply_filters('the_content', ...)` | **Typed filter SPIs** (`ContentFilter`, `TitleFilter`, `QueryFilter` ...) injected as `@All List<T>`, ordered by `@Priority` |
| Replace a whole service | Pluggable functions (`wp_mail`) | **`@Alternative @Priority`** bean |
| Wrap a service | — | **CDI `@Decorator`** |

```java
public interface ContentFilter {
    String apply(String html, RenderContext ctx);
}

@ApplicationScoped
@Priority(100)
public class SmartQuotes implements ContentFilter {
    public String apply(String html, RenderContext ctx) { ... }
}

// core
@Inject @All List<ContentFilter> filters;   // confirm ArC ordering direction; document one convention
```

Rules:
- Every event and filter type is a documented public API with a stability guarantee.
- Events are immutable records. Filters return new values; they never mutate inputs.
- A string-named hook layer (`do_action`/`apply_filters` style) may exist only in the optional `quarkword-wp-hooks` module for literal plugin ports.

### 5.5 Content model (native)

| Entity | Key fields | Notes |
|---|---|---|
| `Site` | id, name, primary domain, path, status | network-scoped |
| `SiteDomain` | site, domain | domain mapping |
| `Post` | id, site, type, status, slug, title, content (block HTML), excerpt, author (user id), parent, menu order, published at, modified at, comment status, password, meta (JSON) | type covers post, page, attachment, reusable block, custom types |
| `PostType` | key, labels, supports, hierarchical, public, REST base | registered at build time |
| `Taxonomy` | key, labels, hierarchical, object types | registered at build time; `category`, `post_tag` built in |
| `Term` | id, site, taxonomy, name, slug, parent, description, meta (JSON) | |
| `TermAssignment` | post, term, order | |
| `Revision` | post, content snapshot, author, created at, autosave flag | |
| `Comment` | id, site, post, parent, author user id or guest (name, email, url), content, status, ip, user agent, meta (JSON) | |
| `Media` | post (attachment), storage key, mime type, size, dimensions, generated sizes (JSON), alt text | |
| `UserProfile` | user id (OIDC `sub`), display name, nicename, bio, meta (JSON) | identity lives in the IdP |
| `SiteMembership` | user, site, role | |
| `Option` | scope (network/site), key, value (JSON), autoload | |

- Meta and options use **JSON columns** via Hibernate's portable `@JdbcTypeCode(SqlTypes.JSON)` (JSONB on Postgres, JSON on MySQL). No PHP-serialized data in the native schema, ever.
- All timestamps stored in UTC (`Instant`); site timezone is a site option.
- Migrations: Flyway, with `db/migration/common`, `db/migration/postgresql`, `db/migration/mysql`.
- Each feature module owns its tables and migrations. QuarkWord uses its **own named persistence unit** so it can live beside other applications' entities (e.g. SunsetERP) in one app.

---

## 6. Module catalogue

Status: **1.0** = required for 1.0 · **Post** = after 1.0 · **Later** = idea backlog.
artifactIds below omit the `quarkword-` prefix for brevity unless shown.

### 6.1 Standalone libraries (plain Java, zero Quarkus dependency)

| Artifact | Purpose | Status |
|---|---|---|
| `lib-php-serialize` | Read/write PHP `serialize()` format | 1.0 |
| `lib-wp-blocks` | Parse/serialize Gutenberg block markup (`<!-- wp:name {json} -->`) | 1.0 |
| `lib-wp-shortcode` | Parse WordPress shortcodes (`[gallery ids="1,2"]`) | 1.0 |
| `lib-wxr` | Streaming read/write of WordPress eXtended RSS export files | 1.0 |
| `lib-wp-password` | Verify WordPress password hashes (phpass portable `$P$`, bcrypt, WP 6.8+ `$wp$` format) | 1.0 |
| `lib-wp-kses` | HTML sanitizing with WordPress kses semantics (allowlists, URL protocols, safe CSS) | 1.0 |

### 6.2 Foundation

| Artifact | Purpose | Depends on | Status |
|---|---|---|---|
| `quarkword-bom` | Aligns all versions with the Quarkus platform | — | 1.0 |
| `quarkword-core` | Content model API, `SiteContext`, events, filter SPIs, persistence SPI, `@SiteFeature` | Quarkus Arc | 1.0 |
| `quarkword-persistence` | Native schema: Hibernate ORM Panache + Flyway, Postgres and MySQL | core | 1.0 |
| `quarkword-multisite` | Site entity, `SiteResolver` strategies, domain mapping, network admin | core, persistence | 1.0 |
| `quarkword-security` | OIDC integration, role → capability model, permission checkers | core, quarkus-oidc | 1.0 |
| `quarkword-security-jpa` | Lightweight local-users alternative (no IdP) for tiny installs | core | Post |

### 6.3 Content features

| Artifact | Purpose | Status |
|---|---|---|
| `quarkword-posts` | Posts, pages, custom post types, statuses, slugs, sticky, password-protected | 1.0 |
| `quarkword-taxonomy` | Categories, tags, custom taxonomies | 1.0 |
| `quarkword-media` | Media library contracts: upload transport SPI, storage SPI, processing pipeline (image sizes, metadata) | 1.0 |
| `quarkword-media-storage-fs` | Filesystem storage | 1.0 |
| `quarkword-media-storage-s3` | S3 / MinIO storage | 1.0 |
| `quarkword-upload-tus` | Resumable uploads via `quarkus-tus` (github.com/SiteNetSoft/quarkus-tus) | Post |
| `quarkword-revisions` | Revisions and autosaves | 1.0 |
| `quarkword-comments` | Comments, threading, moderation queue | Post |
| `quarkword-scheduling` | Scheduled publishing (Quarkus Scheduler / Quartz for clustered) | 1.0 |
| `quarkword-search` | Full-text search via Hibernate Search (OpenSearch/Elasticsearch backend); simple DB search fallback | Post |
| `quarkword-feeds` | RSS 2.0 / Atom feeds | Post |
| `quarkword-sitemap` | XML sitemaps | Post |
| `quarkword-notifications` | Email notifications via Quarkus Mailer | Post |
| `quarkword-menus` | Navigation menus | Post |
| `quarkword-cache` | Page/object caching via Quarkus Cache / Redis | Post |

### 6.4 APIs

| Artifact | Purpose | Status |
|---|---|---|
| `quarkword-rest-wp-v2` | WordPress REST API v2-compatible endpoints (§9) | 1.0 |
| `quarkword-rest` | Native REST API, OpenAPI-documented | Post |
| `quarkword-graphql` | GraphQL via SmallRye GraphQL (WPGraphQL-inspired schema) | Later |

### 6.5 Rendering

| Artifact | Purpose | Status |
|---|---|---|
| `quarkword-theme-qute` | Theme engine: template hierarchy on Qute, theme config, assets via Web Bundler | 1.0 |
| `quarkword-blocks-render` | Server-side rendering for dynamic core blocks (latest posts, query loop, etc.) | 1.0 |
| `quarkword-roq` | Static export of a site through Roq; QuarkWord works fully without it | Post |

### 6.6 Migration and WordPress compatibility

| Artifact | Purpose | Status |
|---|---|---|
| `quarkword-import-wxr` | Import a WXR file into a target site | 1.0 |
| `quarkword-migrate-wpdb` | Convert a live WordPress database (single or multisite) into the native schema | Post |
| `quarkword-persistence-wpdb` | Read (later read/write) adapter over a WordPress schema | Later |
| `quarkword-wp-hooks` | String-named action/filter layer for literal plugin ports | Later |
| `keycloak-wp-password` (repo `quarkword-keycloak`) | Keycloak credential provider accepting WordPress hashes, rehashing on login | 1.0 |

### 6.7 Admin and tooling

| Artifact / repo | Purpose | Status |
|---|---|---|
| `quarkword-admin-gutenberg` (repo) | Gutenberg-based admin, separate GPL repo | 1.0 |
| `quarkword-dev-ui` | Dev UI cards: installed modules, sites, feature toggles, content counts | 1.0 |
| `quarkword-admin-htmx` (repo) | Native admin and simpler editor with Qute + HTMX + Alpine.js + vanilla JS | Post |

### 6.8 Integrations

| Artifact | Purpose | Status |
|---|---|---|
| `quarkword-camel` | Camel Quarkus component: content events as Camel endpoints (syndication, ingestion) | Later |
| SunsetERP interop | Shared conventions (identity key, persistence units, BOM alignment); see §12 | Later |

---

## 7. Standalone library specifications

### 7.1 `lib-php-serialize`

- Parses into a generic tree: `PhpNull`, `PhpBool`, `PhpInt`, `PhpFloat`, `PhpString` (raw bytes + decoded text), `PhpArray` (ordered, int/string keys), `PhpObject` (class name + properties), `PhpCustomObject` (`C:` payload as bytes), `PhpReference`.
- Supports types `N`, `b`, `i`, `d`, `s`, `a`, `O`, `C`, `r`, `R`, and `E` (enums) where present.
- **String lengths are byte counts**; parsing is on bytes with a caller-supplied charset for decoding (default UTF-8, fall back to Latin-1 for legacy data).
- **Never instantiates Java or PHP classes from data.** No reflection, no class lookup.
- Configurable limits: max depth, max element count, max string size.
- Writer produces byte-identical output for round-trips of supported input.
- Convenience: `PhpValue.toJson()` / `toJavaObject()` (maps, lists, scalars).
- WordPress helpers `WordPressSerialization.isSerialized`, `maybeSerialize` and `maybeUnserialize` with WordPress's exact rules.
- Test corpus: real-world WP options, widget settings, broken lengths after search-and-replace (common corruption; offer a lenient "repair lengths" mode).

### 7.2 `lib-wp-blocks`

- Implements the documented block serialization grammar: block comment delimiters, namespaced names (`core/` implied when absent), JSON attributes, self-closing blocks, nested inner blocks, freeform (classic) HTML between blocks.
- Output: `List<Block>` with name, attributes (Jackson `JsonNode` or `Map`), inner HTML, inner content (ordered strings and nulls marking inner block positions), inner blocks.
- Serializer that round-trips parser output exactly.
- Conformance: validate against the public fixtures published with the Gutenberg project's parser specification.

### 7.3 `lib-wp-shortcode`

- Parse `[tag attr="x" attr2=y]content[/tag]`, self-closing, escaped `[[tag]]`, positional attributes.
- Tag registry supplied by caller (shortcodes are only recognized when registered, as in WP).
- Renderer callback interface; unknown shortcodes left as text.
- Full WordPress Shortcode API surface: `do_shortcode` (including shortcodes inside HTML attributes), `strip_shortcodes`, `has_shortcode`, `shortcode_atts`, `shortcode_parse_atts`, `shortcode_unautop`, and the shortcode filter hooks as typed Java extension points.
- Attribute values produced inside HTML attributes go through a pluggable sanitizer; `lib-wp-kses` provides the full WordPress one.

### 7.4 `lib-wxr`

- StAX streaming reader (large exports must not load into memory) and writer.
- Model: channel (site info), authors, categories, tags, terms, items (posts/pages/attachments/custom types) with postmeta and comments.
- Handles WXR versions 1.0–1.2 and the namespaces used by exports.
- Postmeta values are surfaced raw and, when WordPress's `is_serialized` rule says so, also parsed via `lib-php-serialize`.

### 7.5 `lib-wp-password`

- `boolean verify(String password, String storedHash)` and `HashType detect(String)`.
- Supports phpass portable hashes (`$P$` / `$H$`), plain bcrypt (`$2y$`), the WordPress 6.8+ `$wp$`-prefixed bcrypt variant (confirm the exact pre-hash algorithm from public documentation before implementing), and legacy MD5 (32 hex chars).
- No hashing of new passwords in WP formats; new passwords are always hashed by the IdP.

### 7.6 `lib-wp-kses`

- WordPress kses semantics: element and attribute allowlists (the "post", "data" and other contexts), allowed URL protocols, entity normalization, and the safe-CSS filter for `style` attributes.
- Pluggable allowlists so hosts can extend them like the WordPress filters do.
- Implements the attribute sanitizer interface defined by `lib-wp-shortcode`.

### 7.7 WordPress parity testing

- Every standalone lib is checked against the real WordPress code it mirrors: a test-only module downloads a pinned WordPress release at test time, runs its PHP functions with the local `php`, and compares results with the Java library on hand-written, fixture and generated inputs.
- Known, deliberate differences are listed per lib with a reason; any new difference fails the build. The parity profile runs in CI.

---

## 8. Security

### 8.1 Identity

- Code against **`quarkus-oidc`** only; no Keycloak-specific client APIs in core. Keycloak is the default and documented IdP; Dev Services starts one in dev/test.
- User identity key everywhere: OIDC `sub`. QuarkWord stores profile data only (`UserProfile`), never credentials.
- One realm per network. Site membership and roles are QuarkWord data (`SiteMembership`), optionally synchronized from Keycloak groups/organizations by a sync module (Post).

### 8.2 Roles and capabilities

- Built-in roles mirror WordPress: `super_admin` (network), `administrator`, `editor`, `author`, `contributor`, `subscriber` (per site).
- Capabilities mirror WordPress names (`edit_posts`, `edit_others_posts`, `publish_posts`, `delete_posts`, `edit_published_posts`, `upload_files`, `moderate_comments`, `manage_categories`, `manage_options`, `edit_pages`, `list_users`, `promote_users`, ...) because users and the REST v2 API depend on them. Exact role → capability defaults are documented in a table in the module docs, built from public WordPress role documentation.
- Custom roles and capabilities: registered at build time, assignable per site at runtime.
- Enforcement uses Quarkus `@PermissionsAllowed` with permission checker beans for **resource-level** rules (e.g. author may edit *own* post; contributor may edit own *draft*).

### 8.3 Migration of WordPress users

- `quarkword-keycloak` provides a Keycloak credential/hash provider using `lib-wp-password` logic: on first login it verifies the WP hash and rehashes with Keycloak's algorithm.
- Importers create Keycloak users through the Keycloak Admin API (behind a `UserProvisioner` SPI so other IdPs can be supported).

---

## 9. `quarkword-rest-wp-v2` scope

Base path `/wp-json` (configurable). Target: **Gutenberg can create, edit, preview, schedule and publish posts and pages with media.** Everything else follows.

1.0 endpoint set (validate against the editor during development; add what it actually calls):

- `GET /wp-json/` discovery index, `/wp/v2` namespace index
- `posts`, `pages` (CRUD, `context=edit|view|embed`, `_embed`, `_fields`, pagination headers `X-WP-Total` / `X-WP-TotalPages`)
- `posts/{id}/revisions`, `posts/{id}/autosaves` (and pages)
- `media` (multipart upload, update alt/caption, delete)
- `categories`, `tags`, `taxonomies`, `types`, `statuses`
- `users`, `users/me`
- `settings`
- `blocks` (reusable blocks), `block-types`, `block-renderer/{name}`
- `block-patterns/patterns`, `block-patterns/categories`
- `search`
- `oembed/1.0/proxy`
- `themes?status=active` (theme supports only)

Rules:
- Response shapes follow the public REST API handbook and observed responses; differences are documented.
- Errors use the WP error shape (`code`, `message`, `data.status`).
- Auth: Bearer token (OIDC). Gutenberg admin uses an `apiFetch` middleware to attach it. Application-password-style auth is Post.
- Multisite: site resolved by `SiteResolver` like every other request.

---

## 10. Media and uploads

Three independent concerns:

1. **Transport** — how bytes arrive. SPI `UploadTransport`. Built in: multipart (required by WP REST v2). Optional: `quarkword-upload-tus`. Devs can provide their own.
2. **Storage** — where bytes live. SPI `MediaStorage` (put, get, delete, public URL / signed URL). Built in: filesystem, S3/MinIO.
3. **Processing** — what happens after. A `MediaStored` event triggers async processing: image sizes (configurable set, WP defaults: thumbnail, medium, medium_large, large), EXIF/metadata extraction, MIME validation. Image library choice to be decided (must be native-image friendly).

`quarkword-media` depends on none of the implementations.

---

## 11. Rendering

- **`quarkword-theme-qute`:** a theme is a set of Qute templates + config + assets. Template resolution follows the WordPress template hierarchy concept (`single-{type}-{slug}` → `single-{type}` → `single` → `singular` → `index`), implemented in Java.
- Saved block HTML is rendered as-is; dynamic blocks are rendered by `quarkword-blocks-render` (`BlockRenderer` beans, registered by block name at build time).
- Theme template helpers exposed as Qute template extensions / `@Named` beans (`site`, `posts`, `menus`, `assets`), not globals.
- Themes are packaged as JARs (classpath templates) or directories (configurable), chosen at build time.
- **`quarkword-roq`:** exports a site to static files through Roq. Optional.

---

## 12. Interop with other Quarkus applications (incl. SunsetERP)

- QuarkWord BOM is aligned to the same Quarkus platform version as companion projects.
- QuarkWord entities live in their own named persistence unit (`quarkword`).
- Cross-system identity key is the OIDC `sub`.
- No module references another project's schema; integration happens through events, REST, or Camel.

---

## 13. Source policy

Allowed sources:
- WordPress PHP source, read to understand behavior and to build test harnesses.
- Public documentation (REST API handbook, developer reference prose, block grammar documentation, WXR samples, role/capability docs).
- Observed behavior of a running WordPress instance (HTTP requests and responses, database contents of a test install, exported files), including WordPress code run as a black-box oracle in tests.
- Third-party specs and articles describing formats.
- Gutenberg's published npm packages, **consumed** as dependencies only in GPL repos.

Not allowed in Apache-licensed repos:
- Translating or copying WordPress PHP source code: no line-by-line ports, no copied regexes, identifiers or comments. Code follows Java and Quarkus idioms and is designed independently.
- Copying WordPress CSS, images, or text assets (those belong in GPL repos).
- Committing WordPress source files. Test harnesses download a pinned release at test time.

When a component must reproduce a WordPress algorithm closely (parsers, hashing, formatting), use two steps: one person or agent reads the source and writes a behavior description in prose with examples and no code; the implementation is written from that description, and differential tests against the real WordPress code confirm it.

Contributors confirm the policy in the PR template. Get a legal review before 1.0.

---

## 14. Quality and CI

- CI matrix: {PostgreSQL, MySQL} × {JVM, native} for every runtime module; Java 21 and 25 for JVM.
- Tests use Dev Services / Testcontainers; no external services required to run the build.
- Each extension: unit tests, `@QuarkusTest` integration tests, native integration tests.
- WP-compat modules get **conformance suites**: recorded request/response fixtures from a reference WordPress instance, replayed against QuarkWord.
- Standalone libs: fuzz tests for parsers (php-serialize, blocks, shortcode, WXR).
- Code style: Spotless (or Quarkus formatter config), Conventional Commits, semantic versioning.
- Documentation: every module has a docs page on quarkword.org (Antora-style or Roq-generated).

---

## 15. Website — `QuarkWord.github.io`

- Repo name `QuarkWord.github.io`, custom domain `quarkword.org` (`CNAME` file).
- Built with **Roq**, deployed by GitHub Actions to GitHub Pages.
- Hand-written vanilla HTML/CSS for the landing page; Roq/Qute layouts for docs and blog.
- Pages: Home (what, why, quick start), Modules (catalogue generated from a data file), Docs, Migration guide (from WordPress), Blog, About/Licence.
- Assets: `quarkword-logo.svg`, `quarkword-mark.svg`. Colours: ink `#1E2A3A`, accent `#E8553D`, light ink `#E9EEF5` for dark mode.
- Milestone: later, move the site onto QuarkWord itself (dogfooding).

---

## 16. Roadmap

### M0 — Foundation
- Monorepo skeleton, `quarkword-bom`, parent POM, CI matrix, release pipeline.
- Standalone libs: `lib-php-serialize`, `lib-wp-blocks`, `lib-wp-shortcode`, `lib-wxr`, `lib-wp-password`, `lib-wp-kses`, each checked against WordPress (§7.7).
- Website v0 on Roq.

### M1 — Core
- `core`, `persistence` (Postgres + MySQL), `multisite`, `security`, `posts`, `taxonomy`, `revisions`, `scheduling`, `dev-ui`.

### M2 — Editing
- `media` + fs/S3 storage, `rest-wp-v2` (§9), `quarkword-admin-gutenberg` repo, `quarkword-keycloak`.

### M3 — Publishing → **1.0**
- `theme-qute`, `blocks-render`, `import-wxr`, starter theme, migration guide.
- Legal review of the source policy and licensing setup.

### Post-1.0 (rough order)
`comments` · `feeds` · `sitemap` · `search` · `menus` · `notifications` · `cache` · `upload-tus` · `migrate-wpdb` · `rest` (native) · `roq` · `security-jpa` · `admin-htmx` · `graphql` · `camel` · `persistence-wpdb` · `wp-hooks` · clean-room plugin ports.

---

## 17. Open questions

1. Image processing library (native-image friendly).
2. Discriminator multi-tenancy: confirm current Quarkus/Hibernate support level and query-filter guarantees.
3. Keycloak organizations vs groups for optional membership sync.
4. Docs tooling: Roq-only or Antora for versioned docs.
5. Exact Gutenberg package versions to target and upgrade cadence.
6. Exact WordPress 6.8+ password hash format details.
7. Trademark search for "QuarkWord" before 1.0 (name is close to QuarkXPress).

---

## 18. Glossary

- **Module / extension** — a Quarkus extension (runtime + deployment) published by QuarkWord.
- **Library** — a plain Java artifact with no Quarkus dependency.
- **Network** — the whole installation; contains one or more **sites**.
- **Feature** — a per-site switchable capability provided by an installed module.
- **WXR** — WordPress eXtended RSS, WordPress's export format.
