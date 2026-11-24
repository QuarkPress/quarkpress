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
