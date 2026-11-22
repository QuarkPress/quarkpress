# Contributing to QuarkPress

## Prerequisites

- JDK 21 or newer (CI runs 21 and 25)
- Maven 3.9 or newer
- Docker or Podman, for the database tests of the extensions (see the README)
- For `integration-tests/keycloak`'s end-to-end test: a packaged checkout of
  [quarkpress-keycloak](https://github.com/QuarkPress/quarkpress-keycloak), else that test is skipped (see below)
- On JDK 25 only: [quarkus-libvips](https://github.com/SiteNetSoft/quarkus-libvips) installed at the pinned
  commit, or `-Dquarkpress.skip-vips` (see the README's "Building")

Build everything with `mvn verify`. Format with `mvn spotless:apply`; CI runs `spotless:check`.

### The Keycloak provider for the end-to-end test

`integration-tests/keycloak` signs imported WordPress users in through Dev Services' Keycloak with the password-hash
provider of [quarkpress-keycloak](https://github.com/QuarkPress/quarkpress-keycloak) mounted, and reads that
repository's WordPress-made hashes as its fixtures. The provider is not on Maven Central yet, so the test takes a
checkout of it:

- **Where.** By default the directory `quarkpress-keycloak` beside this repository; elsewhere, pass
  `-DquarkpressKeycloakDir=<checkout>`. The test uses
  `<checkout>/wp-password/target/quarkpress-keycloak-wp-password.jar` and
  `<checkout>/wp-password/src/test/resources/wordpress-7.1.3-hashes.txt`.
- **Which commit.** The root `pom.xml` pins it as `quarkpress-keycloak.commit`; CI checks out exactly that commit. A
  checkout at another commit is used, with a warning in the test log. The pin moves only in a `ci(workflows):` commit,
  after the provider commit is pushed.
- **Packaging it.** The provider shades this repository's `lib-wp-password`, so install that first, then package the
  provider (this is what CI does):

  ```bash
  mvn install -DskipTests -pl libs/wp-password,testing/test-gen -am
  git clone https://github.com/QuarkPress/quarkpress-keycloak.git ../quarkpress-keycloak
  git -C ../quarkpress-keycloak checkout <quarkpress-keycloak.commit>
  mvn package -DskipTests -f ../quarkpress-keycloak/pom.xml
  ```

  Package it again after changing the checkout: a jar older than the checkout's main sources or POMs counts as
  missing.
- **Skip locally, required in CI.** Without the jar or the fixtures, or with a stale jar, the end-to-end tests are
  skipped and the build passes. CI (the JDK 21 cells and the native workflow; the JDK 25 cells skip the test) passes
  `-DrequireKeycloakProvider=true`, which makes them fail instead, and then checks the test reports to make sure they
  ran.

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
