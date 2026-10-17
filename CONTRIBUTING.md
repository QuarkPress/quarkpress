# Contributing to QuarkPress

## Prerequisites

- JDK 21 or newer (CI runs 21 and 25)
- Maven 3.9 or newer
- Docker or Podman, for the database tests of the extensions (see the README)

Build everything with `mvn verify`. Format with `mvn spotless:apply`; CI runs `spotless:check`.

## Commits

Use [Conventional Commits](https://www.conventionalcommits.org/): `feat:`, `fix:`, `test:`,
`docs:`, `chore:`, `refactor:`, with the module as scope where useful, e.g.
`feat(lib-php-serialize): parse enums`.

## Design rules

The specification in `QUARKPRESS-SPEC.md` is binding. In short:

1. Quarkus and Java best practices first: build-time over runtime, CDI over globals, typed APIs over strings.
2. Everything is optional; each feature is its own module.
3. The core is headless.
4. WordPress compatibility lives only in modules prefixed `wp-` or suffixed `-wp`.
5. Standalone libraries under `libs/` have zero Quarkus dependency.
6. Both PostgreSQL and MySQL are first-class.

## Source policy

QuarkPress reproduces what WordPress does for its users with an original Java and Quarkus
design. It is never a port of WordPress.

Allowed sources:

- WordPress PHP source code, read to understand how a feature behaves and to build test
  harnesses.
- Public documentation: the REST API handbook, developer reference prose, block grammar
  documentation, WXR samples, role and capability documentation.
- Observed behaviour of a running WordPress instance: HTTP requests and responses, database
  contents of a test install, exported files, and WordPress code run as a black-box oracle in
  tests.
- Third-party specifications and articles describing formats (for example the PHP
  serialization format).
- Gutenberg's published npm packages, consumed as dependencies only in GPL-licensed repositories.

Not allowed in this Apache-licensed repository:

- Translating or copying WordPress PHP source code: no line-by-line ports, and no copied regular
  expressions, identifiers or comments. Code follows Java and Quarkus idioms and is designed
  independently.
- Copying WordPress CSS, images or text assets. Those belong in the GPL-licensed repositories.
- Committing WordPress source files. Test harnesses download a pinned WordPress release at test
  time.

When a component must reproduce a WordPress algorithm closely (parsers, hashing, formatting),
work in two steps: one person reads the source and writes a behaviour description in prose with
examples and no code; the implementation is written from that description, and differential
tests against the real WordPress code confirm it.

Every pull request confirms this policy through the PR template checkbox.
