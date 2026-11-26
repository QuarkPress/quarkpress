# QuarkWord Front

The public pages of QuarkWord sites as WordPress serves them: the site's URLs and permalinks under its permalink
structure, the parsing of a page request through WordPress's rewrite rules and public query variables, the main query
with its conditional flags, the 404 decision, the redirects WordPress makes (alias domains, old slugs, canonical URLs),
WordPress's headers, previews of drafts, and password-protected posts with WordPress's own cookie. The page itself is
rendered by a `FrontRenderer` bean, a theme's job; until an application or a theme module deploys one, a minimal
fallback page renders it. It works on a single site and in a network, where every site serves its pages under its own
path, and a per-site switch turns it off.

## Installation

```xml
<dependency>
  <groupId>org.quarkword</groupId>
  <artifactId>quarkword-front</artifactId>
</dependency>
```

The extension brings `quarkword-core`, `quarkword-persistence`, `quarkword-security`, `quarkword-posts`,
`quarkword-taxonomy`, `quarkword-lib-wp-password` (the portable phpass hash of the post-password cookie) and Quarkus
REST. It uses, when the application has them, the multisite module (a network's sites), the media module (an
attachment's file, see D12) and the revisions module (the autosave a preview shows). The REST module
(`quarkword-rest-wp-v2`) opens the preview sessions that name a preview's viewer and, with this module installed, writes
its front-end links (`link`, `permalink_template`, `preview_link`, ...) in the site's permalink structure.

## Using it

### URLs

Inject `org.quarkword.front.FrontUrls` (request scoped). With `home` `http://wp.test` and `siteurl`
`http://wp.test/wp`: `home()` is `http://wp.test`, `home("/")` `http://wp.test/`, `siteUrl()` `http://wp.test/wp`,
`admin("")` `http://wp.test/wp/wp-admin/`, `login()` `http://wp.test/wp/wp-login.php`, `rest("/wp/v2/posts")`
`http://wp.test/wp-json/wp/v2/posts` (with an empty permalink structure `http://wp.test/index.php?rest_route=/wp/v2/posts`).
Over an https request every URL is https; over http, `home()` keeps the stored scheme of `home`, `siteUrl()` is http,
and `admin()` and `login()` are https when `forceSslAdmin()` holds (`siteurl` stored with https, or the setting below).
A site without the `home` or `siteurl` option uses its record's `http` address. The REST prefix is the REST module's
`quarkword.rest-wp-v2.path` when that module is installed, else `/wp-json`.

### Permalinks

Inject `org.quarkword.front.Permalinks` (request scoped) for WordPress's link functions in the site's permalink
structure (`permalink_structure`, `category_base`, `tag_base`, the registered post types' and taxonomies' rewrite
slugs): `post(post)` (`get_permalink`: `http://wp.test/2026/11/20/hello-world/` under the default
`/%year%/%monthnum%/%day%/%postname%/`, a page's path, an attachment below its parent's link, a custom type's post
below its rewrite slug, the plain `?p=` form when its status asks for one), `postSample(post)` (the editor's template
with `%postname%` or `%pagename%` left literal), `term(taxonomy, term)` (`/category/news/europe/`, `/tag/java/`),
`author`, `year`, `month`, `day`, `search` (`/search/java+21/`), `postTypeArchive`, `postFormat` (`/type/aside/`) and
`paged(requestPathAndQuery, n)` (`get_pagenum_link`). With an empty structure every link is the plain query-string
form, and a `/index.php/...` structure gives `index.php/`-prefixed links, as in WordPress. The module also replaces the
posts module's `LinkBuilder` with one built on these, so the REST API's links and the guid of a new post follow the
structure.

### Resolving a request

`org.quarkword.front.FrontResolver` (request scoped) resolves a request of the current site as WordPress's front
controller does: `resolve(rawPath, query, viewer)` runs the request parser (the site's rewrite rules, built from its
permalink settings and the registered post types and taxonomies, and the public query variables of the query string),
the conditional flags, the main query as `WP_Query` builds it (types, dates, the `w` week under `start_of_week`, slug
and id, authors, order with search relevance, search, term filters, paging, sticky posts first on a home list) as the
`Viewer`, the visibility gate of a singular request (drafts, pending and scheduled posts as previews for a viewer with
`edit_post`, private posts with `read_post`, an attachment by its parent's status), the preview pair and autosave
overlay, the queried object and the 404 decision (`handle_404`), and answers a `FrontRequest`: the query variables,
the flags (`FrontFlags`), the queried object (`QueriedObject`: a post, a term, an author, a post type archive or the
posts page), the posts, the shown post and its date, and the status (200, 404, 403 for a bad preview pair, or the
status a plain-permalink `error` asks for). A request the query parser itself finds not to exist (`?p=-1`,
`?monthnum=13`) is answered 200 with the not-found flags, as WordPress sends its 404 template with a 200 then. The main
query goes through the posts module's queries, so its `PostQueryFilter`s (`pre_get_posts`) apply to it.

### Redirects and headers

A resolved request is then checked for WordPress's redirects, in its order: `FrontSiteGate.decide` first (an alias
domain set to redirect sends a read to the site's address with 301; an archived, spam or deleted site answers 410 with
WordPress's message), then `OldSlugRedirects.decide` (a not-found post requested by a slug or day it once had: 301 to
it), then `CanonicalRedirects.decide(request, RequestUrl, viewer)` (`redirect_canonical`: plain query-string forms to
permalinks, term query strings when every term variable came from the query string, the 404 guess, impossible dates,
content and list paging, an attachment to its file when attachment pages are off, the end-of-URL normalisations, never
towards a post the viewer may not see). As in WordPress, a redirect keeps the host the request named unless only a
`www.` differs from the site's (`Host: evil.test` is sent to `http://evil.test/...`), so an HTTP cache in front of the
application must key its entries on the `Host` header as well as the path. `FrontHeaders` gives the answer's headers: `text/html; charset=UTF-8`,
WordPress's no-cache set for logged-in viewers, previews, 404s and posts with a password, the REST API, alternate and
shortlink `Link` headers of a page, and `X-Redirect-By` with `Location` for a redirect. `RedirectUrls.validate` checks
an address a request names (`redirect_to`) as `wp_validate_redirect` does.

### Rendering

The module serves the pages itself (see [How a request is served](#how-a-request-is-served)) and hands each page to the
`org.quarkword.front.FrontRenderer` bean: `render(request, viewer, passwordState)` answers a `FrontAnswer` (status,
headers, body); its headers are added to the ones the module computed and replace a header of the same name, whatever
its case, and a `HEAD` request gets the headers only. An application or a theme module replaces the renderer with a
bean of its own. Without one, the module's fallback page renders a minimal HTML page: the site's name, a post's title,
date and content through the registered title and content filters (WordPress's password form in place of the content
while the post's password is required), a list's linked titles with links to its previous and next page, or
"Page not found".

### Previews

The viewer of a page is the user of the `quarkword_preview` session cookie, which the REST module's
`POST /quarkword/v1/preview/session` sets (the posts module's `PreviewSessions`), else anonymous. A draft, pending or
scheduled post is shown to a viewer who may edit it, as a preview. A preview pair (`preview_id`, `preview_nonce` with
the viewer's preview token, `preview=true`, as in the REST API's `preview_link`) shows the post with its latest
autosave's title and content (with the revisions module); a pair that is not the viewer's answers 403 "Sorry, you are
not allowed to preview drafts.", as WordPress's JSON `wp_die` object when the request wants JSON, else WordPress's
error page, whose robots meta follows the site's public flag (WordPress's `blog_public`). A preview carries the no-cache
set, `X-Robots-Tag: noindex, nofollow` and `Content-Security-Policy: frame-ancestors 'self'` (D13).

### Password-protected posts

Password-protected posts work as in WordPress, and with WordPress's own cookies. A singular post with a password shows
its title (the heading prefixed `Protected: `, as WordPress's `get_the_title` prefixes it whatever the cookie, and
a private post's `Private: `; the document's `<title>` unprefixed) and, in place of its content, WordPress's form
(`org.quarkword.front.runtime.PasswordForm`, the 7.1.3 classic-theme markup), to every visitor, logged-in or previewing
alike; the renderer is told the post's `PasswordState`. The form posts the password to `wp-login.php?action=postpass`
on the site's path (the `siteurl` of a site must therefore be served by the same application). That endpoint, a
`POST` the catch-all route also takes, sets the `wp-postpass_<md5 of the network's siteurl>` cookie with a fresh
portable phpass hash of the password as entered (`PostPassCookies`: ten days, `home`'s path on a single site and the
network's path (the main site's) on a multisite, `secure` when the target is https, neither `HttpOnly` nor
`SameSite`, as WordPress's), without checking it, and sends the visitor with a 302 to the form's `redirect_to`, else
the referrer, only on the site's own host (`RedirectUrls.validate`), else to `admin_url()`. A cookie holding the hash
of a post's password unlocks every post with that password, and a cookie set by WordPress for the same site works here
(and the other way round). After a wrong password the form shows WordPress's "Invalid password." notice. The page of a
protected post always carries the no-cache set. The endpoint reads the form as PHP reads `$_POST`, stopping after
`max_input_vars` (1000) + 1 pairs (query strings everywhere stop after 1000 variables, as `$_GET` and `parse_str` do),
and reads nothing of a body over 64 KiB (`PostPassEndpoint.MAX_FORM_BYTES`), as PHP leaves `$_POST` empty for a body
over its `post_max_size`: no cookie is set, and the query's `action` and the referrer still count. WordPress's form
posts the password (hashed only up to 4096 bytes, at most 12 KiB encoded), `redirect_to` (a permalink) and `Submit`, a
few kilobytes.

## How a request is served

1. **Entry.** A catch-all route, registered after Quarkus REST's routes and the static resources, takes every `GET`
   and `HEAD` that no filter, static file, resource or other route of the application answered, outside Quarkus's
   non-application root (`/q/`), and every `POST` to a path ending in `/wp-login.php`; any other request is left to
   the server's own 404 or 405 (D19). The request is re-routed internally to the module's page resource, so it is
   served with authentication, the request scope and the site binding active.
2. **Site gate.** No resolved site: 404 with an empty body. A read on an alias domain set to redirect: 301 to the same
   path and query on the site's address. An archived, spam or deleted site: 410 with WordPress's message, as a JSON
   `wp_die` object for a JSON request and an HTML page otherwise, except that, as WordPress checks the preview pair
   before the site, a request that carries a preview pair which is not the viewer's answers 403 (D9).
3. **Feature.** The site's `front` feature off: 404 with an empty body.
4. **Bounds.** A request outside the bounds under [Limits](#limits) is answered 404 (the not-found page) without being
   parsed or resolved and without its session cookie being read (D4).
5. **`wp-login.php`.** A request for `wp-login.php` on the site's path runs its `postpass` action, for a read too, as
   WordPress runs it; anything else there answers 404 with an empty body (D15).
6. **Viewer.** The user of the `quarkword_preview` session cookie, else anonymous; the rest of the request runs as
   that viewer.
7. **Resolution.** `FrontResolver` (above). A bad preview pair: 403 "Sorry, you are not allowed to preview drafts."
   before any query. An `error` of 403, 500, 502 or 503: that status with an empty body.
8. **Redirects.** The old slug redirect, then the canonical redirect: the status, `Location` and
   `X-Redirect-By: QuarkWord`, no body.
9. **Page.** The renderer's answer with WordPress's headers; a `HEAD` gets the headers only.

## Storage

None: the module has no table and no migration. It reads the site's options (`home`, `siteurl`,
`permalink_structure`, `category_base`, `tag_base`, `show_on_front`, `page_on_front`, `page_for_posts`,
`posts_per_page`, `start_of_week`, `wp_attachment_pages_enabled`, `wp_page_for_privacy_policy`, `default_category`,
`blogname`), which the persistence and multisite modules seed with WordPress's defaults (the default structure is
`/%year%/%monthnum%/%day%/%postname%/`, and `/blog/%year%/%monthnum%/%day%/%postname%/` for the main site of a
sub-directory network, see `extensions/persistence/README.md`), and the posts, terms, users and old slugs of the posts,
taxonomy and security modules. The rewrite table is built for each request from those settings and registrations,
never stored (D18). The preview sessions are the posts module's; the post-password cookie lives in the browser only.

## Limits

- A request path above 2,048 bytes or 32 segments, a `..` segment (also percent-encoded), invalid percent-encoding in
  the path or the query, or more than 64 query parameters answer 404 before anything of the request is parsed (D4).
  Query strings are read up to 1000 variables and array names nested up to 64 levels deep, as PHP reads them. Rewrite
  rules are anchored patterns matched only against a path within these bounds.
- The `wp-login.php` `postpass` action reads no form body over 64 KiB: a body whose `Content-Length` is larger is not
  read at all, and one sent without a length is read only up to the bound (D15).
- Feeds, embeds, trackbacks, `robots.txt`, the favicon, sitemaps, comment pages and sign-up pages answer 404 until the
  modules that serve them exist (D3).
- A host application that registers a JAX-RS `ExceptionMapper` for `NotFoundException` or one of its supertypes
  answers unmatched paths itself and disables the front end (D20).
- A date query that compares a unit by unit (`?monthnum=5`, `?day=1`, `?hour=8`) or a week (`?w=1`, D5) is written
  as at most `quarkword.posts.query-date-spans` ranges of dates (512) over the years of the posts the query can find,
  so drafts and other types never widen it; an hour, minute or second reads the distinct dates of those posts, up to
  sixteen times that number. Above, the database compares the local date it computes (see the posts README).
- Pages are the fallback page's until a theme provides a renderer: no templates, widgets, menus or comments.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkword.front.home` | unset | The site address (WordPress's `WP_HOME`): overrides the `home` option of a single site, its trailing slash removed; ignored when multisite is enabled. |
| `quarkword.front.siteurl` | unset | The application address (WordPress's `WP_SITEURL`): overrides the `siteurl` option of a single site, its trailing slash removed; ignored when multisite is enabled. |
| `quarkword.front.force-ssl-admin` | `false` | Admin and login URLs are always https (WordPress's `FORCE_SSL_ADMIN`); they are anyway when `siteurl` is stored with https. |
| `quarkword.front.cache-control` | unset | The `Cache-Control` header of the front-end answers that do not carry WordPress's no-cache set (anonymous viewers, not a 404, a preview or a post with a password); none by default. |

## Differences from WordPress

| Label | Difference |
|---|---|
| D1 | `WP_HOME`, `WP_SITEURL` and `FORCE_SSL_ADMIN` are the settings `quarkword.front.home`, `quarkword.front.siteurl` and `quarkword.front.force-ssl-admin`; as in WordPress, a network ignores the address overrides. The REST API's `url` (in the index and the settings) answers the stored `siteurl` option whatever `quarkword.front.siteurl` says, where WordPress's `WP_SITEURL` changes it too. |
| D2 | Whether a request is https (`is_ssl`) comes from the server's effective request scheme, after the proxy headers it is configured to trust (`quarkus.http.proxy.*`); WordPress reads only the `HTTPS` server variable and port 443. The scheme rules built on it (`home_url` keeps the stored scheme unless the request is https) are WordPress's. |
| D3 | The variables of pages the front end does not serve yet (`feed`, `embed`, `tb`, `robots`, `favicon`, `sitemap`, `cpage`, `rest_route`, `register`, `signup`, `activate`) answer 404, so no page can shadow them; WordPress serves feeds, embeds, trackbacks, `robots.txt`, the favicon, sitemaps and comment pages. |
| D4 | A request outside QuarkWord's bounds (a path above 2,048 bytes or 32 segments, a `..` segment, invalid percent-encoding, more than 64 query parameters) answers 404 (the not-found page) without being parsed or resolved and without its session cookie being read. WordPress has no such bounds. |
| D5 | Date variables are compared on the post's local date in the site's zone, which MySQL cannot convert without its time-zone tables: a year, a year and month, a day or a day with its hour, minute and second as one range of dates, other combinations (`?monthnum=5`, `?day=31`, `?hour=8`, `?year=2026&day=20`) unit by unit, as WordPress's `MONTH(post_date) = 5` compares them, and with the same rows. A unit above 99 (a year above 9999) matches nothing; WordPress compares the number it was given, which for a minute or second of three digits can match a time by its decimal digits (`DATE_FORMAT(post_date, '%H.%i%s') = 08.12300`). A unit or week condition that would need more than `quarkword.posts.query-date-spans` ranges of dates compares the local date the database computes instead, with the limits of the posts README's D38 (MySQL's time-zone tables, its 1970 to 3000 conversion range, Julian dates before 1583). |
| D6 | `orderby=rand`, `comment_count`, `meta_value` and `meta_value_num` are not ordered by: the list falls back to the date (WordPress orders randomly, by comment count, or by the `meta_key` the URL cannot give). |
| D7 | An `author` list holding 0 (`?author=3,0`) applies no author condition; WordPress excludes the posts without author (`post_author NOT IN (0)`), which QuarkWord does not store. |
| D8 | A preview pair (`preview_id` with `preview_nonce`) is checked against QuarkWord's preview token of the viewer for that post, not WordPress's nonce of the action `post_preview_<id>`; a bad pair answers 403 "Sorry, you are not allowed to preview drafts." before any query, as WordPress does. |
| D9 | A front-end read (GET or HEAD) on an alias domain set to redirect answers 301 to the same path and query on the site's address, and an archived, spam or deleted site answers 410 with WordPress's site-check message, as the REST module does; WordPress itself only redirects the `www.` variant of the home host and keeps any other host. |
| D10 | The 404 guess and the old slug and old date lookups compare slugs exactly on the stored, lower-case form, where WordPress's database collation compares without regard to case or accents; as the requested slug is lower-cased and kept percent-encoded first, the answers agree (`/2025/11/20/HELLO-WO/` is guessed, `/2025/11/20/h%C3%A9llo/` is not, in both). The guess takes the first slug in the order of WordPress's slug index (MySQL's `utf8mb4_unicode_520_ci` over the first 191 characters: `_` before `-` before `%` before digits before letters, case folded), then the lowest id, as MySQL answers WordPress's unordered query through that index (`/jav/` finds `java-17` before `java-21`, and `jav_1` before `jav-2`, `jav%d0%ba`, `jav1` and `java`), on PostgreSQL too; the old slug and old date lookups take the lowest id where WordPress takes whichever row its database returns first. |
| D11 | The requests of D3 get no canonical or old slug redirect: WordPress redirects `?p=N&feed=rss2` to the post's comment feed, a post's `comment-page-N/` to the post, `wp-register.php` to the sign-up page and the old feed files to `/feed/`, and the old slug redirect keeps an `embed/` ending. |
| D12 | Without the media module an attachment has no file, so it is never redirected to one: its requests are redirected as with attachment pages on. |
| D13 | No `X-Pingback` header (QuarkWord has no XML-RPC). A redirect is sent with `X-Redirect-By: QuarkWord`. A preview also gets `X-Robots-Tag: noindex, nofollow`, `Content-Security-Policy: frame-ancestors 'self'` and the no-cache set; a redirect is no preview (WordPress redirects a published post asked for as a preview without a valid pair only after it stopped being one) and gets none of them. An anonymous answer that is not a 404 carries `quarkword.front.cache-control` when it is set. |
| D14 | A query parameter written as an array (`post_type[]=book`, `cat[]=2`) is ignored by the front end: no variable is read from it. WordPress keeps an array of scalars for a public variable (a `post_type` array is intersected with the publicly queryable types). |
| D15 | `wp-login.php` serves only the post-password action (`action=postpass`, for a `GET`, `HEAD` or `POST`, where WordPress runs it for any method), on the site's own path (`siteurl` must be served by the same application); anything else there, the login page included, answers 404 with an empty body, and the admin shorthands `/admin`, `/login`, `/dashboard` and `/wp-admin` are served as any other front-end path (where they would be a 404, WordPress redirects them with a 302 to its login and admin screens). The action's fallback target `admin_url()` (`{siteurl}/wp-admin/`) is a page QuarkWord does not serve yet. The action sets no `wordpress_test_cookie`, reads the form only as `application/x-www-form-urlencoded` (PHP also reads multipart forms), and answers 500 for a `redirect_to` array as WordPress fails on it. It reads no form body over 64 KiB, where PHP's `post_max_size` is 8 MB by default (a larger body is ignored as PHP ignores one over that limit; one whose `Content-Length` is larger is not read at all, and one without a length is read up to the bound only). Its redirect is sent with `X-Redirect-By: QuarkWord`. |
| D16 | The password form is written as it is, not passed through the content filters (a theme's `the_content` adds paragraphs around it in WordPress), and its `action` address is attribute-escaped where WordPress's `esc_url` also deletes characters a URL may not hold. A post-password cookie holding a byte outside ASCII, or sent under a bracketed name, unlocks nothing (WordPress compares such a value as given, or fails on an array). |
| D17 | Routing has no hooks: WordPress's rewrite, query-variable, request, canonical-redirect, header and template filters and actions (`rewrite_rules_array`, `query_vars`, `request`, `parse_request`, `redirect_canonical`, `wp_headers`, `template_redirect`, ...) have no counterpart. The rewrite table follows the site's permalink settings and the registered post types and taxonomies (their query variables and rewrite slugs); the main query runs the posts module's `PostQueryFilter`s (`pre_get_posts`); the page is the `FrontRenderer` bean's. |
| D18 | The rewrite table is built for each request and never stored: no `rewrite_rules` option to flush, no `.htaccess` or other server rewrite configuration; a change of the structure, a base or a registration applies to the next request. |
| D19 | The front end answers `GET` and `HEAD` only (and a `POST` to `wp-login.php`); any other method on a front-end path is left to the server's own 404 or 405, and no query variable is read from a form body. WordPress serves its front controller for every method, also reads public query variables from a form body, and answers 400 "A variable mismatch has been detected." when the query and the form give one variable different values, an answer that cannot arise here. |
| D20 | A host application that registers a JAX-RS `ExceptionMapper` for `NotFoundException` or one of its supertypes (up to `Throwable`) makes Quarkus REST answer unmatched paths itself, so the front end's route never runs and the application's mapper answers the site's pages. |
