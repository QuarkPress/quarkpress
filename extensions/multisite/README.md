# QuarkPress Multisite

Several sites on one QuarkPress network, as WordPress multisite has them: each request is matched to a site by its
domain and path, sites can have further domains (aliases), and the network's administrator creates, changes and
deletes sites. Without this extension every request belongs to the main site.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-multisite</artifactId>
</dependency>
```

The extension brings `quarkpress-persistence` and Quarkus's HTTP layer. It sets `quarkpress.multisite.enabled=true`,
which removes the single-site resolver.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.main-site-id` | `1` | The main site. Its domain and path are the network's address; the application does not start without it. |
| `quarkpress.multisite.resolvers` | `domain,subdomain,path` | The built-in strategies that resolve sites (see below). |
| `quarkpress.multisite.path-segments` | `1` | How many path segments are compared with site paths (1 to 16): the count WordPress's `site_by_path_segments_count` filter sets (WordPress's own is 1). Under a network path other than `/`, the network path's segments are added. A value outside 1 to 16 stops the application at start. |

`quarkpress.*` is QuarkPress's configuration namespace: keys it does not declare fail the application.

## How a request finds its site

Before any route runs, and before authentication, an HTTP filter builds what resolvers see of the request (scheme,
host and port of the authority, raw path) and asks every `SiteResolver` once, higher `@Priority` first, on a worker
thread. The answer is stored on the request; the request's site context adopts it the first time QuarkPress code asks,
also in security code and filters that run before Quarkus REST. Resolvers are never asked again for that request. A
resolver or database failure fails the request with 500; it never binds "no site" or another site.

All built-in strategies use WordPress's lookup. The raw path is first prepared as WordPress's bootstrap prepares it:
backslashes are removed as PHP's `stripslashes` removes them, and in a path holding `/wp-admin/` everything from its
last occurrence on becomes `/`. A site's domain must equal the host (a `www.` host also matches the domain without it,
and then the longer domain wins before the longer path), and its path must be the longest match among the request
path's first segments (empty segments and segments that are exactly `0` are skipped, as WordPress skips them). How many
segments count follows WordPress's usual configuration: all of them when the host and path are the network's own
address, the network path's segments plus `path-segments` for paths under a network path other than `/`, and
`path-segments` otherwise. Hosts and path segments are compared lower-cased; ports are ignored. The strategies differ
in the hosts they accept, relative to the network domain:

| Strategy | Priority | Hosts |
|---|---|---|
| `domain` | 300 | Domain aliases, for any host; then sites on hosts that are neither the network domain nor one of its subdomains |
| `subdomain` | 200 | `<label>.<network domain>` |
| `path` | 100 | The network domain itself (also with or without `www.`); path segments are counted after the network's path |

Examples with the network at `localhost/` and one segment: `localhost/news/2026/x` → the site `localhost/news/` if it
exists, else the main site; `blog.localhost/x` → the site `blog.localhost/`; `www.example.com/shop/a` → the site
`example.com/shop/` (or `www.example.com/shop/` if that exists).

A request whose host matches no site is served without a site: `SiteContext.isResolved()` is false, every per-site
feature is off and site-scoped data is invisible. The extension does not redirect or reject it, so other applications
on the same server keep working. A known domain with a site at `/` answers unknown paths with that site, as in
WordPress. A raw path with `.` or `..` segments (also written `%2e`) is served without a site, since the router serves
a different path. Requests whose normalized path lies under the Quarkus non-application root (`/q/` by default) are
never resolved, and no site can have a path there.
Archived, spammed, mature and deactivated sites still resolve; `SiteContext.site().status()` tells, and acting on it
(WordPress's archived and suspended pages) is left to HTTP layers.

Code without an HTTP request (jobs, startup code) belongs to no site; run it in `SiteScope` or `NetworkScope`.

Site resolution reads the database on every request; there is no cache yet.

## Deployment and proxies

- The host is the request's authority. Behind a proxy, Quarkus's proxy settings decide which header supplies it:
  `X-Forwarded-Host` is used only when `quarkus.http.proxy.enable-forwarded-host` is on, and a `Forwarded` header's
  `host=` is honoured when `quarkus.http.proxy.allow-forwarded=true`.
- A request with more than one `Host` header is answered with 400.
- An absolute-form request target (`GET http://host/path`) must name the host of the `Host` header (port ignored), or
  the request is answered with 400; the target alone never chooses the site. With
  `quarkus.http.proxy.proxy-address-forwarding=true` the client's own `Host` is no longer visible, so every
  absolute-form target is answered with 400 (proxies send origin-form targets). A target that is neither origin-form
  nor absolute-form is served without a site.
- The check for `.` and `..` segments assumes that no proxy in front treats `\` as a path separator.

## Domain aliases

`SiteDomains.add(site, domain, redirectToPrimary)` gives a site a further domain. A request on an alias resolves to
the site; with `redirectToPrimary`, `SiteContext.redirect()` returns the site's own address
(`<request scheme>://<site domain><site path>`), which HTTP layers can redirect to. The redirect is kept whenever the
resolution's answer is the alias's site, even when an application resolver that runs before the domain resolver
(priority above 300) gave that answer. The address carries no port, since request ports are ignored: a request on a
non-default port is redirected to port 80 or 443. A domain can be the alias of one site only. Since every lookup also
tries a `www.` host without its prefix, an alias may not be a site's domain with or without `www.` (so `www.localhost`
cannot become an alias while a site lives at `localhost`), and a site may not take a domain whose `www.` form, or bare
form, is an alias. A domain that is empty after normalization, or `0`, is refused ("a site domain must not be empty").

## Network administration

`Sites` creates, finds, lists, changes, flags and deletes sites; `SiteDomains` manages aliases.

- Writes require the network capabilities `create_sites`, `manage_sites` or `delete_sites`, asked from the
  `CapabilityCheck` bean. With the security module, the caller must hold them (only network administrators and the
  system identity do); a refused call throws `CapabilityDeniedException`, which the host application maps to 401 or 403
  (see the security README). **Without the security module every caller is allowed**: do not expose these services to
  untrusted callers without it.
- Each write runs in a network transaction of its own, one after the other (they all take one lock row,
  `qp_network_lock`, first). Called from a request bound to a site, it commits independently of the caller's
  transaction; called from a request bound to the network, it joins the caller's transaction.
- A write called inside a transaction (or nested scope) that already holds the network lock, for example after a
  network option or feature default was written in it, is refused with `IllegalStateException` instead of deadlocking:
  call it outside that transaction.
- Changing, flagging or deleting the site the calling request is bound to from inside that request's transaction is
  refused (`IllegalStateException`): the caller's rows of that site lock its record. The same holds inside a
  `SiteScope` or `NetworkScope` opened, directly or through further scopes, from a transaction bound to that site: the
  spawning transaction keeps its locks while it waits for the scope, so the change could only fail with a lock timeout.
- A site update, flag change or deletion can wait for a transaction that is inserting rows of that site (the
  foreign-key check holds a shared lock on the site's record), on both databases. On PostgreSQL only a delete or a
  change of the domain or path waits; on MySQL every change does. If that transaction itself needs the network lock, the
  database detects the deadlock and rolls one of them back.
- Every network write (also network options, feature defaults and the `blogname` renames) waits at most 10 seconds for
  a lock, from the moment it takes the network lock: PostgreSQL's `lock_timeout` for the transaction, MySQL's
  `innodb_lock_wait_timeout` for the session, restored to the server default before the transaction commits (after a
  rollback the pooled connection keeps 10 seconds until its next network write commits). A write that waits longer,
  for instance for a transaction that keeps rows of the site open, fails with the persistence layer's lock-timeout
  exception and rolls back, and the network lock is free again.
- A caller bound to the network joins its own transaction. If it read a site before another write changed it, a
  change of that site fails with `IllegalStateException` ("site n changed concurrently; try again"); retry it in a new
  transaction.
- Domains and paths are normalized as WordPress normalizes them, then lower-cased. A path may hold only URL path
  characters (a `%` only as `%XX`), at most 100; a domain at most 200 characters, no port, not empty and not `0`.
  `(domain, path)` is unique.
- A site path that no request can reach is refused: one with a `.` or `..` segment, a segment `0`, or an interior `//`.
  So is a path under the non-application root (`/q/` by default), where requests are not resolved to sites. The error
  message shows the normalized path, cut to 64 characters.
- Subdirectory sites of the network domain (the network domain exactly, below the network path) may not start their
  path with WordPress's reserved names: `page`, `comments`, `blog`, `files`, `feed`, `wp-admin`, `wp-content`,
  `wp-includes`, `wp-json`, `embed`, as WordPress's Add Site screen refuses them in subdirectory installs.
  `illegal_names` and the other front-end signup rules do not apply.
- A new site gets WordPress's default options, with the JSON type of the value WordPress stores: `blogname` (the
  title; `Site <id>` when the title is empty), `blogdescription` `""`, `timezone_string` `""`, `gmt_offset` `"0"`,
  `date_format` `"F j, Y"`, `time_format` `"g:i a"`, `start_of_week` `1`, `posts_per_page` `10`,
  `default_category` `0`, `show_on_front` `"posts"`, `page_on_front` `0`, `page_for_posts` `0`,
  `default_comment_status` `"open"`, `default_ping_status` `"open"`, and `qp_features` (the switches passed in
  `NewSite.withFeatures`, else `{}`; features not set follow the network defaults). `NewSite.of` creates a public
  site (public 1), as the Add Site screen does.
- Events: `SiteCreated` (preceded by `SiteFlagsChanged` when the new site's public value is not 1, as WordPress fires
  its status transitions on insert, before the site's options exist), `SiteUpdated`, `SiteFlagsChanged` (after
  `SiteUpdated` when a flag or the public value changed) and `SiteDeleted`, fired inside the transaction (an observer
  that throws rolls the change back) and again to `@ObservesAsync` observers after it commits. A `@SiteFeature` on an
  observer of these events checks the event's site. The site's options are gone when `SiteDeleted` is fired, so a
  gated observer of it sees the network defaults, not the switches the site had.
- A site's name and its `blogname` option stay equal. Renaming a site writes the option. In the other direction, once
  a transaction that wrote a text `blogname` has committed, the site is renamed (first 255 characters) a moment later,
  in a network transaction of its own on another thread, which fires `SiteUpdated`; the writer never waits for it.
  Renames of one site converge on the last committed `blogname`. A rename that fails is logged and the option stays
  written; the name stays stale until the next write of `blogname`. Renames still pending when the application stops
  are lost. A `blogname` that is deleted or not text leaves the name unchanged.
- Reads (`find`, `findByAddress`, `list`, `SiteDomains.find`, `forSite`) need no request context. `list` refuses an
  offset whose last index (`offset + limit - 1`) would exceed `Integer.MAX_VALUE`.
- `SiteDomains.remove` returns false for text that does not normalize to a domain, as `find` returns empty.
- The main site's flags cannot change and it cannot be deleted. Its domain and path can change, which moves the
  network.

## Rules for module authors

- Observers of `SiteCreated` run in the network transaction that creates the site. Write the new site's rows there,
  naming the site (`assignSite`); a `SiteScope` for the new site runs in another transaction, which cannot see it.
- Every `site_id` foreign key to `qp_site` is `on delete cascade`, and so is every composite key between site-scoped
  tables, so `Sites.delete` removes all of a site's rows.
- Network writes serialize on the `qp_network_lock` row and change `qp_site` rows under their version, never with a
  row lock. Do not lock `qp_site` rows yourself: a transaction that inserted rows of a site holds a shared lock on
  that site's row, and a lock on it would wait for, or deadlock with, such transactions.
- Do not change, flag or delete the site a request is bound to from inside that request's transaction, nor from a
  scope opened inside it; do it outside the transaction (`Sites` refuses it).
- Keep network transactions short: one that waits more than 10 seconds for a lock fails.
- Do not call `Sites` or `SiteDomains` writes from a transaction that already wrote a network option or feature
  default (`Sites` refuses it).

## Differences from WordPress

| Item | Difference |
|---|---|
| D1 | Domains and paths are stored lower-cased and compared lower-cased; WordPress keeps their case and compares through its database collation (case-insensitive for ASCII). |
| D2 | A domain that is empty (or `0`), has a port or is longer than 200 characters is refused; WordPress stores it. Request ports are ignored, so the redirect of an alias drops the request's port; WordPress keeps ports other than 80 and 443 as part of the host. |
| D3 | A path with characters a URL path cannot carry unescaped, a `%` that does not start a `%XX` escape, or longer than 100 characters, is refused; WordPress stores it. |
| D4 | A request that matches no site is served without a site; WordPress redirects (to the network, or to the signup page in subdomain installs) or, on the network host, shows its database-error page ("not installed"). The scenario oracle maps all three to "none". |
| D5 | Domain aliases with an optional redirect are a QuarkPress addition; WordPress maps a domain by setting the site's own domain. |
| D6 | The main site's domain and path can change through `Sites`; WordPress's site settings screen does not allow it. |
| D7 | `default_category` is 0 until a module creates the default category; WordPress stores 1. `home`, `siteurl` and `blog_public` are not options. |
| D8 | WordPress's front-end signup rules (lowercase letters and digits only, so no upper case and no hyphens; at least 4 characters; not all digits; `illegal_names`) are not applied: `Sites.create` is the network administrator's "Add Site". |
| D9 | Option values keep their JSON types (`posts_per_page` is the number 10); WordPress stores option values as text and reads every scalar back as text (`"10"`). |
| D10 | A raw request path with `.` or `..` segments is served without a site; WordPress compares such segments as written. |
| D11 | A site's title is stored as given; WordPress stores `blogname` HTML-escaped (`Tom &amp; Jerry`). |
| D12 | The main site's flags never change and it is never deleted; WordPress guards it only on some network-admin paths (its direct status actions and the Edit Site form can change its flags), and its delete function marks blog 1 and the main site deleted instead of dropping them. |
| D13 | The request path is cut at the last `/wp-admin/` for every request; WordPress cuts only when the request is an admin request. QuarkPress has no admin flag. |
| D14 | WordPress's `/wp-admin/` cut can fail on a very long line (PCRE backtrack limit) and then yields an empty path; QuarkPress always cuts, in linear time. |
| D15 | Every backslash is removed from the request host; WordPress applies `stripslashes`, which keeps one of a doubled backslash. Hosts containing backslashes are invalid either way. |
| D16 | A site path no request can reach (a `0` segment, dot segments, an interior `//`) is refused at creation, and so is a path under the non-application root (`/q/` by default); WordPress creates such a site, which no request then reaches. |
| D17 | Archived, spammed and deleted sites resolve like any other. WordPress's `ms_site_check` (skipped for super admins) answers a deleted site with `blog-deleted.php` or a 410 page, and an archived or spammed one with `blog-suspended.php` or a 410 page. QuarkPress leaves that to HTTP layers, which read `SiteContext.site().status()`. |

The parity tests (`-Pwp-parity`) compare the path candidates, the domain and path normalization and the reserved names
with WordPress 7.1.3's functions (Tier A), and run site creation, lookup, flag and default-option scenarios against a
full WordPress 7.1.3 multisite installed in a MySQL container (Tier B, `testing/wp-scenario`).

## Design decisions

- One network per installation; its address is the main site's.
- One lookup (WordPress's) behind three strategies that differ only in the hosts they accept.
- The HTTP filter decides the site once and the site binding adopts it, so resolvers never run inside a database
  session, and the event loop never waits for the database.
- Unresolved requests are served, not rejected; HTTP layers decide what to do with them and with redirects.
- Network writes run in the network scope, in their own transaction, after a lock on a row no foreign key refers to;
  site records change under their version. A write refuses to start while the caller's transaction holds that lock,
  and a change of a site refuses while a transaction up the caller's scope chain is bound to that site. Every lock
  wait of a network transaction is bounded (10 seconds), so a wait no database sees as a deadlock still ends.
- The site's name follows `blogname` asynchronously, so a write of the option never waits for the network lock.
- Network capabilities are checked through core's replaceable `CapabilityCheck`, which the security module fills.
