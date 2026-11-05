# WordPress REST parity harness

Serves WordPress 7.1.3 over HTTP so REST API requests can be compared with QuarkPress's: the scenario harness's
subdirectory network (testing/wp-scenario) is copied into its own database of the `mysql:9.5` container, given pretty
permalinks (`/%postname%/`), `WP_ENVIRONMENT_TYPE` `local` and the dev realm's five users (alice, bob, carol, dave,
erin, with the dev realm's roles, e-mails and display names, each with one application password), and served by
`php -S` on the loopback address through a router script that does what Apache's rewrite rules do and presents every
request as one to `localhost` on port 80. The PHP scripts (`php/rest-config.php`, `php/rest-router.php`,
`php/rest-setup.php`) call WordPress functions and mark every rule they apply themselves. Test scope only: never
published, never in the BOM.

## Running it

```bash
mvn -pl testing/wp-rest-parity -am verify -Pwp-parity
mvn -pl integration-tests/rest-wp-v2 -am verify -Pwp-parity
```

The first runs this module's own tests: the site starts and answers, and two recorders of WordPress's answers write
them to `target/wp-rest-parity/verification-facts.json` (one entry per request: status, headers by lower-case name,
parsed body and exact text; an HTML page is summarised there and kept whole under `target/wp-rest-parity/pages/`)
and the OPTIONS answers to `target/wp-rest-parity/options/`: `VerificationFactsDbTest` for the REST verification list,
and `ContentFactsDbTest`, the content facts run, for the content routes (every item of the posts reference's Part G
and the revisions reference's Part F, the `post_format` registration, the `book` type and the OPTIONS bodies of the
content routes). Each content item creates its own fixtures and removes them, and restores any site state it changes;
the lock, preview and new-post items run on the main site, whose `wp-admin` is served, through login cookie sessions
(the `cookie_session`, `nonce` and `logout` operations). The recorded answers stay under `target/` and are never
committed. The second command runs the comparison itself, `RestParityDbTest` in `integration-tests/rest-wp-v2`,
against that module's test application with the dev realm's Keycloak tokens. Both need a container runtime (Docker
or Podman) and `php` 8.4 (`-Dwp.php=/path/to/php` to choose another); without `-Pwp-parity` they are skipped. CI runs
the REST parity run, `RestParityDbTest`, with QuarkPress on PostgreSQL and on MySQL (WordPress always runs on its own
MySQL container); this module's own tests and the Tier B post, term and revision scenarios (`testing/wp-scenario`)
run on PostgreSQL only.

## The media facts run

`MediaFactsDbTest` (run with `-Pwp-parity -Dtest=MediaFactsDbTest`) records what WordPress answers for the media
references' verification lists into the same `verification-facts.json`: items `u01`-`u15` (uploads) and `i01`-`i18`
(image processing), each on a subdirectory site of its own whose uploads folder is emptied before and after it. It
uploads `MediaFixtures` files (images, EXIF/IPTC/XMP/ICC segments and audio, video and document containers written by
hand, shared with media's tests) through `RestCall.multipart`, and calls WordPress's public functions as a black box
through the setup operations `probe`, `prepare_folder`, `attachment`, `uploads_listing`, `network_option`,
`query_attachments` and `remove_upload`. `php -S` runs with `upload_max_filesize` and `post_max_size` of 64M. The
must-use plugin's media switches are site options set to `"1"` by the item that needs them and switched off in its
`finally`: `qp_parity_allow_svg`, `qp_parity_no_unsupported_refusal`, `qp_parity_hook_log` (the log is the option
`qp_parity_hooks`), `qp_parity_no_big_image_threshold`, `qp_parity_fail_on_second_size` and `qp_parity_extra_sizes`.
Items that need an image editor run only when the server's PHP has GD (`php8.4-gd`, as in CI) and are skipped with a
message otherwise; `-Dwp.media.require-editor=true` makes them fail instead. Imagick items never run (GD is the
reference), and the XMP item needs PHP's DOM extension (`php8.4-xml`). The run installs its template network with the
installing administrator as the current user (the opt-in `wp.scenario.installer-current-user` of testing/wp-scenario,
as on WordPress's Network Setup screen), so the network's `upload_filetypes` is written for that administrator. `php -S`
runs with `display_errors=0`, `html_errors=0` and `log_errors=1`: PHP notices go to `php-errors.log` in the run's
directory, never into a body.

## The WordPress side

`WordPressRestSite.start` clones the template network into a database of its own, prepares it and starts `php -S` on a
free port; `root()` is the real `http://127.0.0.1:<port>/`. Preparing it (rest-setup.php `prepare`) sets the options
above, gives alice the network administrator role, creates the five users with their roles on the main site and one
application password each, and then deletes the installer's `admin` user, so both sides have exactly the five users.
`RestClient` sends a `RestCall` (a caller: `anonymous`, a user name, or `invalid`, a credential the server must
refuse; a method, a target, headers and an optional body) with java.net.http, never following redirects, and answers
an `Exchange` (status, headers, body, and `sentAt`, the moment the request was sent, which the normalisation of
recent dates uses). A call may carry its own credential instead (a `Cookie` and `X-WP-Nonce` of a cookie session, or an
`Authorization` header): the client then adds no application password. Further setup runs as rest-setup.php
operations on the database, from the command line, never over HTTP.

A must-use plugin, `php/qp-parity-mu.php`, is written into the tree before `php -S` starts and removed when the site
closes. It applies per-site switches, site options that are `"1"` where a site wants them (harness rules, through
WordPress's public registration functions and filters only):

- `qp_parity_book_type` registers the custom type `book` (public, in REST under `books`, with title, editor, author,
  excerpt, thumbnail, revisions, custom fields and autosave), the type QuarkPress's test application registers too,
  and with it the post meta `book_rating` (integer, single, default 3) and `book_tags` (strings, several values), in
  QuarkPress's registration order, after WordPress's `footnotes`. Every scenario site has it; the content facts run
  sets it on the main site;
- `qp_parity_book_formats` attaches `post_format` (outside REST) to `book`;
- `qp_parity_no_revisions` keeps no revisions (`wp_revisions_to_keep` answers 0);
- `qp_parity_no_trash` makes every type non-trashable (`rest_{type}_trashable`), so the "trash not supported" answer
  can be recorded.

The last three serve the content facts run.

The router serves WordPress's front controller and its PHP files; under it a subdirectory site is REST-only: its
`wp-admin`, `wp-login.php` and static files are not served (the main site's are).

## Scenarios

A scenario (`scenarios/*.json` in the resources, listed in `scenarios/index.txt`, plus generated ones) runs on a fresh
subdirectory site `/<id>/` on each side, with the five users and their roles there:

```json
{"id": "d03", "rule": "what the scenario shows",
 "setup": [{"term": "t:news", "as": "alice", "taxonomy": "category", "name": "News"},
           {"post": "p:draft", "as": "dave", "type": "post", "status": "draft", "title": "Draft"},
           {"option": "posts_per_page", "value": 5}],
 "requests": [{"as": "bob", "method": "GET", "target": "/wp-json/wp/v2/categories/{t:news}",
               "headers": {"Accept": "application/json"}}]}
```

- Setup steps create posts (`post`: a post, page, book or attachment through each side's own services, with
  optional `fields`: content, excerpt, password, parent, `date_gmt`, slug, menu order, comment status, sticky,
  categories and tags, format, featured media, template and meta), regular revisions (`revision`: an update of title
  and content by a user) and autosaves (`autosave`), the auto-draft of a new post (`draft`, on QuarkPress through
  `POST /quarkpress/v1/drafts` as that user), an editor lock of a given age (`lock`), terms (category or post_tag,
  through each side's REST API, with an optional parent ref) and options; `own_site` makes a user the administrator of
  a new site of its own and takes the user's role on the scenario's site away, `leave` only takes it away. Each step
  names a ref (`t:` terms, `p:` posts, `r:` revisions and autosaves) that later steps and requests use in braces; each
  side puts its own id there. Every site starts with the refs `t:uncategorized` (its default category) and `u:<user>`
  for the five users.
- Requests name their caller (`as`), method, target relative to the site, headers, content type and body. Unknown keys
  are refused, so a typo never drops a step.
- The hand-written scenarios come first: one per item of the REST verification list (`d01` to `d28`), then `x01` to
  `x06` for rules found later (among them who writes terms, `x04`, and a member of another site only, `x05`: a
  `{"leave": "<user>"}` setup step takes the user's role on the scenario's site away). The content scenarios follow:
  `p01` the read permission matrix of the posts collection, `p02` its parameters, `p03` a single post and its
  password, `p04` the derived fields, `p05` links and embeds, `p06` the term and format filters, `p07` creating,
  `p08` updating, `p09` deleting, `p10` post meta on writes, `p11` the revisions routes, `p12` the autosaves routes,
  `p13` the auto-draft of a new post, `p14` the front-end preview answers to an anonymous visitor, `p15` search. Then
  60 generated scenarios for each of the seeds `rest-a` and `rest-b`, the same on
  every run: random callers, routes, methods and query parameters drawn from each route's declared arguments, keeping
  clear of requests whose answers differ as a whole (see `RestScenarios`).

## Normalisation and comparison

Before comparing, `Normalizer` makes the two sides' answers comparable: hosts and ports of local URLs become `{host}`
(the scheme is kept) and the scenario's site path `{site}/`; ids of known refs become the ref, in id fields (`id`,
`parent`, `author`, `post`, `term_id` and the like, by the kind of object) and inside local URLs, while an unknown id
becomes `#?<id>` (in URLs too) so that it still differs; `registered_date` becomes its format; the index's own
`authentication` (a key of the body, or of an envelope's body) becomes `{auth}`; the editor preferences' `_modified`
stamp `{stamp}`. Users, terms, posts and revisions are numbered apart, so an id is looked up among the refs of its
kind (`u:`, `t:`, `p:`, `r:`), chosen by the field (`author` a user, `post`, `featured_media`, `p`, `page_id` and
`preview_id` posts, `parent` a term in a term and a post in a post or a revision) or by the object and route an `id`
belongs to. Values that differ by nature become a shape: a `preview_nonce=` value in any text `{nonce}`; a lock value
(`<time>:<user id>`) under a key naming a lock `{lock}:<user ref>`, so the holder still compares; and a `date`,
`date_gmt`, `modified` or `modified_gmt` within two minutes of the request's `sentAt` `{now}` (both sides run within
seconds of each other), older dates compared as they are. `targetHints` and `Allow` are left as they are: they are
compared per caller.

`RestComparison` then compares, per request, the status, the headers `Allow`, `X-WP-Total`, `X-WP-TotalPages`,
`Link`, `Content-Type`, `Location`, `X-Robots-Tag`, `X-Content-Type-Options`, `Cache-Control`, `Expires`, `Vary`,
`Access-Control-Expose-Headers`, `Access-Control-Allow-Headers` (and `Access-Control-Allow-Origin`, `-Credentials` and
`-Methods` when the request sent an `Origin`) and the normalised body: JSON parsed (`[]` and `{}` distinct, key order
ignored), anything else as text; one aspect per differing JSON leaf. For the scenarios about PHP's escapes and the
order of keys (`d14`, `d21`), a body equal once parsed is also compared byte for byte (`body:raw`), each value the
normalisation changed written as its normalised JSON and every other byte kept. The namespaces, routes and index links
QuarkPress does not serve yet are a fixed, named list: each one WordPress lists is recorded as a `wp-only` aspect.
Front-end links (`link`, `permalink_template`, `preview_link`, a search item's `url`) that differ while both sides
have one are recorded as `link-form` aspects, and WordPress's pretty form is then compared in the plain form QuarkPress
writes (`/author/<slug>/` as `?author=<id>`, `/category/.../<slug>/` as `?cat=<id>`, `/tag/<slug>/` as `?tag=<slug>`,
a post's `/<slug>/` as `?p=<id>`, a page's `/<parents>/<slug>/` as `?page_id=<id>`, `/book/<slug>/` as
`?book=<slug>`, `/type/<slug>/` as `?post_format=<slug>`, a permalink template's `%postname%` and `%pagename%`
likewise, a preview link's query kept), the slug checked against the object's own and the id normalised, so a wrong
host, site, id or slug differs; only a link whose object lacks the id or the slug compares as equal. The
`rel="alternate"` entry of a compared `Link` header is mapped the same way (from the body, or from the request's route
for a HEAD). Every difference is written
to `integration-tests/rest-wp-v2/target/rest-parity/differences.tsv`, and each scenario's answers to
`target/rest-parity/<id>.json` there.

## Known differences

`integration-tests/rest-wp-v2/src/test/resources/wp-rest-parity-known-differences.txt` lists the deliberate
differences, one per line: scenario, aspect, label and reason, with `*` and `?` globs. The label is a row of the
"Differences from WordPress" table in `extensions/rest-wp-v2/README.md`, and `KnownDifferenceLabelsTest` checks that
every label used there is a row of it and every row is used. Entries name the aspects that differ, never a whole
request, so the rest of a request is still compared. Every entry is asserted: an entry whose comparisons all match WordPress now, or
that covers no comparison, fails the run. A scenario of `none` documents a difference no scenario can show. Entries
naming generated scenarios by id are fragile: a change to the generator renumbers them.

The run also needs at least `MIN_COMPARISONS` comparisons (9650, 90% of the 10765 of the green run that set it,
rounded down to a multiple of 50; the last green run, 169 scenarios, made 18578), every scenario of the corpus, and at least one comparison per scenario, so a harness that
silently stops comparing fails; with the `wp-parity` profile on, a missing container runtime fails the run instead of
skipping it. `-Drest.parity.only=d01,x0` narrows the run to
scenarios whose ids start so, for local debugging; such a run never passes, since the other scenarios' known
differences then match nothing.

## Capturing the block editor's traffic

The `capture-har` profile records the block editor's network traffic against the live WordPress site as HAR files,
for the administrator (alice) and the author (carol), for each flow (new post, open post, autosave, save draft,
preview, schedule, publish, image upload, the inserter's media tab, the media modal, a reusable block, link search):

```bash
mvn -pl testing/wp-rest-parity -am verify -Pwp-parity,capture-har -Dtest=EditorHarCapture
```

It is manual only and never runs in CI; it downloads Playwright's browsers on first use, and without the profile
neither Playwright nor the capture source is on any path. The files go to `target/har` (`-Dhar.dir=` to choose
another directory) and are never committed: they hold WordPress's responses. The browser keeps WordPress's
`http://localhost/...` URLs and cookies, and Playwright forwards those requests to the server's real port. Only REST
and admin-ajax traffic is kept (one open-post HAR is kept whole). Everything runs on the main site, since a
subdirectory site's `wp-admin` and `wp-login.php` are not served under the router.
