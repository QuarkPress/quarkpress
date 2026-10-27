# QuarkPress Dev UI

QuarkPress's pages in the Quarkus Dev UI. In `quarkus dev` the Dev UI (`/q/dev-ui`) gets a QuarkPress card with pages
for the installed modules, the sites of the network, the per-site feature switches, the registries, content counts, the
scheduler, the persistence unit and the users of the dev realm. Nothing of it exists in a production build, a test
build, a remote dev session or a native executable: the extension adds no page, no bean and no endpoint outside local
dev mode.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-dev-ui</artifactId>
</dependency>
```

The scope can stay at its default: the extension's build steps run only in local dev mode, so a production build
leaves it out. It needs `quarkpress-core`; the other QuarkPress modules (persistence, multisite, security, posts,
taxonomy, revisions, scheduling) are optional and a page appears only for the modules the application has. The
extension adds no HTTP server: the Dev UI appears only in applications that already serve HTTP, so an application
without `quarkus-vertx-http` (through REST or another HTTP extension) declares it itself.

## Try it

`integration-tests/posts` has every module but multisite and runs in dev mode, with Dev Services for PostgreSQL and
Keycloak (Podman or Docker must be running). After a build of the repository (`mvn install -DskipTests`), from the
repository root:

```shell
mvn -f integration-tests/posts/pom.xml quarkus:dev
```

The QuarkPress card is then on http://localhost:8080/q/dev-ui/extensions.

## Pages

The pages appear in this order; the current site of the pages that need one is a browser setting (the Sites page sets
it, the default is site 1). When the kept site no longer exists (the dev database was recreated, say), a page that
needs one goes back to site 1, keeps that, and says so in a line above its content.

- **Modules** (always): the QuarkPress modules on the class path, read at build time: artifact, version, capability,
  the per-site features each declares with their defaults, its migration band and its configuration key prefixes. No
  call is made.
- **Sites** (multisite): the network's sites in id order, at most 1000, through `Sites.list`; a form creates a site.
  Choosing a site here makes it the current site of the other pages. The Public column reads the site's public
  setting: `yes` (1), `no (asks search engines not to index)` (0), or `other (<n>)` for any other value, as plugins
  use negative values for private sites.
- **Features** (persistence): every registered feature with its value on the current site and where the value comes
  from (the site's own switch, the network default or the feature's default), through `FeatureToggles` and the site's
  features; a switch changes it, and Reset (shown when the value is the site's own switch) removes the site's switch so
  the network default or the feature's default applies again.
- **Persistence** (persistence): QuarkPress's persistence unit and datasource, the database kind, the Flyway history
  table and the applied migrations (version, description, script, time, success), from Flyway.
- **Registries** (posts): the registered post types, all post statuses (the internal `request-*` ones included, in
  registration order), the taxonomies and the roles of the current site with their capabilities. The taxonomies need
  the taxonomy module and are left out without it.
- **Content counts** (posts): the current site's posts by type and status (pairs with posts only, counted through
  `Posts.query`), its terms by taxonomy and its revisions, autosaves included (`Revisions.countAll`). The terms need the
  taxonomy module and the revisions the revisions module; each is left out without its module.
- **Scheduler** (scheduling): the next 20 scheduled posts of every site, soonest first and overdue ones included
  (`ScheduledPosts.next`), and this node's job status (`SchedulingStatus.snapshot`): last poll, last batch, posts
  published since start, last failure and last housekeeping.
- **Dev users** (security): the users of the dev realm, each with its realm roles, whether QuarkPress has a profile for
  it (found by subject), its roles per site and whether it is a network administrator.

## Writes

Exactly three, as the system identity when the security module is present and as an anonymous identity without it:
creating a site (`Sites.create`), switching a site feature (`FeatureToggles.setForSite`, as the site's own switch) and
removing a site's switch of a feature (`FeatureToggles.clearForSite`). A refusal of the service (an address in use, an
unknown feature) is shown with the service's message. Everything else the pages do is a read.

## Dev users and tokens

The Dev users page reads the realm files named by `quarkus.keycloak.devservices.realm-path` (a list; the default is
`quarkpress-dev-realm.json`), each from the class path or else the file system, whether or not Keycloak runs. A realm
that is missing or unreadable adds no users (an unreadable one is logged), and a user without an id or user name is
skipped. A user in several realms is listed once. When the OIDC extension is installed and Dev Services for Keycloak are
enabled, the page links to the Keycloak page of the OIDC card, where a token is obtained; otherwise there is no link.
In the realm shipped with the security extension a dev user's password is its user name; a realm named by
`quarkus.keycloak.devservices.realm-path` sets its own passwords. The Dev UI mints no token itself.

## How it runs

- Every build step of the extension runs only in local dev mode. No bean of the extension exists in any other mode: the
  runtime jar has no bean index, and its classes reach the container only through the build steps.
- A provider class (the group of JSON-RPC methods of a module) is registered only when its module is installed, so
  the methods of an absent module do not exist: calling one answers "method not found". There are five: persistence
  (`features`, `setFeature`, `clearFeature`, `siteExists`, `persistence`), multisite (`sites`, `createSite`), posts
  (`registries`, `counts`), scheduling (`scheduler`) and security (`devUsers`).
- The work of each call on the QuarkPress services runs on a thread of its own, started with nothing of the caller's
  context, as the system identity when the security module is present and as an anonymous identity without it. (The
  users call reads the realm files first, on the Dev UI's worker thread.) Work on one site runs in that site's scope; a
  call naming no site, or one that does not exist, is refused before any scope opens and writes nothing. (`siteExists`
  answers false for such a site instead: the pages use it to check the site they keep.) Reads across
  sites go through the services that open the network scope themselves; the extension sets no network-scope policy of
  its own.
- A call made inside a transaction is refused.

## Limits

- No production admin UI, no REST endpoint, no content editing and no token minting.
- At most 1000 sites are listed and 20 scheduled posts.
- The scheduler status is that of the node serving the Dev UI; other nodes' jobs are not shown.
- Local dev mode only: remote dev mode gets no Dev UI pages from this extension.
- Without the security module the three writes (a new site, a feature switch and its removal) run under an anonymous
  identity with no capability check; it is a development tool.
- A call that fails, a read naming a site that does not exist included, is logged by Quarkus's Dev UI at ERROR with its
  stack trace ("Error in JsonRPC Call", logger `io.quarkus.devui.runtime.jsonrpc.JsonRpcCodec`): Dev UI logs every
  exception a JSON-RPC method throws and has no quieter path for an expected refusal. The pages check the site they
  keep before reading it, so only a direct call with an unknown site logs one.

## Decisions

- Five providers, one per module, rather than one: a module that is absent takes its methods with it.
- No network-scope policy of its own: every read across sites goes through a service that opens the network scope
  itself, so the Dev UI cannot read more than those services allow.
- The Dev UI runs its service calls on a detached thread as the system identity, so what a page shows does not depend
  on the browser's session, and per-site work is scoped to the site the page names.
- No HTTP dependency: adding one would give production applications an HTTP server they did not ask for. The Dev UI
  therefore appears only where HTTP is served.
- All twelve post statuses are listed, not only the public ones, since the page shows the registry as it is.
- The token link appears only with Dev Services for Keycloak, because only then does the page it points to exist.
- Local dev mode only, not remote dev: the pages write to the database and must never reach a deployed application.
- The query for the next scheduled posts lives in the scheduling module's public API, not in the Dev UI, so the page
  uses what any caller may use (with its capability check).
- No bean index and no bean-defining annotation in the runtime jar: the build steps register the beans and providers
  of the modules that are present, and nothing else.
- A write returns the service's refusal as the error of its result, while a read, or any call naming an unknown site,
  fails the call.
- The current site is a browser setting of the pages, site 1 by default, not state kept by the application.
- A third write, `clearFeature`, the inverse of `setFeature` (F1): without it a site switch, once written, could not be
  undone from the Dev UI. It follows `setFeature`'s rules: an unknown site is refused before any scope opens, an
  unknown feature is the error of the result.
- The pages check the kept site with `siteExists`, a read of the persistence provider, rather than with `sites`: the
  multisite module is optional, and a site-scoped page exists without it.
