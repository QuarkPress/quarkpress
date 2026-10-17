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
| `libs/wxr/` | Streaming reader and writer for WordPress WXR export files |
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

## Licence

Apache License 2.0. See [`LICENSE`](LICENSE).
