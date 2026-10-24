# WordPress scenario oracle (Tier B)

Compares QuarkPress with a real WordPress on behaviour that needs a full installation: a JSON scenario (a list of
operations on a network) runs against WordPress 7.1.3 installed in a MySQL container, and against QuarkPress's
services in a Quarkus test application; what each side observed is normalized and compared. Test scope only: never
published, never in the BOM.

## Running it

```bash
mvn -pl testing/wp-scenario -am verify -Pwp-parity -DtestDb=postgresql
```

It needs PHP 8.4 with `mysqli` (`-Dwp.php=/path/to/php` selects another binary), network access for the first
download, and a container runtime (Docker, or Podman through `DOCKER_HOST`). Without a container runtime the tests
report `ASSUMPTION: no container runtime` and are skipped; without `-Pwp-parity` they do not run at all. The driver
uses only what WordPress itself requires (`json`, `hash`) besides `mysqli`. `-Dwp.scenario.outcomes=<file>` appends
every fact both sides observed to that file, one tab-separated line each (case, key, WordPress, QuarkPress), so what
agreed can be read too; the comparison itself reports only differences.

## How it works

- **Release.** The WordPress 7.1.3 release archive is pinned by SHA-256 in testing/wp-oracle
  (`wordpress-7.1.3-release.sha256`), downloaded into `target/wp-source/` and unpacked into `target/wp-scenario/`.
  Neither is committed.
- **Installation.** A PHP driver written for QuarkPress (`php/scenario-driver.php`) installs single-site WordPress into
  a template database of the `mysql:9.5` container and converts it into a network: subdirectory, subdirectory under
  `/net/`, subdomain, and subdirectory with two path segments. It calls WordPress functions and carries no WordPress
  code.
- **Runs.** Every scenario runs in its own PHP process on a fresh copy of its variant's template database, with the
  network constants defined (WordPress's usual multisite configuration). The QuarkPress side moves its main site to
  the same network address, runs the same operations through `Sites` and the HTTP site resolution, and deletes what it
  created.
- **Comparison.** Sites are named by creation order (`#0` is the main site). Each operation and each created site's
  final record is one compared aspect. `testing/wp-oracle`'s `ParityRun` compares them, with a skip list per test
  class (`src/test/resources/wp-scenario*-skip.txt`, items from the multisite README's "Differences from WordPress",
  for the capability scenarios from the security README's table (D5, D6 and D20), for the post scenarios from the
  posts README's and for the term scenarios from the taxonomy README's) and a minimum number of comparisons, so an
  empty corpus cannot pass.

## Scenario format

```json
{"id": "ms3-candidate-paths", "rule": "MS3", "variant": "subdirectory", "ops": [
  {"op": "create_site", "domain": "localhost", "path": "/news/", "title": "News"},
  {"op": "lookup", "host": "localhost", "path": "/0/news/x"},
  {"op": "set_flag", "site": 1, "flag": "archived", "value": true},
  {"op": "read_options", "site": 1, "names": ["blogname"]}
]}
```

- `create_site` creates a site as the network administrator does (an empty title means no title); `lookup` resolves a
  request's host and raw path; `set_flag` sets `archived`, `mature`, `spam`, `deleted` or `public` of a created site;
  `read_options` reads default options. `site` numbers count created sites; `0` is the main site.
- Hand-written scenarios live in `src/main/resources/org/quarkpress/testing/wpscenario/scenarios/multisite/` (listed in
  `index.txt`) and name the behaviour-reference rule they check. Generated scenarios come from `test-gen` with fixed
  seeds (`MultisiteScenarios.generated`).
- Inputs both sides cannot be compared on are refused when a scenario is read: hosts with ports, paths with a query,
  backslash, `%` or `/wp-admin/`, titles with HTML-special characters, flag changes of the main site.

Capability scenarios (`scenarios/capabilities/`) add user, role and capability operations, all on the subdirectory
network: `create_user {username, given_name, family_name}` (empty names are absent; on both sides a new user is a
member of no site), `add_user {user, site, role}`, `remove_user {user, site}`, `set_role {user, site, role}`,
`add_cap {user, site, capability, grant}`, `super_admin {user, grant}`, `network_flag {name, value}`
(`add_new_users`, or `menu_plugins` for the plugins entry of `menu_items`), `site_setting {site, name, value}`
(`link_manager_enabled`), `add_role {site, role, name, capabilities}`, `role_cap {site, role, capability, grant}`,
`remove_role {site, role}` and `can {user, site, capability, target}`. Users count from 1 in creation order; `0` is a
logged-out visitor in `can`, and `target` 0 means no user object. The QuarkPress side runs as the system identity and
mirrors WordPress's installed `admin` user with a placeholder profile. Usernames are refused when WordPress would
treat them differently from the comparison's reach: `admin`, control characters, and, for a user without names, any
username strict sanitizing would change (WordPress would show its login as display name); names are refused with
HTML-special characters or surrounding or doubled spaces.

Post operations act on the main site of the subdirectory network, which the driver empties of WordPress's sample
content when it installs the template: `site_option {name, value}` (`timezone_string`, `gmt_offset`,
`permalink_structure`, `posts_per_page`, `default_comment_status`, `default_ping_status`, `show_on_front`),
`front_page {option, post}`, `insert_post {as, fields}`, `update_post {as, post, fields, edit_date}`,
`publish_post {post}`, `trash_post {as, post}`, `untrash_post {as, post}`, `delete_post {as, post, force}`,
`stick {post, sticky}`, `query_posts {as, args}`, `page_uri {post}` and `read_post {post}` (`PostScenarioJson` lists the
keys of `fields` and `args`). Posts count from 1 in insertion order; `as` is a created user, or `0` for WordPress's
user 0, which QuarkPress plays with its system identity, and a scenario performs only what WordPress's REST layer would
allow that user. Every inserted post's final record is compared field by field (`post#<k>:<field>`), with users and
posts named by index (`u1`, `p1`; `admin` is the installation's user, `none` no one) and `page_on_front` and
`page_for_posts` naming their post; a slug, page path segment or slug meta value that is a post id (with `-<n>` or
`__trashed`) shows as `#self` or `#p<k>`. A slug a scenario gives that reads as a number is 1000 or more: WordPress
numbers the main site's posts from a few, so a smaller one would name a post on WordPress's side only. Dates a scenario
gives lie before 2025 or from 2090 on. A stamp of the run shows as `now` on both sides: a local date or modification
within 14 hours of the run (whatever the site's zone was), a GMT one within the run; each local date's offset from its
GMT date is compared in minutes, so a zone slip on a stamp still shows. Queries name only the types `post`, `page`,
`any` and the statuses `publish`, `future`, `draft`, `pending`, `private`, `trash`, `any`: WordPress also writes
revisions, which QuarkPress does not keep. WordPress leaves rows that tie on every order key to the database; the
driver appends the id in the direction QuarkPress breaks ties in: the first key's (newest first without one) for the
query itself, and descending for WordPress's fetch of sticky posts missing from the first page, which QuarkPress orders
by date, then id, both descending. Query orders are therefore total on both sides. A query ordered by `none` is
compared as a sorted set and must not be paged (`per_page` -1, no `page` or `offset`). Guids are compared only when
every post is inserted under plain permalinks (a `permalink_structure` of `""`): WordPress's network setup gives the
main site a pretty structure, under which a published post's guid is its pretty link. Post text holds no control
characters but tab and line feed and no characters outside the Basic Multilingual Plane, so Tier B has no emoji.
QuarkPress's site options are stored as given, so the runner applies WordPress's option sanitizing on the QuarkPress
side: `posts_per_page` is cast as PHP casts an integer (0 becomes 1, below -1 its absolute value), `gmt_offset` keeps a
number's digits, colons, dots and minus signs and is otherwise empty, and an empty or `0` default comment or ping status
becomes `closed`. A `timezone_string` is not checked against WordPress's zone list, so scenarios name only valid zones.
Generated post scenarios come from `test-gen` with fixed seeds (`PostScenarios.generated`): users insert, update,
publish, trash, restore, delete and stick posts, some under a drawn time zone, and query, read and resolve them.

### Term scenarios

Term operations act on the main site like post operations: `insert_term {as, taxonomy, fields}`, `update_term {as,
term, taxonomy, fields}`, `delete_term {as, term, taxonomy}`, `set_post_terms {as, post, taxonomy, terms, append}`,
`remove_post_terms {as, post, taxonomy, terms}`, `post_terms {post, taxonomy}`, `query_terms {as, args}`, `get_term
{term, taxonomy}` (an empty taxonomy reads the term without one) and `term_exists {taxonomy, value, parent}`
(`TermScenarioJson` lists the keys of `fields`, `terms` and `args`). `as` names a user as in post operations.
Taxonomies are `category`, `post_tag` and `genre`, a hierarchical taxonomy on `post` with the default term General
that the driver registers on every run and the QuarkPress side with `@Taxonomy` (`TierBTaxonomies`). Terms are named
`t<k>` by creation order on the site during the scenario (implicit creations by name included, deleted terms still
counted), `default` is the taxonomy's default term (in `category` and `genre` only) and `none` no parent. Every created
term's final record and the default terms' are compared field by field (`term#t<k>:<field>`,
`term#default-category:<field>`, `term#default-genre:<field>`); a slug that is the term's own id shows as `#self`,
followed by its `-<n>` suffixes if it has any (`#self-2`). `post_terms` and `query_terms` record each term with its
count (`t<k>=<count>`, the padded count when the query pads), so counts are compared wherever terms are listed.
Deleting a default term, which QuarkPress refuses for everyone, is recorded as WordPress's answer (`kept`), as the
taxonomy README's D1 describes.
Term text is printable ASCII, tab and line feed without backslashes (so slashing never matters), at most 300
characters, and a name or slug that reads as a number, or whose slug starts with one, is 1000 or more: WordPress
numbers the main site's terms from a few. WordPress leaves terms that tie on the order key to the database; the driver
appends the term id in the query's direction, and a query ordered by `none` is compared as a sorted set. The scenarios
live in `scenarios/terms/` (hand-written, one or more per behaviour-reference rule, listed in `index.txt`) and
`TermScenarios` (generated with `test-gen` from the fixed seeds `20261021-terms-1` and `20261021-terms-2`, 20 each:
an editor, three to five posts, four to eight terms of the three taxonomies, assignments and removals, a rename, slug
change, re-parenting or slug-and-parent update, post status changes through saves, `publish_post`, trash and untrash,
a deleted post and a deleted term, an assignment naming the deleted term's id, names made of digits, then reads,
lookups and queries with `exclude` and `exclude_tree`; every scenario ends with `post_terms` reads of each remaining
post in all three taxonomies, so what the operations did to unpublished posts is compared too).
`TermScenarioParityDbTest` runs all 50 and requires at least 4150 comparisons (half of the first green run's 8393).
Deliberate differences are in `wp-scenario-terms-skip.txt`, labelled with the taxonomy README's table; the records of
the scenarios' posts use the posts README's labels with the prefix `posts README:` in the reason (today every line is
the posts README's D5, a new draft's GMT modification date, which `publish_post` leaves as it is).

### Revision scenarios

Revision operations act on the main site like post operations: `autosave {as, post, fields, meta}` (through
WordPress's REST autosaves controller), `restore {as, revision, fields}` (with the revisions screen's checks),
`delete_revision {revision}` (as the system), `revisions {as, post, args}`, `autosave_of {as, post, user}` and
`revision_count {as, post}` (`RevisionScenarioJson` lists the keys of `fields`, `meta` and `args`), and the post
operation `update_post_meta {as, post, key, value}` (key `footnotes`, with canonical values, since WordPress sanitizes
others). `as: 0` and `author: 0` are refused in revision scenarios (the system identity is not a user there). A scenario
may carry `revisions_keep` at the top level: the driver defines it as `WP_POST_REVISIONS` for its process and the
QuarkPress side applies it through a filter (`TierBRevisionsKeep`). Revisions are named `r<k>` by creation order of
revision rows on the site during the scenario (regular revisions and new autosaves; deleted ones still counted; a
reference beyond the rows that exist is `missing` on both sides). Every created revision's final record is compared
field by field (`revision#r<k>:<field>`: `parent`, `author`, `autosave`, `title`, `content`, `excerpt`, `footnotes`,
`date_gmt`, the last shown as `now` inside the run's window). An autosave's answer is the autosave `r<k>`, or the post
`p<k>` when the caller's own draft was updated.

Driver rules: the driver defines `WP_RUN_CORE_TESTS` so the autosaves controller does not define `DOING_AUTOSAVE` for
the rest of its process, and removes `wp_save_post_revision` from `post_updated` around an autosave (as the controller
expects when it saves one); a deleted post is `invalid_post` before the capability check; the REST permission refusals
are reported as `forbidden`. `autosave_of` without a user is asked only while at most one user autosaved the post,
since WordPress leaves ties between autosaves of one second to the database. The scenarios live in
`scenarios/revisions/` (hand-written, listed in `index.txt`) and `RevisionScenarios` (generated with `test-gen` from
the fixed seeds `20261022-revisions-1` and `20261022-revisions-2`, 20 each). `RevisionScenarioParityDbTest` runs all
49; the 9 hand-written scenarios make 1152 comparisons and at least 550 are required (the generated ones add to them).
Deliberate differences are in `wp-scenario-revisions-skip.txt`, labelled with the revisions README's table (the posts'
records with the prefix `posts README:`; today the posts README's D5 for a never-updated pending post, four lines, and
the revisions README's D13, two lines).

## Adding scenarios

Add a JSON file and its line in `index.txt`. A difference the comparison reports is a finding: fix QuarkPress, or, for a
deliberate difference, add an item to the multisite README's differences table (the security README's for capability
scenarios, the posts README's for post scenarios, the taxonomy README's for term scenarios) and a skip-list line naming
it. A skip-list line that no longer covers a difference fails the run.
