# QuarkWord Front

The public pages of QuarkWord sites as WordPress serves them. This module starts with the URLs every link is built
from: `FrontUrls` composes the home, site, admin, login and REST URLs of the current site for the current request from
the `home` and `siteurl` options with WordPress's scheme rules (`home_url`, `site_url`, `admin_url`, `wp_login_url`,
`get_rest_url`, `set_url_scheme`, `force_ssl_admin`). It requires the posts, taxonomy, security and persistence modules
and works on a single site and in a network.

## Installation

```xml
<dependency>
  <groupId>org.quarkword</groupId>
  <artifactId>quarkword-front</artifactId>
</dependency>
```

## Using it

Inject `org.quarkword.front.FrontUrls` (request scoped). With `home` `http://wp.test` and `siteurl`
`http://wp.test/wp`: `home()` is `http://wp.test`, `home("/")` `http://wp.test/`, `siteUrl()` `http://wp.test/wp`,
`admin("")` `http://wp.test/wp/wp-admin/`, `login()` `http://wp.test/wp/wp-login.php`, `rest("/wp/v2/posts")`
`http://wp.test/wp-json/wp/v2/posts` (with an empty permalink structure `http://wp.test/index.php?rest_route=/wp/v2/posts`).
Over an https request every URL is https; over http, `home()` keeps the stored scheme of `home`, `siteUrl()` is http,
and `admin()` and `login()` are https when `forceSslAdmin()` holds (`siteurl` stored with https, or the setting below).
A site without the `home` or `siteurl` option uses its record's `http` address. The REST prefix is the REST module's
`quarkword.rest-wp-v2.path` when that module is installed, else `/wp-json`.

`org.quarkword.front.FrontResolver` (request scoped) resolves a request of the current site as WordPress's front
controller does: `resolve(rawPath, query, viewer)` runs the request parser, the conditional flags, the main query as
`WP_Query` builds it (types, dates, the `w` week under `start_of_week`, slug and id, authors, order with search
relevance, search, term filters, paging, sticky posts first on a home list) as the `Viewer`, the visibility gate of a
singular request (drafts, pending and scheduled posts as previews for a viewer with `edit_post`, private posts with
`read_post`, an attachment by its parent's status), the preview pair and autosave overlay, the queried object and the
404 decision (`handle_404`), and answers a `FrontRequest`: the flags, the queried object, the posts, the shown post
and its date, and the status (200, 404, 403 for a bad preview pair, or the status a plain-permalink `error` asks for).
A request the query parser itself finds not to exist (`?p=-1`, `?monthnum=13`) is answered 200 with the not-found
flags, as WordPress sends its 404 template with a 200 then.

A resolved request is then checked for WordPress's redirects, in its order: `FrontSiteGate.decide` first (an alias
domain set to redirect sends a read to the site's address with 301; an archived, spam or deleted site answers 410 with
WordPress's message), then `OldSlugRedirects.decide` (a not-found post requested by a slug or day it once had: 301 to
it), then `CanonicalRedirects.decide(request, RequestUrl, viewer)` (`redirect_canonical`: plain query-string forms to
permalinks, the 404 guess, impossible dates, content and list paging, an attachment to its file when attachment pages
are off, the end-of-URL normalisations, never towards a post the viewer may not see). `FrontHeaders` gives the answer's
headers: `text/html; charset=UTF-8`, WordPress's no-cache set for logged-in viewers, previews, 404s and posts with a
password, the REST API, alternate and shortlink `Link` headers of a page, and `X-Redirect-By` with `Location` for a
redirect. `RedirectUrls.validate` checks an address a request names (`redirect_to`) as `wp_validate_redirect` does.

The module serves these pages itself. A catch-all route, registered after Quarkus REST's routes and the static
resources, takes every `GET` and `HEAD` that no filter, static file, resource or other route of the application
answered, outside Quarkus's non-application root (`/q/`); any other method is left to the server's own 404 or 405.
It runs, in order: the site gate (except that, as WordPress checks the preview pair before the site, a request on an
archived, spam or deleted site that carries a preview pair which is not the viewer's answers 403, not 410), the
site's `front` feature (off: 404 with an empty body), the request's bounds (a
request outside them is answered 404 without reading anything), the viewer (the user of the `quarkword_preview`
session cookie the REST module's `POST /quarkword/v1/preview/session` sets, else anonymous), the resolution, a bad
preview pair (403 "Sorry, you are not allowed to preview drafts.", WordPress's JSON `wp_die` object when the request
wants JSON; the error page's robots meta follows the site's `blog_public`, as WordPress's), an `error` of 403, 500, 502 or 503 (that status, empty body), the old slug and canonical redirects, then
the page through the `org.quarkword.front.FrontRenderer` bean with WordPress's headers (a `HEAD` gets the headers
only). An application or a theme module replaces the renderer with a bean of its own; without one, the module's
fallback page renders a minimal HTML page: the site's name, a post's title, date and content through the registered
filters (an empty content while the post's password is required), a list's linked titles with links to its previous
and next page, or "Page not found". A host application that maps `NotFoundException` (or a supertype) with a JAX-RS
`ExceptionMapper` makes Quarkus REST answer unmatched paths itself, and the front end then serves nothing.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkword.front.home` | unset | The site address (WordPress's `WP_HOME`): overrides the `home` option of a single site, its trailing slash removed; ignored when multisite is enabled. |
| `quarkword.front.siteurl` | unset | The application address (WordPress's `WP_SITEURL`): overrides the `siteurl` option of a single site, its trailing slash removed; ignored when multisite is enabled. |
| `quarkword.front.force-ssl-admin` | `false` | Admin and login URLs are always https (WordPress's `FORCE_SSL_ADMIN`); they are anyway when `siteurl` is stored with https. |
| `quarkword.front.cache-control` | unset | The `Cache-Control` header of anonymous front-end answers; none by default. |

## Differences from WordPress

| Label | Difference |
|---|---|
| D1 | `WP_HOME`, `WP_SITEURL` and `FORCE_SSL_ADMIN` are the settings `quarkword.front.home`, `quarkword.front.siteurl` and `quarkword.front.force-ssl-admin`; as in WordPress, a network ignores the address overrides. |
| D2 | Whether a request is https (`is_ssl`) comes from the server's effective request scheme, after the proxy headers it is configured to trust (`quarkus.http.proxy.*`); WordPress reads only the `HTTPS` server variable and port 443. The scheme rules built on it (`home_url` keeps the stored scheme unless the request is https) are WordPress's. |
| D3 | The variables of pages the front end does not serve yet (`feed`, `embed`, `tb`, `robots`, `favicon`, `sitemap`, `cpage`, `rest_route`, `register`, `signup`, `activate`) answer 404, so no page can shadow them; WordPress serves feeds, embeds, trackbacks, `robots.txt`, the favicon, sitemaps and comment pages. |
| D4 | A request outside QuarkWord's bounds (a path above 2,048 bytes or 32 segments, a `..` segment, invalid percent-encoding, more than 64 query parameters) answers 404 before anything is read. |
| D5 | Date variables are compared on the post's local date in the site's zone, which MySQL cannot convert without its time-zone tables: a year, a year and month, a day or a day with its hour, minute and second as one range of dates, other combinations (`?monthnum=5`, `?day=31`, `?hour=8`, `?year=2026&day=20`) unit by unit, as WordPress's `MONTH(post_date) = 5` compares them, and with the same rows. A unit above 99 (a year above 9999) matches nothing; WordPress compares the number it was given, which for a minute or second of three digits can match a time by its decimal digits (`DATE_FORMAT(post_date, '%H.%i%s') = 08.12300`). |
| D6 | `orderby=rand`, `comment_count`, `meta_value` and `meta_value_num` are not ordered by: the list falls back to the date (WordPress orders randomly, by comment count, or by the `meta_key` the URL cannot give). |
| D7 | An `author` list holding 0 (`?author=3,0`) applies no author condition; WordPress excludes the posts without author (`post_author NOT IN (0)`), which QuarkWord does not store. |
| D8 | A preview pair (`preview_id` with `preview_nonce`) is checked against QuarkWord's preview token of the viewer for that post, not WordPress's nonce of the action `post_preview_<id>`; a bad pair answers 403 "Sorry, you are not allowed to preview drafts." before any query, as WordPress does. |
| D9 | A front-end read (GET or HEAD) on an alias domain set to redirect answers 301 to the same path and query on the site's address, and an archived, spam or deleted site answers 410 with WordPress's site-check message, as the REST module does; WordPress itself only redirects the `www.` variant of the home host and keeps any other host. |
| D10 | The 404 guess and the old slug and old date lookups compare slugs exactly on the stored, lower-case form, where WordPress's database collation compares without regard to case or accents; as the requested slug is lower-cased and kept percent-encoded first, the answers agree (`/2025/11/20/HELLO-WO/` is guessed, `/2025/11/20/h%C3%A9llo/` is not, in both). The guess takes the first slug in the order of WordPress's slug index (MySQL's `utf8mb4_unicode_520_ci` over the first 191 characters: `_` before `-` before `%` before digits before letters, case folded), then the lowest id, as MySQL answers WordPress's unordered query through that index (`/jav/` finds `java-17` before `java-21`, and `jav_1` before `jav-2`, `jav%d0%ba`, `jav1` and `java`), on PostgreSQL too; the old slug and old date lookups take the lowest id where WordPress takes whichever row its database returns first. |
| D11 | The requests of D3 get no canonical or old slug redirect: WordPress redirects `?p=N&feed=rss2` to the post's comment feed, a post's `comment-page-N/` to the post, `wp-register.php` to the sign-up page and the old feed files to `/feed/`, and the old slug redirect keeps an `embed/` ending. |
| D12 | Without the media module an attachment has no file, so it is never redirected to one: its requests are redirected as with attachment pages on. |
| D13 | No `X-Pingback` header (QuarkWord has no XML-RPC). A redirect is sent with `X-Redirect-By: QuarkWord`. A preview also gets `X-Robots-Tag: noindex, nofollow`, `Content-Security-Policy: frame-ancestors 'self'` and the no-cache set; a redirect is no preview (WordPress redirects a published post asked for as a preview without a valid pair only after it stopped being one) and gets none of them. An anonymous answer that is not a 404 carries `quarkword.front.cache-control` when it is set. |
| D14 | A query parameter written as an array (`post_type[]=book`, `cat[]=2`) is ignored by the front end: no variable is read from it. WordPress keeps an array of scalars for a public variable (a `post_type` array is intersected with the publicly queryable types). |
