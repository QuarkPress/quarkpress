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
uses only what WordPress itself requires (`json`, `hash`) besides `mysqli`.

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
  class (`src/test/resources/wp-scenario*-skip.txt`, items from the multisite README's "Differences from WordPress")
  and a minimum number of comparisons, so an empty corpus cannot pass.

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

## Adding scenarios

Add a JSON file and its line in `index.txt`. A difference the comparison reports is a finding: fix QuarkPress, or, for
a deliberate difference, add an item to the multisite README's differences table and a skip-list line naming it. A
skip-list line that no longer covers a difference fails the run.
