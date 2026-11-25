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
