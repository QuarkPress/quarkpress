# Contributing to QuarkPress

## Prerequisites

- JDK 21 or newer (CI runs 21 and 25)
- Maven 3.9 or newer

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

## Clean-room policy

QuarkPress implements WordPress behaviour from public documentation and observed behaviour,
never by reading or translating WordPress PHP source code.

Allowed sources:

- Public documentation: the REST API handbook, developer reference prose, block grammar
  documentation, WXR samples, role and capability documentation.
- Observed behaviour of a running WordPress instance: HTTP requests and responses, database
  contents of a test install, exported files.
- Third-party specifications and articles describing formats (for example the PHP
  serialization format).
- Gutenberg's published npm packages, consumed as dependencies only in GPL-licensed repositories.

Not allowed in this Apache-licensed repository:

- Reading, translating or copying WordPress PHP source code.
- Copying WordPress CSS, images or text assets. Those belong in the GPL repositories.

Every pull request confirms this policy through the PR template checkbox.
