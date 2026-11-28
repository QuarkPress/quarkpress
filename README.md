<p align="center">
  <img src="quarkword-logo.svg" alt="QuarkWord wordmark" width="520">
</p>

# QuarkWord

QuarkWord brings the business features of WordPress (posts, pages, taxonomies, media,
comments, users and roles, multisite, the block editor, imports) to the Java/Quarkus
ecosystem as a set of independent, composable Quarkus extensions and plain Java libraries.

It is **not a port** of WordPress. It reproduces what WordPress does for its users, using the
modern Java/Quarkus way. See [`QUARKWORD-SPEC.md`](QUARKWORD-SPEC.md) for the full specification.

Project home: <https://quarkword.org>

## Repository layout

| Path | Contents |
|---|---|
| `bom/` | `org.quarkword:quarkword-bom`, aligns all versions with the Quarkus platform |
| `libs/` | Plain Java libraries (`org.quarkword.lib`), zero Quarkus dependency |
| `libs/php-serialize/` | Reader/writer for the PHP `serialize()` format |
| `libs/wp-blocks/` | Parser and serializer for Gutenberg block markup |
| `libs/wp-formatting/` | WordPress's text formatters: autop, texturize, smilies, `convert_chars`, `capital_P_dangit` and excerpts, with WordPress's output |
| `libs/wp-kses/` | WordPress's kses HTML sanitizer: the same allowlists, URL protocols, inline-CSS filtering and output as WordPress |
| `libs/wp-shortcode/` | WordPress shortcodes: register handlers, then expand, strip, find and parse `[shortcodes]` as WordPress does |
| `libs/wp-style-engine/` | WordPress's style engine: block style attributes and theme.json style values become CSS declarations, class names and stylesheets, with WordPress's output |
| `libs/wp-password/` | Verifier for passwords stored by WordPress (MD5, phpass, bcrypt, `$wp$`, application passwords) |
| `libs/wxr/` | Streaming reader and writer for WordPress WXR export files |
| `extensions/core/` | `quarkword-core`: site context, typed ids, events, filter interfaces, per-site features |
| `extensions/persistence/` | `quarkword-persistence`: QuarkWord's datasource and persistence unit, sites, options, site isolation |
| `extensions/multisite/` | `quarkword-multisite`: several sites per network, site resolution by domain, subdomain and path, domain aliases, network administration |
| `extensions/security/` | `quarkword-security`: user profiles from OIDC, site memberships, network administrators, WordPress roles and capability checks |
| `extensions/keycloak-provisioner/` | `quarkword-keycloak-provisioner`: imports users into Keycloak through its Admin API for the security module's `UserImports`, with their WordPress password hashes as `wordpress` credentials that the [quarkword-keycloak](https://github.com/QuarkWord/quarkword-keycloak) hash provider verifies at sign-in, after which Keycloak re-hashes the password |
| `extensions/posts/` | `quarkword-posts`: WordPress posts with their types and statuses, saving rules, trash, sticky posts, meta and queries |
| `extensions/taxonomy/` | `quarkword-taxonomy`: categories, tags and custom taxonomies, terms with WordPress's saving rules, assignments, counts, default categories and queries |
| `extensions/revisions/` | `quarkword-revisions`: revisions and autosaves of posts with WordPress's change check, limits, restore and capability rules, and a per-site switch |
| `extensions/scheduling/` | `quarkword-scheduling`: scheduled publishing of posts by a polling job, the trash and auto-draft purges, and a per-site switch |
| `extensions/media/` | `quarkword-media`: uploads with WordPress's file name, type and size rules, attachments with their metadata, generated image sizes made during or after the upload, deletion of their files, media library queries, a storage and an image processor SPI, and a per-site switch |
| `extensions/media-storage-fs/` | `quarkword-media-storage-fs`: the media storage backend that keeps the files in a directory and serves them under `/wp-content/uploads`, or publishes them under a base URL |
| `extensions/media-storage-s3/` | `quarkword-media-storage-s3`: the media storage backend that keeps the files in an S3 bucket (AWS or an S3-compatible store), published under a base URL such as a CDN's, as presigned URLs or as the bucket's URLs |
| `extensions/media-image-imageio/` | `quarkword-media-image-imageio`: the media image processor on the JDK's ImageIO and TwelveMonkeys (JPEG, PNG, GIF and BMP sizes), also in a native image |
| `extensions/media-image-vips/` | `quarkword-media-image-vips` (Java 25 only): the media image processor on libvips through quarkus-libvips (JPEG, PNG, WebP, AVIF and GIF sizes, HEIC read and converted to JPEG), taking precedence over ImageIO's, which it falls back to for BMP and when libvips is missing |
| `extensions/blocks/` | `quarkword-blocks`: the block types, block renderers, block patterns and pattern categories an application registers through CDI beans, WordPress's core pattern categories, the attributes block supports add, and nested pattern resolution within a configurable bound; no database |
| `extensions/outbound/` | `quarkword-outbound`: a safe outbound HTTP GET for URLs the server fetches on a user's behalf (oEmbed, URL uploads): public addresses only, the connection pinned to the checked address, every redirect checked again, an aborting byte cap, trust through the Quarkus TLS registry |
| `extensions/rest-wp-v2/` | `quarkword-rest-wp-v2`: WordPress's REST API (`/wp-json/`, `wp/v2`) for every site with Bearer tokens: the index, posts, pages, custom types, revisions and autosaves, media and uploads from a URL, search, post types, statuses, taxonomies, terms, users and settings, synced patterns, block types, the block renderer, block patterns and categories, the synthetic active theme, the oEmbed proxy, with WordPress's errors, headers, `_fields`, `_links` and `_embed`; the `quarkword/v1` routes the block editor needs outside WordPress's API (auto-drafts, locks, the preview session, the editor's settings and preload map); routes and settings applications add, and per-site switches |
| `extensions/front/` | `quarkword-front`: the public pages of every site as WordPress serves them: URLs and permalinks under the site's permalink structure, request parsing through WordPress's rewrite rules and public query variables, the main query and its conditional flags, the 404 decision, alias-domain, old-slug and canonical redirects, WordPress's headers, previews and password-protected posts with WordPress's cookie, pages rendered by a replaceable renderer bean (a minimal fallback page until a theme provides one), and a per-site switch |
| `extensions/dev-ui/` | `quarkword-dev-ui`: Quarkus Dev UI pages for QuarkWord in dev mode: the installed modules and what they add, sites, feature toggles and the data each module keeps |
| `testing/media-image-contract/` | The shared tests every media image processor passes (test only) |
| `testing/test-gen/` | Seeded value generators and a property runner on plain JUnit (test only) |
| `testing/wp-oracle/` | Tier A oracle (test only): runs pinned WordPress 7.1.3 PHP code to compare it with QuarkWord libraries, multisite, security, posts, taxonomy and revisions functions |
| `testing/wp-scenario/` | Tier B oracle (test only): a full WordPress 7.1.3 install in a MySQL container, compared with QuarkWord on JSON scenarios |
| `testing/wp-rest-parity/` | REST oracle (test only): the Tier B WordPress 7.1.3 network served over HTTP by `php -S`, the REST scenarios, their normalisation and comparison, the media and editor-extras facts runs and their scenarios (a classic harness theme and must-use plugin switches stand in for the test application's block types, patterns and theme), and the manual capture of the block editor's traffic |
| `integration-tests/` | Applications that test the extensions end to end (JVM locally, also native in CI); `integration-tests/posts` is a single site with posts, categories, tags, revisions and scheduling, tested over HTTP with Keycloak tokens, and launched a second time on one database by its multi-node test (it also runs in `quarkus:dev` with the Dev UI, see `extensions/dev-ui/README.md`); `integration-tests/dev-ui-minimal` checks the Dev UI with only the core and persistence modules, and `integration-tests/dev-ui-posts` with posts but without taxonomy, revisions or scheduling (both dev mode only, never native); `integration-tests/rest-wp-v2` is a network serving the REST API and, through the front module, its public pages, tested over HTTP with Keycloak tokens, and both compared with WordPress 7.1.3 under `-Pwp-parity` (see `testing/wp-rest-parity/README.md`); `integration-tests/media` is a network with the media, filesystem storage and ImageIO modules, tested over HTTP with Keycloak tokens, and its media library compared with WordPress 7.1.3 under `-Pwp-parity`; `integration-tests/media-vips` (JDK 25 only) is the same network with the libvips image processor and ImageIO as its fallback, tested alike and compared with WordPress 7.1.3 with GD and, for its WebP, AVIF, HEIC, TIFF and BMP scenario, with WordPress's recorded answers with Imagick; `integration-tests/keycloak` is a single site with the Keycloak provisioner whose imported WordPress users sign in through Dev Services' Keycloak with the quarkword-keycloak provider mounted, after which their passwords are Argon2 (it needs the provider checkout, see `CONTRIBUTING.md`); `integration-tests/front` is a single site serving its public pages through the front module beside the REST API and the media module, tested over HTTP with Keycloak tokens (permalinks, redirects, 404s, paging, attachments, password-protected posts and previews) |
| `quarkword-logo.svg`, `quarkword-mark.svg` | Wordmark and square mark (SVG) |
| `docs/` | Specification and planning documents |

## Building

Requires JDK 21 or newer and Maven 3.9+.

```bash
mvn verify
```

If your default `java` is older than 21, point Maven at a newer JDK:

```bash
JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 mvn verify
```

On JDK 25 the build also includes the optional libvips image processor, which needs
[quarkus-libvips](https://github.com/SiteNetSoft/quarkus-libvips) installed at the commit named by
`quarkus-libvips.commit` in `pom.xml` (it is not published yet): clone it, check out that commit and run
`mvn install -DskipTests`. To build without it, add `-Dquarkword.skip-vips`.

Format sources before committing:

```bash
mvn spotless:apply
```

Tests of the extensions start PostgreSQL or MySQL through Quarkus Dev Services, so they need Docker or Podman.
Choose the database with `-DtestDb=postgresql` (default) or `-DtestDb=mysql`; CI runs both. With rootless Podman:

```bash
export DOCKER_HOST=unix://$XDG_RUNTIME_DIR/podman/podman.sock
export TESTCONTAINERS_RYUK_DISABLED=true
```

`integration-tests/keycloak` mounts the Keycloak password-hash provider from
[quarkword-keycloak](https://github.com/QuarkWord/quarkword-keycloak), which is not published yet. Without a
packaged checkout of it beside this repository its end-to-end tests are skipped locally; CI requires them. See
`CONTRIBUTING.md`.

Native executables are built and tested in GitHub Actions only; `-Dnative` fails outside CI.

## Licence

Apache License 2.0. See [`LICENSE`](LICENSE).
