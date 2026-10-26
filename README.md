<p align="center">
  <img src="quarkpress-logo.svg" alt="QuarkPress wordmark" width="520">
</p>

# QuarkPress

QuarkPress brings the business features of WordPress (posts, pages, taxonomies, media,
comments, users and roles, multisite, the block editor, imports) to the Java/Quarkus
ecosystem as a set of independent, composable Quarkus extensions and plain Java libraries.

It is **not a port** of WordPress. It reproduces what WordPress does for its users, using the
modern Java/Quarkus way. See [`QUARKPRESS-SPEC.md`](QUARKPRESS-SPEC.md) for the full specification.

Project home: <https://quarkpress.org>

## Repository layout

| Path | Contents |
|---|---|
| `bom/` | `org.quarkpress:quarkpress-bom`, aligns all versions with the Quarkus platform |
| `libs/` | Plain Java libraries (`org.quarkpress.lib`), zero Quarkus dependency |
| `libs/php-serialize/` | Reader/writer for the PHP `serialize()` format |
| `libs/wp-blocks/` | Parser and serializer for Gutenberg block markup |
| `libs/wp-password/` | Verifier for passwords stored by WordPress (MD5, phpass, bcrypt, `$wp$`, application passwords) |
| `libs/wxr/` | Streaming reader and writer for WordPress WXR export files |
| `extensions/core/` | `quarkpress-core`: site context, typed ids, events, filter interfaces, per-site features |
| `extensions/persistence/` | `quarkpress-persistence`: QuarkPress's datasource and persistence unit, sites, options, site isolation |
| `extensions/multisite/` | `quarkpress-multisite`: several sites per network, site resolution by domain, subdomain and path, domain aliases, network administration |
| `extensions/security/` | `quarkpress-security`: user profiles from OIDC, site memberships, network administrators, WordPress roles and capability checks |
| `extensions/posts/` | `quarkpress-posts`: WordPress posts with their types and statuses, saving rules, trash, sticky posts, meta and queries |
| `extensions/taxonomy/` | `quarkpress-taxonomy`: categories, tags and custom taxonomies, terms with WordPress's saving rules, assignments, counts, default categories and queries |
| `extensions/revisions/` | `quarkpress-revisions`: revisions and autosaves of posts with WordPress's change check, limits, restore and capability rules, and a per-site switch |
| `extensions/scheduling/` | `quarkpress-scheduling`: scheduled publishing of posts by a polling job, the trash and auto-draft purges, and a per-site switch |
| `testing/wp-oracle/` | Tier A oracle (test only): runs pinned WordPress 7.1.3 PHP code to compare it with QuarkPress libraries, multisite, security, posts, taxonomy and revisions functions |
| `testing/wp-scenario/` | Tier B oracle (test only): a full WordPress 7.1.3 install in a MySQL container, compared with QuarkPress on JSON scenarios |
| `integration-tests/` | Applications that test the extensions end to end (JVM locally, also native in CI); `integration-tests/posts` is a single site with posts, categories, tags, revisions and scheduling, tested over HTTP with Keycloak tokens, and launched a second time on one database by its multi-node test |
| `quarkpress-logo.svg`, `quarkpress-mark.svg` | Wordmark and square mark (SVG) |
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

Native executables are built and tested in GitHub Actions only; `-Dnative` fails outside CI.

## Licence

Apache License 2.0. See [`LICENSE`](LICENSE).
