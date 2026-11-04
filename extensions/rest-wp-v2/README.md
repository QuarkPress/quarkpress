# QuarkPress REST API (WordPress v2)

WordPress's REST API for QuarkPress sites: the `/wp-json/` index, the `wp/v2` routes for posts, pages and custom post
types (with their revisions and autosaves), search, post types, post statuses, taxonomies, terms, users and settings,
with WordPress 7.1.3's URL forms, parameter rules, validation messages, error objects, headers, pagination, `_fields`,
`_links` and `_embed`, encoded as PHP encodes JSON; QuarkPress's own `quarkpress/v1` routes for what WordPress's block
editor gets outside its REST API (the new post's auto-draft, the editor's post lock, the preview session); and a
minimal front-end page for previews and post links until themes exist. Clients authenticate with Bearer tokens of the
security module's OIDC provider. Applications add routes and settings of their own through the same pipeline. It works
on a single site and in a network, where every site serves its own API under its own path; a per-site switch turns it
off.

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
anonymous. A token that fails authentication (invalid, expired, signed by an unknown issuer), or the `Bearer` scheme
sent without a token, is refused before any route is matched, on every route including public ones: 401
`rest_invalid_token` "The access token is invalid or has expired." with no `Allow` header (D2). There are no
cookies, nonces or application passwords: only a request that carries `Authorization: Bearer ...` has a caller (the
preview page's session cookie is read by that page alone, never by a REST route). A request the application
authenticated any other way (such as the session cookie of an OIDC `web-app` or `hybrid` tenant, or a custom
`HttpAuthenticationMechanism`) is served as anonymous, its permissions and the services it calls included, so a page
of another site cannot make a browser write with the user's rights; and so is one whose such credential failed
authentication (an expired session cookie, say) (D24). The index's `authentication` names the default tenant's
issuer, `{"oidc":{"issuer":"<quarkus.oidc.auth-server-url>"}}`, when that tenant is enabled and configured, else `[]`
(D3).

### The site feature

The per-site feature `rest-wp-v2` (on by default; `FeatureToggles`, the `qp_features` option) serves the API for the
site. Off, every REST URL of the site answers 404 `rest_no_route`, as does a request whose host and path resolve to no
site.

### Routes

| Route | Methods | What it serves |
|---|---|---|
| `/` | GET | The site index: name, description, `url`, `home`, time zone and reading settings, the namespaces, the authentication schemes, every route's data, `site_logo`, `site_icon` and `site_icon_url`, and the `help` link; `?context=help` adds each route's schema. |
| `/<namespace>` | GET | One namespace's index (`/wp/v2`, and every namespace an application registers); `?namespace=` names another one. |
| `/wp/v2/posts`, `/wp/v2/pages`, `/wp/v2/<rest base>` | GET, POST | The posts of each post type shown in REST (attachments and blocks excepted, their routes come later), at its REST base: the list, with WordPress's filters (`status` needs the type's `edit_posts` for anything but `publish`, as the posts module's queries do: `read_private_posts` alone admits nothing, where WordPress admits `private`), its callback errors and its paging, counting only what the caller may read (D27); the rendered fields run the registered filters (D26). The term filters `tax_relation`, `<taxonomy base>` and `<taxonomy base>_exclude` (a term id list, or `{terms, include_children, operator}`) and `format` follow WordPress: unknown ids match nothing (or exclude nothing), `include_children` takes the whole subtree, a filter asking for terms turns sticky-first off; an expansion past 10 000 terms answers 400 `rest_invalid_param` (`term_filter_too_large`, see the taxonomy module). POST creates a post with WordPress's checks in its order (an `id` is `rest_post_exists`, one empty in PHP's sense such as `0` 404 `rest_post_invalid_id` "Invalid post ID." from the preparation; another author needs `edit_others_posts`, `sticky` on any type `edit_others_posts` and `publish_posts`, then `create_posts`, then `assign_term` on each named term) and its preparation (a private, published or scheduled status needs `publish_posts`; a date without offset is the site's wall time, one with an offset an instant; an author must be a user of the network; a password and stickiness exclude each other; a parent may be a post of any type; a draft or pending post given a slug gets it unique as if published; block delimiters are written again as WordPress writes them), then writes the post, its stickiness, featured image, format, template and terms in one transaction (D29); `template` accepts only `""` (D30); the answer is 201 with the post in the edit context and its `Location`. |
| `/wp/v2/posts/<id>`, `/wp/v2/pages/<id>`, `/wp/v2/<rest base>/<id>` | GET, POST, PUT, PATCH, DELETE | GET: one post of the type, looked up on the request's site: a missing id, another type's or another site's post is 404 `rest_post_invalid_id`. The checks run in WordPress's order: the edit context needs `edit_post` (`rest_forbidden_context`), a non-empty `password` in the query string that differs from the stored one is 403 `rest_post_incorrect_password` (also on a post without password), then the post must be readable (`rest_forbidden`). The edit context of a caller who may edit the post, or a `password` from any source that matches, renders the content and excerpt of a post with a password; the protected flags and the class list say the password is there either way. `permalink_template` is the plain link, `generated_slug` the title's slug (the id when the title gives none) made unique as for a published post of the type under the post's parent, `class_list` WordPress's front-end classes; a stored template `default` reads as `""`, any other stored value as stored. For a viewable type the only `Link` header is the permalink's `rel="alternate"` line, on HEAD too; the collections keep the API root's. The answer carries WordPress's links (self, collection, about, author, replies, version-history with the revision count and predecessor-version, up, `wp:attachment`, one `wp:term` per REST taxonomy of the type) and, in the edit context, the caller's action links (publish, unfiltered HTML, sticky, assign author, create and assign terms); no featured media link yet (D28). POST, PUT and PATCH update the post alike: the arguments are checked first (a `status` or `template` equal to the stored one passes whatever the lists say), then WordPress's update checks in its order (the post; `edit_post`, else `rest_cannot_edit`; another author needs `edit_others_posts`; a `sticky` that changes the post's stickiness, on any type, `edit_others_posts` and `publish_posts`; `assign_term` on each named term), then the create's preparation against the stored post (fields not sent stay as stored; a `date` or `date_gmt` sent as null dates the post now, its date floating when the status it gets is draft, pending or auto-draft); the post, its format, featured image, stickiness, template, terms and meta are written in one transaction (D29) and, while the revisions module is present, the revision is saved once at the end, after the meta. Editor locks are not consulted, as WordPress's REST writes do not consult them. The answer is 200 with the post in the edit context. DELETE needs `delete_post` (`rest_cannot_delete` "Sorry, you are not allowed to delete this post."): with `force=true` the post is deleted for good and the answer is `{"deleted": true, "previous": <the post in the edit context>}`; without, the trash must be on with a positive `quarkpress.posts.empty-trash-days` (else 501 `rest_trash_not_supported` "The post does not support trashing. Set 'force=true' to delete.") and the post not in it yet (else 410 `rest_already_trashed`), and the answer is the trashed post in the edit context. |
| `/wp/v2/posts/<parent>/revisions`, `/wp/v2/pages/<parent>/revisions`, `/wp/v2/<rest base>/<parent>/revisions` | GET | The revisions of one post of a type that keeps revisions (served while the revisions module is present): every user's revisions, autosaves included, newest first; `context`, `page`, `per_page` (1 to 100, no default: without it every revision is one page), `search` (WordPress's terms, phrases and `-` exclusions over title, excerpt and content), `include`, `exclude`, `offset`, `order` and `orderby` (`date`, `id`, `include`, `relevance`, `slug`, `include_slugs`, `title`) with WordPress's paging quirks: a given `offset` (even 0) fixes the window and `page` only moves the links, an offset at or past the total or a page past the last is refused while the post has revisions, a post without revisions, or whose revisions are off, answers `[]` with zero totals. The parent is looked up on the request's site under the route's type (`rest_post_invalid_parent`); reading needs `edit_post` on it (`rest_cannot_read`). The totals agree with the post's `version-history` count (D31). |
| `/wp/v2/posts/<parent>/revisions/<id>`, `/wp/v2/pages/<parent>/revisions/<id>`, `/wp/v2/<rest base>/<parent>/revisions/<id>` | GET, DELETE | One revision: the parent and permission checks of the list, then the revision (`rest_post_invalid_id`, also for another site's), which must belong to the parent (`rest_revision_parent_id_mismatch`); fields `author`, `date`, `date_gmt`, `id`, `modified`, `modified_gmt`, `parent`, `slug`, `guid`, `title`, `content`, `excerpt` (when the type has them), `meta` and a `parent` link (D31). DELETE runs WordPress's checks (`delete_post` on the parent, the revision, `edit_post`) and is then refused for everyone with `rest_cannot_delete`, as WordPress refuses it, so `Allow` never lists it. |
| `/wp/v2/posts/<id>/autosaves`, `/wp/v2/pages/<id>/autosaves`, `/wp/v2/<rest base>/<id>/autosaves` | GET, POST | The autosaves of one post of a type that is autosaved (served while the revisions module is present). GET: every user's autosaves, newest first, unpaged and without totals; the post is looked up as the revisions list looks up its parent (`rest_post_invalid_parent`) and reading needs `edit_post` (`rest_cannot_read`). POST: the arguments are validated first, an id `0` is `rest_post_invalid_id` "Invalid item ID.", then WordPress's update checks (the post, `edit_post`, another author, a changed stickiness, the named terms) and the posts route's preparation, whose error is the answer (D32). A draft or auto-draft of the caller's own that no other user holds a fresh lock on is updated in place with every prepared field but meta, terms, featured image, stickiness, format and template (an auto-draft given `status: draft` becomes a draft), and answered in the revision shape with the post's id, its parent, null dates while its date floats and its own preview link; any other post gets the caller's autosave (created, rewritten when a field or a revisioned meta key differs from the post, else returned untouched) and nothing of the request is applied to the post. The request's revisioned meta values (`footnotes`) pass their key's sanitizer for the caller before they are stored, as a post's meta does. A `meta` object that names no revisioned key is not compared at all, where WordPress compares every revisioned key (`""` for one not sent) whenever `meta` is not empty; the editor always sends `footnotes`. The answer is 200 in the edit context whatever context was asked for. |
| `/wp/v2/posts/<parent>/autosaves/<id>`, `/wp/v2/pages/<parent>/autosaves/<id>`, `/wp/v2/<rest base>/<parent>/autosaves/<id>` | GET | The latest autosave of the post of any user, whatever the id (`rest_post_no_autosave` when it has none), after the revisions item's parent and permission checks. In the edit context every autosaves answer carries `preview_link`: the post's plain link with `preview_id`, the caller's preview token as `preview_nonce` (the same for a whole tick of half the preview lifetime) and `preview=true` last; a draft updated in place has its link with `preview=true` alone; a type that is not viewable has `""`. |
| `/wp/v2/types`, `/wp/v2/types/<type>` | GET | The post types registered to show in REST; the edit context needs the type's `edit_posts`. |
| `/wp/v2/statuses`, `/wp/v2/statuses/<status>` | GET | The post statuses that are not internal, then `trash`; a status that is not public is shown only to callers who may edit posts of some REST type. |
| `/wp/v2/taxonomies`, `/wp/v2/taxonomies/<taxonomy>` | GET | The taxonomies registered to show in REST (`?type=` names a post type); the edit context needs the taxonomy's `assign_terms`. |
| `/wp/v2/categories`, `/wp/v2/tags`, `/wp/v2/<rest base>` | GET, POST | The terms of each taxonomy shown in REST, at its REST base: list (readable by anyone) and create. |
| `/wp/v2/<rest base>/<id>` | GET, POST, PUT, PATCH, DELETE | One term: read, update, delete (`force=true` is required, terms have no trash). |
| `/wp/v2/users`, `/wp/v2/users/<id>`, `/wp/v2/users/me` | GET, POST, PUT, PATCH, DELETE | The site's users with WordPress's visibility rules; the only write applied is a user's own `meta.persisted_preferences` (the editor's preferences, kept per site); creating, deleting and other changes answer 501 after WordPress's permission check (D10, D11). |
| `/wp/v2/settings` | GET, POST, PUT, PATCH | The registered settings, for callers with `manage_options` on the site. |
| `/wp/v2/search` | GET | WordPress's search, public: `type=post` (the default) lists the published posts, pages and books (every public post type shown in REST but attachments) for every caller alike, by relevance then date for a `search` and newest first without one, protected posts hidden from anonymous searches; `type=term` the categories and tags (every public taxonomy shown in REST), empty ones included, by name, each item's `type` being its taxonomy; `type=post-format` the formats whose term exists on the site, with string ids. `subtype` narrows a handler to its own subtypes (`any` means all; a list naming none of them falls back to posts alone or to every taxonomy); `include` wins over `exclude`; the post-format handler ignores both and `subtype`. Items carry the plain front-end link (D1) and `self`, `about` and `collection` links (`collection` alone for a format). A page past the end of the posts answers an empty page with zero totals and a `prev` link; past the end of the terms or formats it is 400 `rest_search_invalid_page_number`. |
| `/quarkpress/v1/drafts` | POST | QuarkPress-only (D36): the auto-draft a new post starts from, as WordPress's new-post screen creates it: body `{type}` (a post type shown in REST; attachments and blocks are refused with `rest_type_invalid`), the caller needs the type's `edit_posts` and `create_posts` (else `rest_cannot_create`); the row has status `auto-draft`, the title "Auto Draft" when the type has one, the caller as author, a floating date, no slug, the type's comment defaults, no term, and the site's `default_post_format` when the type supports formats; answers 201 with the post in the edit context and a `Location` header; no lock is taken (the shell takes one through the lock routes next). An auto-draft that is never saved is removed by the scheduling module's housekeeping seven days after its last change; without that module it stays. |
| `/quarkpress/v1/locks/<id>` | POST, PUT, DELETE | QuarkPress-only (D34, D36): the editor's post lock (the posts module's `PostLocks`), answering what WordPress's edit screen, heartbeat and admin-ajax give the editor. Every method needs `edit_post` on a post of the site whose type is shown in REST (`rest_cannot_edit` "Sorry, you are not allowed to edit this post."); a missing post is 404 `rest_post_invalid_id`. POST takes the lock as the edit screen does: `{"isLocked": false, "activePostLock": "<time>:<user>", "release_token": ...}`, or, when another user holds a fresh lock, `{"isLocked": true, "user": {"name", "avatar"}}` with nothing taken (a 128-pixel avatar, only while the site shows avatars). PUT is the heartbeat's refresh: `{"new_lock", "release_token"}`, or, once another user has taken over, `{"lock_error": {"name", "text", "avatar_src", "avatar_src_2x"}}` ("<name> has taken over and is currently editing.", avatars of 64 and 128 pixels). DELETE with `active_post_lock` releases the caller's own lock by compare-and-set and answers `{"released": true}`, or `false` when the stored value no longer matches. A browser's `sendBeacon` on leaving the page can send neither `DELETE` nor an `Authorization` header, so the shell posts a JSON body to `...?_method=DELETE` with `active_post_lock` and `release_token`: without credentials, a valid token of that post, the lock value's user and the value itself (consumed either way) releases as that user would, and anything else answers 401 `rest_cannot_edit`; a caller with a Bearer token releases with its own rights. |
| `/quarkpress/v1/locks/<id>/takeover` | POST | QuarkPress-only (D34, D36): takes the lock for the caller whatever is stored, as WordPress's "Take over" does, with the checks of the lock route, and answers as a take that succeeded. |
| `/quarkpress/v1/preview/session` | POST, DELETE | QuarkPress-only (D33, D36): the session that names the viewer of the preview page. POST opens one for a logged-in member of the site or a super admin (anyone else: `rest_forbidden` "Sorry, you are not allowed to do that."), answering `{"expires_in": <seconds>}` with `Set-Cookie: quarkpress_preview=<token>; Max-Age=<seconds>; Path=<the site's path>; HttpOnly; SameSite=Lax` (and `Secure` over HTTPS), the lifetime being `quarkpress.posts.preview-lifetime`. DELETE closes every session of the caller on the site and clears the cookie (`Max-Age=0`), answering `{"closed": true}`. No REST route reads the cookie. |

The post, term and user collections take WordPress's `context`, `page`, `per_page` (1 to 100, default 10), `search` and their
own filters, and answer `X-WP-Total`, `X-WP-TotalPages` and a `Link` header with the previous and next pages. Every
answer of a matched route carries an `Allow` header listing the methods the caller may use there. The global parameters
`_fields`, `_embed`, `_envelope`, `_pretty`, `_method` and the `X-HTTP-Method-Override` header work on every route as in
WordPress; `_jsonp` is always refused (D5).

The `meta` field of a post write (create and update, after the post's other steps and before the update's revision,
which so holds the new values) must be an object or a list whose registered keys' values nest at most 64 arrays or
objects (the deepest value stored), else 400 `rest_invalid_param`; null means no meta. It
writes the registered keys shown in REST that it names, in registration order, and ignores other names. Per key: null
(or an empty list for a key of several values) deletes it, refused with 500 `rest_invalid_stored_value` when a single
key's stored value does not fit its schema, as is a list holding null; any other value must fit the key's schema (its
error with status 400, the `meta.<name>` param in `additional_data`), is coerced to it, passes the key's sanitizer for
the caller (`footnotes` is cleaned for callers without `unfiltered_html`) and is stored, a list key's values replacing
the stored ones. A write needs `edit_post` and the key's permission (a protected key, starting with `_`, needs an auth
that allows it), else `rest_cannot_update` or `rest_cannot_delete` "Sorry, you are not allowed to edit the <name>
custom field." with the key and the 401/403 status; a single value equal to the stored one needs no permission. Every
key is tried and the errors answered together, the first one's code and status first and the others in
`additional_errors`; any error rolls the whole write back (D29). A key whose default does not fit its schema fails the
application's start.

The routes of media, blocks, block patterns, block types, themes, templates, global styles, navigation, menus,
widgets, comments, plugins, site health, oEmbed, application passwords and `/batch/v1` are not served yet: they answer
404 `rest_no_route`, and the index does not list them (D6). Later releases add them, starting with media, then the
block editor's own routes.

### The preview page

While `quarkpress.rest-wp-v2.fallback-front-end` is on, the module answers the front-end requests that name a post or
ask for a preview with a minimal HTML page until themes exist (D35): a `GET` or `HEAD` of the site's root (its path, or
`index.php` there) whose query has `p`, `page_id`, `attachment_id`, `post_type` with `p`, or `preview`, and no
`rest_route`. It is served through the same entry mechanism as the API (see [How a request is
served](#how-a-request-is-served)): a filter decides and re-routes, and an internal resource renders inside the normal
request under the viewer's identity. The viewer is the user of the `quarkpress_preview` cookie that
`POST /quarkpress/v1/preview/session` sets, and is anonymous without a valid session, whatever else the request
carries. In order:

- `preview_id` with a `preview_nonce` that is not the viewer's valid preview token of that post: 403 "Sorry, you are
  not allowed to preview drafts." (an HTML page, or WordPress's JSON `wp_die` object when `Accept` names
  `application/json`);
- a post that does not exist, or is not of the type the parameter names: 404;
- a viewer who may not see the post: 404 (a published post shows to everyone; a draft, pending or scheduled post to a
  viewer with `edit_post`; a private one with `read_post`; an auto-draft, a revision or a trashed post to nobody);
- a published post asked for with `preview` and no valid pair: 301 to its plain link, as WordPress's canonical
  redirect does;
- otherwise the page: the site's name, the post's title, its date and its content through the registered filters. A
  shown post that is not public is in preview mode, and a draft or pending post is dated now; in preview mode, for a
  viewer with `edit_post` and a valid pair, the latest autosave of any user replaces the title, content and excerpt,
  its `post_format` the format and its `_thumbnail_id` the featured image. A password-protected post shows its title
  and WordPress's notice "This content is password protected. To view it please enter your password below:" in place
  of its content, without the form, unless the viewer is in preview mode with `edit_post`. Previews are sent with
  no-cache headers.

The links to it come from the autosaves routes' `preview_link` (`preview_id`, the caller's preview token as
`preview_nonce`, `preview=true`); the token is the posts module's `PreviewTokens`.

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
  argument 400 `rest_missing_callback_param`. The schema keywords `pattern`, `patternProperties` and `anyOf` are
  refused when the route is registered (see Limits); `oneOf` is checked as WordPress checks it, and each of its schemas
  needs a `title`.
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
| `quarkpress.rest-wp-v2.fallback-front-end` | `true` | Whether site-root requests naming a post (`?p=`, `?page_id=`, `?attachment_id=`, `?post_type=…&p=`) or asking for a preview (`?preview=`) get the minimal front-end page (the site's name, the post's title, date and content) until themes exist. Off, no front-end request is answered by this module (D35). |

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
   The minimal front-end page is served through the same mechanism: a second filter at the same priority decides
   whether a `GET` or `HEAD` of the site's root (its path, or `index.php` there) names a post or asks for a preview
   without any `rest_route`, and re-routes it to the internal `PreviewPage` resource (`/_quarkpress/front-end/page`,
   computed at build time like the REST entry path); that resource renders inside the normal request, after
   authentication, with the request scope and the site binding active. The filter reads no cookie and calls no
   service; the resource alone reads the `quarkpress_preview` session cookie (set by
   `POST /quarkpress/v1/preview/session`, cleared by `DELETE`) and swaps the request's identity for the session's
   user while it renders, as the REST server does for its Bearer identities; no REST route ever reads that cookie.
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
- A custom type's `link` for a private or trashed post uses `?<type>=<slug>`, by analogy with its published posts;
  WordPress's recorded answers cover published and draft posts only.
- Messages are English only (D14).
- The schema keywords `pattern`, `patternProperties` and `anyOf` are refused when a route or a setting is
  registered: `pattern` would need a regular expression run on request input, and the others are not implemented. A
  `oneOf` whose schemas lack a `title` is refused too (WordPress's messages name the schemas by title).
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
- At most 1000 distinct hrefs are embedded per answer, counting those that lead nowhere, and at most 10 000 embedded
  items per answer (a list counts its items, anything else one); later ones get `[]` (D18).
  A link whose route starts with `//` after the API root embeds `[]` (PHP would read a host there).
- A negative `offset` is made positive for the query and for the pagination headers alike; WordPress's headers use the
  raw value while its query uses the positive one (D25).
- The `uniqueItems` check after sanitizing applies to the argument's own list, not to lists nested inside it (WordPress
  leaves a nested failure inside the sanitized value).
- URL sanitizing (format `uri`) differs from WordPress's `esc_url_raw` on some malformed URLs with a port-like text in
  a bracketed authority (`https://x[:1.Ad]`): the brackets are encoded where WordPress leaves one.
- Avatar URLs keep a `d` or `r` value of exactly `0` (an `avatar_default` or `avatar_rating` option stored as `0`),
  which WordPress drops.
- A post write's errors from the posts module itself, those the REST preparation does not answer first, reach the
  client with the module's code and status 400: `empty_content` "Content, title, and excerpt are empty." (WordPress's
  answer), `invalid_date` "Invalid date." (a year outside 1000 to 9999, which the posts module cannot store, its D6),
  and, only when a concurrent request deletes the post, the author or the parent between the checks and the write,
  `invalid_post` "Invalid post ID.", `invalid_author` "Invalid author ID: <id>" and `invalid_parent` "Invalid post
  parent ID: <id>" (messages that are not WordPress's). `db_insert_error` and `db_update_error` (a value too long for
  its column) answer 500. A stale version answers 409 (D12).
- `featured_media` accepts any attachment of the site, as an image, until the media module knows attachment types
  (the posts module's D35).
- `POST /quarkpress/v1/drafts` refuses `attachment` and `wp_block` with `rest_type_invalid` until the media and block
  routes exist.
- A `meta` value for a key of several values given as an object with integer keys (`{"book_tags":{"0":null}}`) gets
  its schema's error rather than `rest_invalid_stored_value`; WordPress's answer to it was not recorded.
- The `key` in the data of a meta permission refusal (`rest_cannot_update`, `rest_cannot_delete`) is the key's REST
  name; no registration gives a key a REST name of its own yet, and which name WordPress puts there was not recorded.

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
- Arguments are checked before permissions, in WordPress's order, also where that tells a caller something: an
  anonymous `PUT {"status":"trash"}` answers 401 for a post in the trash (the stored status passes the check) and 400
  `rest_invalid_param` for any other post, so the answer shows whether a post the caller cannot read is trashed, as
  WordPress's does.
- What WordPress's block editor gets outside the REST API (the new-post screen's auto-draft, the post lock of the edit
  screen, heartbeat and admin-ajax, the login cookie of a preview) is served by QuarkPress-only `quarkpress/v1` routes
  under Bearer tokens (D36), so the admin shell needs no cookie and no nonce. Only the preview page, which a browser
  opens by navigation, knows its viewer by a cookie, and no REST route reads that cookie.

## Differences from WordPress

The rows below are the differences the parity tests know by label. Some further, narrower ones are listed under
[Limits](#limits) only: `uniqueItems` in nested lists, bracketed URL authorities, and avatar `d` or `r` values of `0`.

| Label | Difference |
|---|---|
| D1 | REST hrefs (`_links`, `Location`, pagination, the API-root `Link`) always use the `/wp-json/` form and `/wp-json/` is served whatever the permalink structure; front-end `link` fields use plain permalinks (`?cat=`, `?tag=`, `?author=`) until themes and permalinks arrive. WordPress uses the `?rest_route=` form (`index.php?rest_route=` in hrefs and pagination links) and does not serve `/wp-json/` when the site has plain permalinks: it redirects `/wp-json/...` with a 301 and answers `/wp-json/` with the front page. A request made with `?rest_route=` gets pagination links that keep it in their query: `.../wp-json/wp/v2/tags?rest_route=%2Fwp%2Fv2%2Ftags&page=2`. |
| D2 | Authentication is Bearer tokens only: no cookie and nonce, no application passwords. An invalid or expired token, or the `Bearer` scheme without a token, answers 401 `rest_invalid_token` "The access token is invalid or has expired." before matching, on every route, with no `Allow`. WordPress falls back to anonymous for failed application passwords because of its hook order; QuarkPress answers 401 so token clients know to refresh (the admin's Bearer middleware relies on it) and never silently gets public data. |
| D3 | The index's `authentication` advertises `{"oidc":{"issuer":...}}` when OIDC is configured; WordPress shows `[]` or its application-passwords endpoint. |
| D4 | No `Access-Control-Allow-Origin` unless the origin is listed in `quarkpress.rest-wp-v2.cors.origins`; a listed origin also gets `X-HTTP-Method-Override` among allowed headers and `Allow`, `X-WP-Upload-Attachment-ID` among exposed ones. WordPress reflects any origin with credentials. |
| D5 | `_jsonp` is always refused with 400 `rest_callback_disabled` "JSONP support is disabled on this site.", WordPress's answer when JSONP is disabled (`application/json; charset=UTF-8`, with only the content type, `X-Robots-Tag`, the API-root `Link` and `X-Content-Type-Options`). WordPress serves JSONP by default (`application/javascript`) and refuses an invalid callback name with `rest_callback_invalid`. |
| D6 | The index lists only the namespaces and routes QuarkPress serves (no `oembed/1.0`, `wp-site-health/v1`, `wp-block-editor/v1`, `wp-abilities/v1`, `batch/v1`, application passwords, or the `wp/v2` routes of later releases); `?namespace=` naming a namespace QuarkPress does not serve answers 404 `rest_invalid_namespace`. |
| D7 | The index has no `wp:active-theme` link until the synthetic theme route exists, and so no `curies`. |
| D8 | `/wp/v2/types` lists only registered types (no `nav_menu_item`, `wp_template`, `wp_template_part`, `wp_global_styles`, `wp_navigation`, `wp_font_family`, `wp_font_face`); `/wp/v2/taxonomies` has no `nav_menu` or `wp_pattern_category`, so `wp_block`'s `taxonomies` is empty and enums naming types (such as the users' `has_published_posts`) list only registered ones. The unregistered `revision` type answers 404 `rest_type_invalid` where WordPress refuses it with 401 or 403 `rest_cannot_read_type`. |
| D9 | User fields come from the identity provider's profile: `username`, `nickname` and `slug` are the nicename, `first_name`, `last_name` and `url` are empty, `locale` is `en_US`. |
| D10 | `POST /users` answers 501 `quarkpress_users_managed_externally` "Users are managed by the identity provider." after the `create_users` check; an update may change only one's own `meta.persisted_preferences`, any other field answers that 501 after WordPress's checks (`promote_user` when `roles` are given, then `edit_user`). |
| D11 | `DELETE /users/{id}` answers 501 `rest_cannot_delete` "The user cannot be deleted." after the `delete_user` check on a single site too (WordPress does so only on multisite). |
| D12 | Unexpected failures answer 500 `internal_server_error` with the short message "There has been a critical error on this website." and are logged; a stale-version conflict answers 409 `quarkpress_conflict` "The item was changed by another request.". |
| D13 | The answers WordPress gives with `wp_die` before its REST server runs (410 for an archived, spam or deleted site; 400 for a `rest_route` that is not a string or that differs between query and form) are its JSON `wp_die` error when the request's `Accept` or `Content-Type` is JSON, else a minimal HTML page with the same sentence (WordPress's page has more markup: its styles, its title and a longer robots line). The 410 comes before authentication, for every caller: WordPress's answer for token and application-password callers; the only difference is that WordPress lets a cookie-authenticated super admin through, and QuarkPress has no cookies. |
| D14 | Messages are English only. |
| D15 | The index never carries `image_sizes`, `image_size_threshold`, `image_strip_meta` or `image_max_bit_depth`: QuarkPress never enables client-side media processing. WordPress 7.1 adds them to an editor's index (not a subscriber's or an anonymous caller's) when the site is served in a secure context such as `localhost`. |
| D16 | A requested route longer than 2048 bytes in UTF-8 matches no route and answers 404 `rest_no_route`; WordPress has no bound and matches it (a 2053-byte `/wp/v2/types/...` route answers its `rest_type_invalid` with `Allow`). |
| D17 | Embedding follows only links into this site's own API (below its API root, or its home with a `rest_route`) and embeds `[]` for any other; WordPress also dispatches another host's `?rest_route=` link, on this site. |
| D18 | At most 1000 distinct hrefs are embedded per answer (counting those that lead nowhere) and at most 10 000 embedded items per answer, later ones get `[]`; WordPress has no bound. |
| D19 | Site address and admin e-mail settings (single site; a network's sites have neither): the `url` setting is read-only. In `/wp/v2/settings`, `url` answers the site's own address, the same as the index's `url`. A write to it is accepted and has no effect: the answer shows the address unchanged. QuarkPress takes a site's address from its site record, while WordPress stores the value in its `siteurl` option, which moves the site. `email` reads and writes the `admin_email` option as WordPress does, but a new QuarkPress site has none, so it reads null until one is written. WordPress answers the address given at installation. |
| D20 | Argument values that make WordPress fail with a PHP error (a 500) get the answer of a value that does not fit: an IPv6 address such as `1:2:3:4:5:6:7:8:9::` (format `ip`) answers 400 `rest_invalid_ip`, an array given to a `date-time` format without a type answers 400 `rest_invalid_date`, and an array sanitized with format `uri` or `hex-color` becomes `""`, as it does for the other formats. |
| D21 | Answers that make WordPress fail with a PHP error (a 500) are kept as they are: under `_fields`, a scalar or null body, a list of scalars, and a PHP object as the body or as an item of a list body; a scalar body with links (a null body with links becomes `{"_links":...}`); and `?namespace[]=x` on a namespace's index answers 404 `rest_invalid_namespace`. |
| D22 | List arguments hold at most 1000 entries (the terms' `include`, `exclude` and `slug`; the users' `include`, `exclude`, `slug`, `roles`, `capabilities`, `has_published_posts` and `search_columns`; the posts' `include`, `exclude`, `slug`, `status`, `author`, `author_exclude`, `parent` and `parent_exclude`, and the term id lists of their term filters; the term id lists a post is created with, counted once per distinct id); a longer list answers 400 `rest_invalid_param` as a list over its `maxItems` does ("... must contain at most 1000 items."). WordPress has no bound. |
| D23 | In pagination links, the bytes of a query parameter's name below 0x20, 0x7F and above are percent-encoded, so no name can break the header; WordPress writes them raw. A malformed UTF-8 sequence in a query value is read as U+FFFD and written back as `%EF%BF%BD`; WordPress keeps the raw bytes. |
| D24 | Only a request carrying `Authorization: Bearer ...` has a caller. A request the application authenticated another way (an OIDC `web-app` or `hybrid` session cookie, a custom `HttpAuthenticationMechanism`) is served as anonymous: reads answer as to anyone, writes are refused as to an anonymous caller (401); so is one whose non-Bearer credential failed authentication (an expired session cookie), while a failed Bearer token is refused as D2 says. WordPress serves a cookie-authenticated request as its user when it carries a valid REST nonce, and as anonymous without one. |
| D25 | A negative `offset` is made positive for the pagination headers as for the query, so `offset=-1` counts and links pages as `offset=1` does (a `prev` link included); WordPress's query uses the positive value while its headers use the raw one, so its pages and links do not match the items it lists. |
| D26 | `title.rendered`, `content.rendered` and `excerpt.rendered` of posts run only the registered filters (none is built in): no paragraphs, texturizing, shortcodes or dynamic blocks; an empty excerpt is the content's text cut to `excerpt_length` words (55 by default) followed by ` [&hellip;]`. The raw fields match WordPress's. |
| D27 | `X-WP-Total` and `X-WP-TotalPages` of post collections count only the posts the caller may read; WordPress counts the rows its query found and then drops those the caller may not read, so its totals can exceed the items it lists. |
| D28 | No `wp:featuredmedia` link and no media embeds until the media routes exist; `replies` and `wp:attachment` are linked as WordPress links them, to routes not served yet, so an embed of `replies` embeds the `rest_no_route` error where WordPress embeds the post's comments. |
| D29 | A REST write is one transaction: when a term or meta step fails, the error is WordPress's but the post, its sticky state, featured image, format, template, terms and the meta keys written before the failing one are left as they were, and a create leaves no post (WordPress keeps them all, and the post it created without answering its id; a failed update saves no revision on either side). |
| D30 | `template` accepts only `""` or the stored value until themes exist (the allowed list is empty: "template is not one of ."). REST ignores `preview_id`/`preview_nonce` (WordPress checks them on every request). |
| D31 | Revisions have their own ids (a revision id may equal a post id; clients only use them under their parent); their dates are never null and all four (`date`, `date_gmt`, `modified`, `modified_gmt`) come from the revision's one time, where WordPress keeps separate columns and answers null for a zero GMT date; `guid` is `<home>?p=<revision id>` built from that id; the system identity's revisions have author 0. |
| D32 | An autosave request whose fields fail preparation (unknown author, a sticky password conflict, a bad status) answers that error; WordPress saves without those fields. |
| D33 | Draft previews authenticate with the `quarkpress_preview` session cookie set by `POST /quarkpress/v1/preview/session` (12 h by default, `HttpOnly`, `SameSite=Lax`, scoped to the site's path), and `preview_nonce` is a 27-character HMAC token computed from a per-site secret (`quarkpress_preview_secret`, an option REST never shows), the post, the user and a tick of half the preview lifetime: valid 12 to 24 hours like WordPress's nonce, but bound to the preview session's user rather than the login session, never stored, and revoked for everyone by deleting the secret. WordPress uses its login cookie and a 10-character nonce bound to the login session. An admin shell on another site cannot set the cookie. |
| D34 | Locks are served by `/quarkpress/v1/locks/{id}` (take, refresh, release) and `/quarkpress/v1/locks/{id}/takeover`; WordPress serves them through its edit screen, heartbeat and admin-ajax. The release token is one-time and bound to the lock value, where WordPress's unlock nonce is reusable for 12 to 24 hours. A release on a missing post answers 404 `rest_post_invalid_id` where WordPress's `wp-remove-post-lock` answers `0`, and a release whose value no longer matches the stored lock answers `{"released": false}` where WordPress answers `1`. |
| D35 | Without a theme, front-end requests `?p=`, `?page_id=`, `?attachment_id=`, `?post_type=…&p=`, `?preview=` get a minimal page (site name, title, date, content) instead of the theme's template; a password-protected post shows WordPress's "This content is password protected…" notice without the password form (no cookie lifts it; an editor previewing sees the content); `?<type>=<slug>` links of published custom types are not served, so the 301 a custom-type post gets for `?post_type=<type>&p=<id>&preview=true` (WordPress's `Location`) lands on a 404 until themes and permalinks arrive. |
| D36 | QuarkPress-only namespace `quarkpress/v1`: WordPress has no such routes. The editor's lock routes `/quarkpress/v1/locks/{id}` (take, refresh, release) and `/quarkpress/v1/locks/{id}/takeover` are served there, so the index and the namespace list carry a namespace and routes WordPress lacks. `/quarkpress/v1/drafts` (the auto-draft a new post starts from, WordPress's `post-new.php` row) and `/quarkpress/v1/preview/session` (the preview page's session) are served there too. |
| D37 | The `modified_gmt` of a draft whose date floats and that was not updated since it was created is its stored modification instant. WordPress, whose stored GMT modification date is zero then, answers `modified` minus the site's current numeric UTC offset, so the two differ when the offset at the draft's date is not today's (a daylight-saving switch in between: WordPress, recorded in winter, answers `modified_gmt` 11:00 for a floating draft dated 2026-07-15 12:00 in Europe/Paris, whose instant is 10:00 UTC). |

WordPress behaviour kept on purpose (not differences): `_fields` reduces a keyed collection by its keys
(`/wp/v2/types?_fields=name` is `[]`), and a field below one already requested whole ends the list there
(`_fields=meta,meta.a,id` keeps `meta` whole and drops `id`); `prev` may point to page 0 (a page past the end links
back to the last page, even when it is 0); `GET ...?_method=DELETE` deletes. Only a Bearer token identifies the caller
(D24), and a link or a cross-site form cannot carry one, so the last one is not a cross-site request forgery vector.

The parity tests (`-Pwp-parity`) run the hand-written verification scenarios and generated ones against WordPress
7.1.3 served over HTTP (`testing/wp-rest-parity`, run by `integration-tests/rest-wp-v2`, on PostgreSQL and on MySQL):
169 scenarios, 49 hand-written and 120 generated, 18578 comparisons in the last green run, at least 9650 required, and
every scenario must compare something. Besides status and body they compare `Allow`, the totals, `Link`, `Location`,
the content type, `X-Robots-Tag`, `X-Content-Type-Options`, `Cache-Control`, `Expires`, `Vary` and the CORS headers,
and, for the escapes and key-order scenarios, the body's bytes. Their known-differences file labels every entry with a
row of this table and names the aspects that differ; a test checks that every label it uses is a row here and every
row is used.
