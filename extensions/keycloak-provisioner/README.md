# QuarkPress Keycloak Provisioner

Creates QuarkPress users in Keycloak through Keycloak's Admin REST API (Quarkus's `quarkus-keycloak-admin-rest-client`).
It is the security module's `UserProvisioner` for Keycloak: `UserImports.importUser` (see the security README,
"Provisioning users") calls it, then creates the user's profile. A user's WordPress password hash is stored in Keycloak
as a `wordpress` password credential. The `quarkpress-keycloak-wp-password` hash provider, installed in Keycloak,
verifies that credential at sign-in, and Keycloak then re-hashes the password with the realm's algorithm.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-keycloak-provisioner</artifactId>
</dependency>
```

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.keycloak-provisioner.realm` | dev and test: Dev Services' realm (`quarkpress`) | The realm users are created in. Required in production: without it the start fails. |
| `quarkpress.keycloak-provisioner.email-verified` | `true` | Whether a created user's e-mail address counts as verified. |

The admin client's connection and credentials are Quarkus's `quarkus.keycloak.admin-client.*`. In production the start
fails while the password grant (the admin client's default) still uses the default credentials, username `admin` with
password `admin`. The check is narrower than "every default": the username `admin` with another password only logs a
warning, and so does a `server-url` without https. The realm `master` and the client `admin-cli` are not checked
themselves. The start also fails without `quarkpress.keycloak-provisioner.realm`. Use
`quarkus.keycloak.admin-client.grant-type=CLIENT_CREDENTIALS`, a confidential client of the realm whose service account
holds only the `realm-management` roles `manage-users` and `view-users`, and take its secret from the environment.
Dev and test use Dev Services' admin.
