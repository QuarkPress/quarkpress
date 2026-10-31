# QuarkPress REST API (WordPress v2)

WordPress's REST API for QuarkPress sites: the `/wp-json/` index, the `wp/v2` routes for post types, post statuses,
taxonomies, terms, users and settings, with WordPress 7.1.3's URL forms, parameter rules, validation messages, error
objects, headers, pagination, `_fields`, `_links` and `_embed`, encoded as PHP encodes JSON. Clients authenticate with
Bearer tokens of the security module's OIDC provider. Applications add routes and settings of their own through the same
pipeline. It works on a single site and in a network, where every site serves its own API under its own path; a per-site
switch turns it off.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-rest-wp-v2</artifactId>
</dependency>
```

The extension brings `quarkpress-core`, `quarkpress-persistence`, `quarkpress-security`, `quarkpress-posts`,
`quarkpress-taxonomy` and `quarkus-rest-jackson`. It works with the multisite, revisions and scheduling modules when
the application has them, and does not bring them. Public API lives in `org.quarkpress.restwp`;
`org.quarkpress.restwp.runtime` and its `wpv2` package are implementation and may change.

## Using it

### URL forms

Every site serves the API below its own path (`/` for the main site, `/news/` for a subdirectory site, the site's own
domain for a subdomain or mapped site):

- `/<site path>wp-json/<route>`, for example `http://localhost/news/wp-json/wp/v2/categories?per_page=5`;
- `/<site path>index.php/wp-json/<route>`;
- `?rest_route=<route>` in the query of any URL of the site, for example `http://localhost/?rest_route=/wp/v2/tags`;
- `rest_route` as a field of a form POST (`application/x-www-form-urlencoded`) to the site's path or `index.php`
  there; when the query has one too, the two must be equal.

The base (`/wp-json` by default, `quarkpress.rest-wp-v2.path`) counts only right after the site's own path, and a
query `rest_route` wins over the path. Route literals are matched ignoring ASCII case and trailing slashes are ignored,
as in WordPress. Every href the API writes (`_links`, `Location`, pagination `Link`, the API root `Link`) uses the
`/wp-json/` form, whatever the permalink structure (D1).

### Authentication

Send `Authorization: Bearer <access token>` with a token of the security module's default OIDC tenant; the caller is
then the QuarkPress user of that token, with their roles on the requested site. A request without credentials is
anonymous. A token that fails authentication (invalid, expired, signed by an unknown issuer) is refused before any
route is matched, on every route including public ones: 401 `rest_invalid_token` "The access token is invalid or has
expired." with no `Allow` header (D2). There are no cookies, nonces or application passwords: only a request that
carries `Authorization: Bearer ...` has a caller. A request the application authenticated any other way (such as the
session cookie of an OIDC `web-app` or `hybrid` tenant, or a custom `HttpAuthenticationMechanism`) is served as
anonymous, its permissions and the services it calls included, so a page of another site cannot make a browser write
with the user's rights (D24). The index's
`authentication` names the default tenant's issuer, `{"oidc":{"issuer":"<quarkus.oidc.auth-server-url>"}}`, when that
tenant is enabled and configured, else `[]` (D3).

### The site feature

The per-site feature `rest-wp-v2` (on by default; `FeatureToggles`, the `qp_features` option) serves the API for the
site. Off, every REST URL of the site answers 404 `rest_no_route`, as does a request whose host and path resolve to no
site.

### Routes

| Route | Methods | What it serves |
|---|---|---|
| `/` | GET | The site index: name, description, `url`, `home`, time zone and reading settings, the namespaces, the authentication schemes, every route's data, `site_logo`, `site_icon` and `site_icon_url`, and the `help` link; `?context=help` adds each route's schema. |
| `/<namespace>` | GET | One namespace's index (`/wp/v2`, and every namespace an application registers); `?namespace=` names another one. |
| `/wp/v2/types`, `/wp/v2/types/<type>` | GET | The post types registered to show in REST; the edit context needs the type's `edit_posts`. |
| `/wp/v2/statuses`, `/wp/v2/statuses/<status>` | GET | The post statuses that are not internal, then `trash`; a status that is not public is shown only to callers who may edit posts of some REST type. |
| `/wp/v2/taxonomies`, `/wp/v2/taxonomies/<taxonomy>` | GET | The taxonomies registered to show in REST (`?type=` names a post type); the edit context needs the taxonomy's `assign_terms`. |
| `/wp/v2/categories`, `/wp/v2/tags`, `/wp/v2/<rest base>` | GET, POST | The terms of each taxonomy shown in REST, at its REST base: list (readable by anyone) and create. |
| `/wp/v2/<rest base>/<id>` | GET, POST, PUT, PATCH, DELETE | One term: read, update, delete (`force=true` is required, terms have no trash). |
| `/wp/v2/users`, `/wp/v2/users/<id>`, `/wp/v2/users/me` | GET, POST, PUT, PATCH, DELETE | The site's users with WordPress's visibility rules; the only write applied is a user's own `meta.persisted_preferences` (the editor's preferences, kept per site); creating, deleting and other changes answer 501 after WordPress's permission check (D10, D11). |
| `/wp/v2/settings` | GET, POST, PUT, PATCH | The registered settings, for callers with `manage_options` on the site. |

The term and user collections take WordPress's `context`, `page`, `per_page` (1 to 100, default 10), `search` and their
own filters, and answer `X-WP-Total`, `X-WP-TotalPages` and a `Link` header with the previous and next pages. Every
answer of a matched route carries an `Allow` header listing the methods the caller may use there. The global parameters
`_fields`, `_embed`, `_envelope`, `_pretty`, `_method` and the `X-HTTP-Method-Override` header work on every route as in
WordPress; `_jsonp` is always refused (D5).

The routes of posts, pages, revisions, autosaves, search, media, blocks, block patterns, block types, themes,
templates, global styles, navigation, menus, widgets, comments, plugins, site health, oEmbed, application passwords and
`/batch/v1` are not served yet: they answer 404 `rest_no_route`, and the index does not list them (D6). Later releases
add them, starting with posts, pages, revisions and search, then media, then the block editor's own routes.

### Adding routes

An application adds routes with a CDI bean implementing `RestRouteProvider`. Its `register(RouteRegistry)` declares
routes with `route(namespace, pattern)`, each with its endpoints (`Endpoint.of(methods...)` with its arguments,
`Permission` and `Handler`), optional route-wide arguments and an item schema, then `register()`:

```java
@ApplicationScoped
public class BookRoutes implements RestRouteProvider {

    @Inject
    Capabilities capabilities;

    @Override
    public void register(RouteRegistry routes) {
        routes.route("shop/v1", "/books/(?P<id>[\\d]+)")
                .endpoint(Endpoint.of("GET")
                        .arg(Arg.of("format", "string").enumOf("short", "long").defaultValue(TextNode.valueOf("short"))
                                .build())
                        .permission(request -> capabilities.can("read")
                                ? PermissionResult.allowed()
                                : PermissionResult.forbidden(request))
                        .handler(request -> RestResponse.ok(
                                JsonNodeFactory.instance.objectNode().put("id", request.urlParams().get("id"))))
                        .build())
                .register();
    }
}
```

- **Patterns** are WordPress's, below the namespace: literal text and the two groups `(?P<name>[\d]+)` and
  `(?P<name>[\w-]+)`, at most one group per path segment. Anything else is refused with `IllegalArgumentException`
  ("unsupported route pattern: ..."): routes are matched by hand-written code, never by a regular expression on the
  request path.
- **Arguments** (`Arg.of(name, type)` with `enumOf`, `minimum`, `maximum`, `format`, `items`, `defaultValue`,
  `required`, `phase`, and a `sanitize` or `validate` callback of its own) hold a JSON schema as WordPress's `args`
  do. A `VALIDATE` argument is checked before any argument is sanitized, a `SANITIZE` one while they are sanitized, as
  WordPress's two phases do; failures answer 400 `rest_invalid_param` with WordPress's messages, a missing required
  argument 400 `rest_missing_callback_param`. The schema keywords `pattern`, `patternProperties`, `anyOf` and `oneOf`
  are refused when the route is registered (see Limits).
- **Permissions** run after the arguments are checked, before the handler. `Permission.PUBLIC` lets everyone in;
  `PermissionResult.forbidden(request)` is WordPress's `rest_forbidden`, 401 for an anonymous caller and 403
  otherwise; `PermissionResult.denied(error)` answers any other error.
- **Handlers** answer a `RestResponse` (`ok`, `created` with its `Location`, `paged` for a collection page,
  `withStatus`, `withHeader`, `withLinks`) and refuse by throwing `RestException` with a `RestError` (WordPress's code,
  message and data, with its `additional_data` and `additional_errors`). `RestRequest.param(name)` reads a parameter by
  WordPress's precedence: JSON body, form body, query, route groups, defaults.
- **Collections** use `Paging.collectionArgs(itemSchema, withOffset)` for WordPress's common arguments,
  `Paging.of(request)` for the page asked for and `RestResponse.paged(body, total, paging, request)` for the pagination
  headers. `paged` needs the request the server handed to the handler (it knows the route's URL) and throws
  `IllegalArgumentException` for any other `RestRequest`, such as a hand-made stub in a unit test.
- **Fields and links.** `Fields.forResponse(request, itemSchema)` gives once per request the `Selection` of fields to
  compute; the server then reduces the body to `_fields` itself. `Links` (relations, hrefs and attributes such as
  `embeddable`) are rendered into `_links` with `targetHints` for the caller on `self`, and embedded on `_embed`.
  `RestUrls` builds hrefs on the site's API root. An object WordPress builds as a PHP object (`stdClass`), such as a
  post type's `labels`, is a `PhpObjectNode`, which `_fields` keeps whole.
- **Order.** Providers run by `order()` (lower first, then by class name) and namespaces and routes keep their
  registration order, which decides which route a path matches first and how the index lists them. WordPress's own
  routes use orders 0 to 600; the default, 1000, puts an application's routes after them. A route registered again
  under the same pattern gets the new endpoints after its own.
- **When.** The providers are asked once, while the application starts. A registration error (a route pattern or
  schema keyword that is refused, a setting registered twice) fails the start with a message naming the provider and
  the refused registration.

### Adding settings

`/wp/v2/settings` serves WordPress's own settings (`title`, `description`, on a single site `url` and `email`, then
`timezone`, `date_format`, `time_format`, `start_of_week`, `language`, `use_smilies`, `default_category`,
`default_post_format`, `posts_per_page`, `show_on_front`, `page_on_front`, `page_for_posts`, `default_ping_status`,
`default_comment_status`, `site_logo`, `site_icon`), then those of the application's `SettingsProvider` beans, ordered
by class name. A `SettingDefinition` is WordPress's `register_setting` with `show_in_rest`: the REST name, the option
that holds it, its JSON schema type (`string`, `integer`, `number`, `boolean`, `array` or `object`), its default when
the option does not exist, further schema keywords and whether REST shows it.

```java
@ApplicationScoped
public class ShopSettings implements SettingsProvider {

    @Override
    public List<SettingDefinition> settings() {
        ObjectNode schema = JsonNodeFactory.instance.objectNode().put("description", "The shop's currency.");
        schema.putArray("enum").add("EUR").add("USD");
        return List.of(new SettingDefinition(
                "shop_currency", "shop_currency", "string", Optional.of(TextNode.valueOf("EUR")), schema, true));
    }
}
```

A read answers each setting's option, or its default when the option does not exist, converted to the setting's type
(null when the stored value does not fit the schema). A write checks every sent setting first and refuses the whole
request when one fails, then writes them in registration order through WordPress's sanitizing of the built-in options (a
value WordPress refuses keeps the stored one). Null deletes the option, unless the stored option (or, without one, PHP's
false) does not fit the schema: then 500 `rest_invalid_stored_value`, with the settings before it written. The answer
shows the values as written; later reads show the stored form. Names starting with `qp_` are QuarkPress's own and are
refused for the REST name and for the option; `SettingsRegistry` lists what is registered.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.rest-wp-v2.path` | `/wp-json` | The base of the API below each site's path, WordPress's `rest_get_url_prefix`. It must name a path below the site, not `/`. |
| `quarkpress.rest-wp-v2.cors.origins` | none | The origins answered with `Access-Control-Allow-Origin` (compared ignoring ASCII case; printable ASCII without spaces or commas). None by default, so no answer carries that header (D4). |

The API uses the application's OIDC configuration for its index's issuer (`quarkus.oidc.auth-server-url`), and
`quarkus.http.limits.max-body-size` bounds the bodies it reads.

## How a request is served

1. **Entry.** An HTTP filter that runs after the multisite module has resolved the site and before authentication
   decides whether a request is a REST request: a query with a non-empty `rest_route`, a path with the base right
   after the site's path (or `index.php/` and the base), or a form POST to the site's path or `index.php` there. A path
   under Quarkus's non-application root (`/q/` by default) never is. Every other request goes on to the application's
   own routes untouched. A REST request is re-routed internally to the extension's one JAX-RS resource
   (`/_quarkpress/rest-wp-v2/route` below the REST application path and the HTTP root path, computed at build time;
   the build fails when Quarkus REST cannot deploy it there). That resource answers a request that reaches it any
   other way with an empty 404.
2. **Authentication.** The security module authenticates the token. A token that fails goes straight to the answer
   stages below with 401 `rest_invalid_token`, after the site gate, with no route matched.
3. **The route.** `rest_route` comes from the form (for a form POST) or the query, and must be the same text in both;
   else the route is the path's, percent-decoded once. A `rest_route` that is not a string answers 400
   `rest_path_invalid_type`, one that differs between query and form 400 `wp_die` "A variable mismatch has been
   detected.": both before the REST server runs, as JSON for a JSON request and as an HTML page otherwise, with
   no-cache headers and none of the REST headers.
4. **Method override.** A textual `_method` in the query, else the `X-HTTP-Method-Override` header, replaces the
   method, whatever the real one.
5. **Site gate.** No resolved site, or the feature off: 404 `rest_no_route`. A GET or HEAD on a redirecting alias
   domain: 301 to the same path and query on the site's address. An archived, spam or deleted site: 410 with
   WordPress's sentence ("This site has been archived or suspended.", "This site is no longer available."), as a JSON
   `wp_die` object for a JSON request and an HTML page otherwise, for every caller (D13).
6. **JSONP.** A query `_jsonp` that PHP counts as true: 400 `rest_callback_disabled` (D5).
7. **OPTIONS** answers the data of the first route whose pattern matches the path, in the `help` context, with the
   `Allow` header; 200 `[]` when no route matches. Nothing is validated and no handler runs.
8. **Match.** Routes whose namespace starts the path are tried first, then all, in registration order; the first
   route whose pattern matches the whole path wins, then its first endpoint declaring the method (HEAD falls back to
   GET). No match: 404 `rest_no_route`.
9. **JSON body.** A body with a JSON content type that PHP cannot decode answers 400 `rest_invalid_json` with PHP's
   error code and message.
10. **Arguments.** Required arguments, then the validation phase, then the sanitizing phase (which replaces each
    source's values by their sanitized forms), as WordPress checks them.
11. **Permission**, then the **handler**. An exception that escapes either becomes WordPress's error: a
    `RestException` its own error; a refused capability `rest_forbidden` (401 anonymous, 403 otherwise); a disabled
    site feature 404 `rest_no_route`; the posts, taxonomy and revisions modules' exceptions their WordPress codes; a
    stale version 409 `quarkpress_conflict`; anything else 500 `internal_server_error`, logged (D12).
12. **Shaping.** The `Allow` header for this caller (on errors of a matched route too, absent when nothing is
    allowed); the `_fields` reduction (not of errors); the links rendered into `_links`; with `_embed`, every asked-for
    embeddable link fetched once through this same pipeline as a GET of the same caller on the same site, with
    `context=embed`, and set under `_embedded` (not for HEAD).
13. **Answer.** WordPress's fixed headers (`Content-Type: application/json; charset=UTF-8`, `X-Robots-Tag: noindex`,
    the API root `Link`, `X-Content-Type-Options: nosniff`, `Access-Control-Expose-Headers` and
    `Access-Control-Allow-Headers`), the route's own headers over them, `_envelope`, no-cache headers for a logged-in
    caller (and for a 4xx whose method came from the override header), the cross-origin headers, the body encoded as
    PHP's `json_encode` encodes it (`\/`, `\uXXXX` for non-ASCII), pretty printed with `_pretty`; no body for HEAD or
    204. Whitespace runs in header values are collapsed to one space.

## Limits

- Only the routes listed above are served; the rest answer 404 `rest_no_route` until their release (D6).
- Messages are English only (D14).
- The schema keywords `pattern`, `patternProperties`, `anyOf` and `oneOf` are refused when a route or a setting is
  registered: `pattern` would need a regular expression run on request input, and the others are not implemented.
- Route patterns hold literal text and the groups `(?P<name>[\d]+)` and `(?P<name>[\w-]+)` only, one per path
  segment. A requested route longer than 2048 bytes in UTF-8 matches no route (D16).
- `rest_route` is read from a POST's fields only for a form body (`application/x-www-form-urlencoded`) sent to the
  site's path or `index.php` there; a multipart body is not read for it, nor for parameters. A form POST to the site's
  path without any `rest_route` answers an empty 404 instead of reaching the application's own route there.
- The base is a REST path only right after a site's own path: `/foo/wp-json/...` is left to the application's other
  routes when `/foo/` is no site, a path with `//` before the base (`//wp-json/...`, `/news//wp-json/...`) is no REST
  path (WordPress trims the slashes), and nothing under the non-application root is ever REST.
- Quarkus's path-based HTTP permission policies (`quarkus.http.auth.permission.*.paths`) and OIDC tenant resolution by
  path see the internal path the request is re-routed to, never `/wp-json/...`. Check REST callers in permissions and
  handlers instead.
- At most 1000 distinct hrefs are embedded per answer, counting those that lead nowhere; later ones get `[]` (D18).
  A link whose route starts with `//` after the API root embeds `[]` (PHP would read a host there).
- A negative `offset` is made positive for the query and for the pagination headers alike; WordPress's headers use the
  raw value while its query uses the positive one (D25).
- The `uniqueItems` check after sanitizing applies to the argument's own list, not to lists nested inside it (WordPress
  leaves a nested failure inside the sanitized value).
- URL sanitizing (format `uri`) differs from WordPress's `esc_url_raw` on some malformed URLs with a port-like text in
  a bracketed authority (`https://x[:1.Ad]`): the brackets are encoded where WordPress leaves one.
- Avatar URLs keep a `d` or `r` value of exactly `0` (an `avatar_default` or `avatar_rating` option stored as `0`),
  which WordPress drops.

## Decisions

- One pipeline in WordPress's order serves every route, the application's included, so validation, permissions,
  errors, `Allow`, `_fields`, links, embedding and headers behave the same everywhere.
- WordPress's quirks are kept where clients can see them (the `_fields` rules, `prev` links to page 0, PHP's JSON
  escapes), and WordPress's crashes (a PHP error answering 500) become ordinary answers (D20, D21).
- Bearer tokens only, refused before matching when invalid: a client never receives public data under a token it
  believes valid. An identity from anything but the `Authorization: Bearer` header is not trusted, so WordPress's
  cookie and nonce defence against cross-site requests is not needed.
- Cross-origin access is closed by default and opened per origin; JSONP is never served.
- Embedding dispatches internally on the same site, caller and pipeline, never over HTTP and never to another host.
- Users are managed by the identity provider: the users routes read profiles and memberships, and refuse writes
  after WordPress's own permission checks, so clients see WordPress's refusals first.
- The route matcher, the query parser, `_fields` and every text rule are hand-written loops linear in their input;
  every list argument and embedding run is bounded.

## Differences from WordPress

The rows below are the differences the parity tests know by label. Some further, narrower ones are listed under
[Limits](#limits) only: `uniqueItems` in nested lists, bracketed URL authorities, and avatar `d` or `r` values of `0`.

| Label | Difference |
|---|---|
| D1 | REST hrefs (`_links`, `Location`, pagination, the API-root `Link`) always use the `/wp-json/` form and `/wp-json/` is served whatever the permalink structure; front-end `link` fields use plain permalinks (`?cat=`, `?tag=`, `?author=`) until themes and permalinks arrive. WordPress uses the `?rest_route=` form (`index.php?rest_route=` in hrefs and pagination links) and does not serve `/wp-json/` when the site has plain permalinks: it redirects `/wp-json/...` with a 301 and answers `/wp-json/` with the front page. A request made with `?rest_route=` gets pagination links that keep it in their query: `.../wp-json/wp/v2/tags?rest_route=%2Fwp%2Fv2%2Ftags&page=2`. |
| D2 | Authentication is Bearer tokens only: no cookie and nonce, no application passwords. An invalid or expired token answers 401 `rest_invalid_token` "The access token is invalid or has expired." before matching, on every route, with no `Allow`. WordPress falls back to anonymous for failed application passwords because of its hook order; QuarkPress answers 401 so token clients know to refresh (the admin's Bearer middleware relies on it) and never silently gets public data. |
| D3 | The index's `authentication` advertises `{"oidc":{"issuer":...}}` when OIDC is configured; WordPress shows `[]` or its application-passwords endpoint. |
| D4 | No `Access-Control-Allow-Origin` unless the origin is listed in `quarkpress.rest-wp-v2.cors.origins`; a listed origin also gets `X-HTTP-Method-Override` among allowed headers and `Allow`, `X-WP-Upload-Attachment-ID` among exposed ones. WordPress reflects any origin with credentials. |
| D5 | `_jsonp` is always refused with 400 `rest_callback_disabled` "JSONP support is disabled on this site.", WordPress's answer when JSONP is disabled (`application/json; charset=UTF-8`, with only the content type, `X-Robots-Tag`, the API-root `Link` and `X-Content-Type-Options`). WordPress serves JSONP by default (`application/javascript`) and refuses an invalid callback name with `rest_callback_invalid`. |
| D6 | The index lists only the namespaces and routes QuarkPress serves (no `oembed/1.0`, `wp-site-health/v1`, `wp-block-editor/v1`, `wp-abilities/v1`, `batch/v1`, application passwords, or the `wp/v2` routes of later releases); `?namespace=` naming a namespace QuarkPress does not serve answers 404 `rest_invalid_namespace`. |
| D7 | The index has no `wp:active-theme` link until the synthetic theme route exists, and so no `curies`. |
| D8 | `/wp/v2/types` lists only registered types (no `nav_menu_item`, `wp_template`, `wp_template_part`, `wp_global_styles`, `wp_navigation`, `wp_font_family`, `wp_font_face`); `/wp/v2/taxonomies` has no `nav_menu` or `wp_pattern_category`, so `wp_block`'s `taxonomies` is empty and enums naming types (such as the users' `has_published_posts`) list only registered ones. The unregistered `revision` type answers 404 `rest_type_invalid` where WordPress refuses it with 401 or 403 `rest_cannot_read_type`, and the unregistered `post_format` taxonomy 404 `rest_taxonomy_invalid` where WordPress answers 401 `rest_forbidden`. |
| D9 | User fields come from the identity provider's profile: `username`, `nickname` and `slug` are the nicename, `first_name`, `last_name` and `url` are empty, `locale` is `en_US`. |
| D10 | `POST /users` answers 501 `quarkpress_users_managed_externally` "Users are managed by the identity provider." after the `create_users` check; an update may change only one's own `meta.persisted_preferences`, any other field answers that 501 after WordPress's checks (`promote_user` when `roles` are given, then `edit_user`). |
| D11 | `DELETE /users/{id}` answers 501 `rest_cannot_delete` "The user cannot be deleted." after the `delete_user` check on a single site too (WordPress does so only on multisite). |
| D12 | Unexpected failures answer 500 `internal_server_error` with the short message "There has been a critical error on this website." and are logged; a stale-version conflict answers 409 `quarkpress_conflict` "The item was changed by another request.". |
| D13 | The answers WordPress gives with `wp_die` before its REST server runs (410 for an archived, spam or deleted site; 400 for a `rest_route` that is not a string or that differs between query and form) are its JSON `wp_die` error when the request's `Accept` or `Content-Type` is JSON, else a minimal HTML page with the same sentence (WordPress's page has more markup: its styles, its title and a longer robots line). The 410 comes before authentication, for every caller: WordPress's answer for token and application-password callers; the only difference is that WordPress lets a cookie-authenticated super admin through, and QuarkPress has no cookies. |
| D14 | Messages are English only. |
| D15 | The index never carries `image_sizes`, `image_size_threshold`, `image_strip_meta` or `image_max_bit_depth`: QuarkPress never enables client-side media processing. WordPress 7.1 adds them to an editor's index (not a subscriber's or an anonymous caller's) when the site is served in a secure context such as `localhost`. |
| D16 | A requested route longer than 2048 bytes in UTF-8 matches no route and answers 404 `rest_no_route`; WordPress has no bound and matches it (a 2053-byte `/wp/v2/types/...` route answers its `rest_type_invalid` with `Allow`). |
| D17 | Embedding follows only links into this site's own API (below its API root, or its home with a `rest_route`) and embeds `[]` for any other; WordPress also dispatches another host's `?rest_route=` link, on this site. |
| D18 | At most 1000 distinct hrefs are embedded per answer (counting those that lead nowhere), later ones get `[]`; WordPress has no bound. |
| D19 | Site address and admin e-mail settings (single site; a network's sites have neither): the `url` setting is read-only. In `/wp/v2/settings`, `url` answers the site's own address, the same as the index's `url`. A write to it is accepted and has no effect: the answer shows the address unchanged. QuarkPress takes a site's address from its site record, while WordPress stores the value in its `siteurl` option, which moves the site. `email` reads and writes the `admin_email` option as WordPress does, but a new QuarkPress site has none, so it reads null until one is written. WordPress answers the address given at installation. |
| D20 | Argument values that make WordPress fail with a PHP error (a 500) get the answer of a value that does not fit: an IPv6 address such as `1:2:3:4:5:6:7:8:9::` (format `ip`) answers 400 `rest_invalid_ip`, an array given to a `date-time` format without a type answers 400 `rest_invalid_date`, and an array sanitized with format `uri` or `hex-color` becomes `""`, as it does for the other formats. |
| D21 | Answers that make WordPress fail with a PHP error (a 500) are kept as they are: under `_fields`, a scalar or null body, a list of scalars, and a PHP object as the body or as an item of a list body; a scalar body with links (a null body with links becomes `{"_links":...}`); and `?namespace[]=x` on a namespace's index answers 404 `rest_invalid_namespace`. |
| D22 | List arguments hold at most 1000 entries (the terms' `include`, `exclude` and `slug`; the users' `include`, `exclude`, `slug`, `roles`, `capabilities`, `has_published_posts` and `search_columns`); a longer list answers 400 `rest_invalid_param` as a list over its `maxItems` does ("... must contain at most 1000 items."). WordPress has no bound. |
| D23 | In pagination links, the bytes of a query parameter's name below 0x20, 0x7F and above are percent-encoded, so no name can break the header; WordPress writes them raw. A malformed UTF-8 sequence in a query value is read as U+FFFD and written back as `%EF%BF%BD`; WordPress keeps the raw bytes. |
| D24 | Only a request carrying `Authorization: Bearer ...` has a caller. A request the application authenticated another way (an OIDC `web-app` or `hybrid` session cookie, a custom `HttpAuthenticationMechanism`) is served as anonymous: reads answer as to anyone, writes are refused as to an anonymous caller (401). WordPress serves a cookie-authenticated request as its user when it carries a valid REST nonce, and as anonymous without one. |
| D25 | A negative `offset` is made positive for the pagination headers as for the query, so `offset=-1` counts and links pages as `offset=1` does (a `prev` link included); WordPress's query uses the positive value while its headers use the raw one, so its pages and links do not match the items it lists. |

WordPress behaviour kept on purpose (not differences): `_fields` reduces a keyed collection by its keys
(`/wp/v2/types?_fields=name` is `[]`), and a field below one already requested whole ends the list there
(`_fields=meta,meta.a,id` keeps `meta` whole and drops `id`); `prev` may point to page 0 (a page past the end links
back to the last page, even when it is 0); `GET ...?_method=DELETE` deletes. Only a Bearer token identifies the caller
(D24), and a link or a cross-site form cannot carry one, so the last one is not a cross-site request forgery vector.

The parity tests (`-Pwp-parity`) run the hand-written verification scenarios and generated ones against WordPress
7.1.3 served over HTTP (`testing/wp-rest-parity`, run by `integration-tests/rest-wp-v2`): 154 scenarios, 34
hand-written and 120 generated, 10765 comparisons in the last green run, at least 9650 required, and every scenario
must compare something. Besides status and body they compare `Allow`, the totals, `Link`, `Location`, the content
type, `X-Robots-Tag`, `X-Content-Type-Options`, `Cache-Control`, `Expires`, `Vary` and the CORS headers, and, for the
escapes and key-order scenarios, the body's bytes. Their known-differences file labels every entry with a row of this
table and names the aspects that differ; a test checks that every label it uses is a row here and every row is used.
