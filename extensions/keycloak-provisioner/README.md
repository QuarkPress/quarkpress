# QuarkPress Keycloak Provisioner

Creates QuarkPress users in Keycloak through Keycloak's Admin REST API (Quarkus's `quarkus-keycloak-admin-rest-client`).
It is the security module's `UserProvisioner` for Keycloak: `UserImports.importUser` (see the security README,
"Provisioning users") checks `create_users`, calls it, then creates the user's profile. A user's WordPress password
hash is stored in Keycloak as a `wordpress` password credential. The
[`quarkpress-keycloak`](https://github.com/QuarkPress/quarkpress-keycloak) hash provider, installed in Keycloak,
verifies that credential at sign-in, and Keycloak then re-hashes the password with the realm's algorithm. Users keep
their WordPress password and leave the WordPress format at their first sign-in.

Nothing in QuarkPress imports users on its own: importers and host applications call `UserImports`.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-keycloak-provisioner</artifactId>
</dependency>
```

The version comes from `quarkpress-bom`. The module needs `quarkpress-security`, which it brings. Users imported with a
password hash can sign in only once Keycloak has the hash provider: see
[the provider's README](https://github.com/QuarkPress/quarkpress-keycloak#readme) for the jar, its installation and
the Keycloak versions it is tested on. Users imported without a hash need no provider.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.keycloak-provisioner.realm` | dev and test: Dev Services' realm (`${quarkus.keycloak.devservices.realm-name:quarkpress}`); otherwise none | The realm users are created in, the one QuarkPress signs users in through. Required in production: without it the start fails. |
| `quarkpress.keycloak-provisioner.email-verified` | `true` | Whether a created user's e-mail address counts as verified (only set when the user has an address). WordPress trusted the addresses it kept, and QuarkPress reads only verified addresses from tokens. |

Both keys are declared to QuarkPress's core, so a misspelt `quarkpress.keycloak-provisioner.*` key fails the start
and names the key.

### The admin client

The connection and the credentials are Quarkus's own `quarkus.keycloak.admin-client.*`. Their defaults are the
password grant (`grant-type` `PASSWORD`) as `admin`/`admin` through the client `admin-cli` of the realm `master`.
In dev and test the admin client uses Dev Services' Keycloak and its admin, and nothing needs setting.

In production, give the provisioner a client of its own with the least privilege it needs:

1. In the realm users are created in, create a confidential client with service accounts enabled, and the standard
   flow and direct access grants off.
2. Give its service account the `realm-management` client roles `manage-users` (to create users) and `view-users`
   (to find an existing one), and nothing else.
3. Keep the client's secret out of the configuration files: read it from the environment.

With `kcadm.sh` (the same representations the end-to-end test sends through the Admin API; these commands themselves
are not run by the tests):

```sh
kcadm.sh create clients -r <realm> -s clientId=quarkpress-provisioner -s publicClient=false \
  -s serviceAccountsEnabled=true -s standardFlowEnabled=false -s directAccessGrantsEnabled=false
kcadm.sh add-roles -r <realm> --uusername service-account-quarkpress-provisioner \
  --cclientid realm-management --rolename manage-users --rolename view-users
kcadm.sh get clients/<client uuid>/client-secret -r <realm>
```

Then:

```properties
quarkus.keycloak.admin-client.server-url=https://keycloak.example.com
quarkus.keycloak.admin-client.realm=<realm>
quarkus.keycloak.admin-client.grant-type=CLIENT_CREDENTIALS
quarkus.keycloak.admin-client.client-id=quarkpress-provisioner
quarkus.keycloak.admin-client.client-secret=${QUARKPRESS_PROVISIONER_SECRET}
quarkpress.keycloak-provisioner.realm=<realm>
```

### The start check

In production (not in dev or test) the provisioner checks these settings when the application starts:

- The start fails while the password grant (the default) still uses the default credentials, username `admin` with
  password `admin`, whether they are left unset or set explicitly. The grant type is read in every form Quarkus
  accepts (`PASSWORD`, `password`, …).
- The start fails without `quarkpress.keycloak-provisioner.realm`. Both failures are reported together.
- The username `admin` with another password under the password grant only logs a warning: the provisioner then
  signs in as Keycloak's administrator.
- A `server-url` that does not start with `https://` only logs a warning: the provisioner sends password hashes and
  its credentials over it.

The check is narrower than "every default": the realm `master` and the client `admin-cli` are not checked themselves.
The messages name the settings, never their values.

## What an import does

`provision(NewUser)` obtains the admin client's token first, so that a refusal of its credentials is told apart from a
refusal of the user, then posts the user to `POST /admin/realms/<realm>/users`:

- `username` as given (Keycloak stores it lower-cased); `email`, with `emailVerified` from
  `quarkpress.keycloak-provisioner.email-verified`, when the user has one; `firstName` and `lastName` when the user has
  them. Absent names stay absent: none is invented.
- `enabled` true and no required actions.
- With a password hash, one credential: see [The hash import](#the-hash-import).

The outcomes:

| Keycloak's answer | Result |
|---|---|
| 201 Created | `ProvisionedIdentity(subject, created = true)`, the subject from the `Location` header (else found by username) |
| 409 Conflict, and a user of that username exists | `ProvisionedIdentity(subject, created = false)`: the existing user, **unchanged**; its fields and credentials are left as they are, whatever the import says |
| 409 Conflict, and no user of that username | `EMAIL_TAKEN`: another user has the e-mail address |
| 400 | `INVALID_USER`: Keycloak refused a field, such as a username it does not allow |
| 404 | `NOT_CONFIGURED`: no such realm |
| 401 or 403 | `UNAUTHORIZED` |
| no answer, or any other status | `UNAVAILABLE` |

After a 409 the provisioner searches the username exactly and matches it ignoring case, as Keycloak stores usernames
lower-cased: importing `John` finds the user `john`. That makes an import idempotent by username, so a failed import
is retried by calling again: the second call finds the Keycloak user the first one made and `UserImports` creates the
missing profile. Nothing is retried automatically. When the Keycloak user already existed, `UserImports` creates the
profile without the importer's e-mail; the user's first sign-in brings the address Keycloak vouches for. This holds for
a retried import too: its profile has no e-mail until that first sign-in, so a user who never signs in, such as an
imported author, keeps none.

### Errors

Every failure is a `UserProvisioningException` with a reason and one of these fixed texts. No text carries the
request, the user's fields, the hash or Keycloak's answer:

| Reason | When | Message |
|---|---|---|
| `NOT_CONFIGURED` | the realm key is unset or blank | `quarkpress.keycloak-provisioner.realm is not set: the Keycloak realm users are created in.` |
| `NOT_CONFIGURED` | 404 to the creation | `Keycloak has no realm of the name quarkpress.keycloak-provisioner.realm sets.` |
| `UNAUTHORIZED` | the token request is refused (a 4xx), or a 401/403 to any other call | `Keycloak refused the admin client's credentials (quarkus.keycloak.admin-client.*).` |
| `UNAUTHORIZED` | 401 or 403 to the creation | `Keycloak refused to create the user with the admin client's credentials: the client needs the realm-management roles manage-users and view-users.` |
| `EMAIL_TAKEN` | 409, and no user of that username | `Another Keycloak user has the user's e-mail address.` |
| `INVALID_USER` | 400 | `Keycloak refused the user.`, with the field and Keycloak's message key added when they are known ones, such as `Keycloak refused the user's username: error-username-invalid-character.` |
| `UNAVAILABLE` | another status, a 5xx to the token request included | `Keycloak could not create the user: it answered HTTP <status>.` |
| `UNAVAILABLE` | no answer | `Keycloak cannot be reached.` |
| `UNAVAILABLE` | 201, but the user is not found by its username | `Keycloak created the user but does not find it by its username.` |

`INVALID_USER` names only the fields `username`, `email`, `firstName` and `lastName`, and only Keycloak's message keys
`error-username-invalid-character`, `error-invalid-length`, `error-invalid-email`, `error-invalid-value`,
`error-invalid-blank`, `error-person-name-invalid-character`, `error-user-attribute-required`,
`error-username-invalid-idn-homograph` and `error-user-attribute-read-only`; anything else of Keycloak's answer is
left out.
`Keycloak cannot be reached.` keeps the network error as its cause (refused or timed-out connection, unknown host, no
route, TLS failure), which names the address only; any other cause is dropped, as it could quote Keycloak's answer.

A `NewUser` QuarkPress itself refuses (an empty or padded username, an invalid e-mail, a hash that is not a WordPress
one) never reaches Keycloak: see the security README.

## The hash import

A `NewUser` with `PasswordHash.wordpress(user_pass)` gets one credential, written exactly as the provider's README asks
([Import users with their WordPress hash](https://github.com/QuarkPress/quarkpress-keycloak#import-users-with-their-wordpress-hash)):
type `password`, `temporary` false, `credentialData` `{"hashIterations":0,"algorithm":"wordpress","algorithmData":{}}`
and `secretData` `{"value":"<user_pass>","algorithmData":{}}`, without salt. Hash iterations 0 and empty algorithm
data make Keycloak re-hash the password with the realm's algorithm after the first successful sign-in. The end-to-end
test (`integration-tests/keycloak`) imports a WordPress-made hash of every accepted form (`$wp$`, `$P$`, MD5,
`$2y$`/`$2a$`/`$2b$`, ASCII, UTF-8 and quoted passwords), signs each user in through Keycloak with the provider
mounted, and checks that the credential then is Argon2.

Without the provider in Keycloak, a credential of algorithm `wordpress` cannot be verified and those users cannot sign
in. **Never make `wordpress` the server's default hashing algorithm or a realm's hashing algorithm**: the provider's
README explains why (unknown usernames then answer 500, which reveals which usernames exist, and Keycloak cannot hash
new passwords).

## WXR imports carry no hashes

WordPress's exporter never writes password hashes: a WXR file's authors (`WxrAuthor` in lib-wxr) carry login, e-mail,
display, first and last name only. Users imported from WXR are therefore created without a credential, and set a
password through Keycloak (a reset e-mail, or a temporary password an administrator sets). The hash path is for
callers that read WordPress's `wp_users.user_pass` themselves, such as a host application with access to the
WordPress database. QuarkPress's WXR importer (a later milestone) calls `UserImports` once per author, without a hash;
how such a user's first sign-in goes is decided there.

## Keycloak's user profile

**Names and e-mail.** Keycloak's default user profile requires first name, last name and e-mail. A user created
without them is refused at sign-in until the profile is complete: the password grant answers 400 `invalid_grant`
"Account is not fully set up". WordPress users often have no names, and the provisioner never invents them. Dev
Services' realm makes the three attributes optional, so dev and test do not show this. For a production realm, make
them optional:

```sh
kcadm.sh get users/profile -r <realm> > profile.json
# in profile.json, remove the "required" object of the firstName and lastName attributes
# (and of email, for users without an e-mail address)
kcadm.sh update users/profile -r <realm> -f profile.json
```

or, in the admin console, *Realm settings → User profile*, and switch *Required* off for each attribute. The
provider's tests make first and last name optional this way, through the same endpoint of the Admin API.

**Usernames.** The provisioner sends the WordPress login unchanged. Keycloak refuses (400, so `INVALID_USER`)
usernames WordPress accepts: those with a space, `'` or `<` (`error-username-invalid-character`) and those shorter
than 3 characters (`error-invalid-length`). It stores the rest lower-cased. To accept such logins, relax the
`username` attribute's validations in the realm's user profile: the provider's README gives the recipe and what it
relaxes. The provisioner's tests show the refusals; they do not run the recipe.

## Logging

The provisioner logs only Keycloak's status codes and fixed texts, at DEBUG, and nothing of the request or the
answer. A test captures
every log record at level ALL during imports with a hash, a repeated import included, and finds the hash in none. Two
settings would record it anyway:

- `quarkus.rest-client.logging.scope=request-response` logs the admin client's request bodies, hash included. Leave
  it off for the admin client.
- A proxy, or anything else that logs request bodies between QuarkPress and Keycloak.

Keycloak's admin events do not store the hash, even with "Include representation": Keycloak strips `credentials`
from user representations (checked by the provider's tests). Keycloak's database, and realm exports with users, do
hold it until the user's first sign-in replaces it.

## Native

The provisioner has no native-specific setting. CI's native workflow builds `integration-tests/keycloak` as a native
executable and runs its end-to-end sign-in against Dev Services' Keycloak with the provider mounted; native builds
run in CI only.

## Tests

- `extensions/keycloak-provisioner/deployment`: the provisioner against Dev Services' Keycloak without the hash
  provider (creation, the stored `wordpress` credential, an existing username, a taken e-mail, refused usernames, an
  unknown realm, wrong admin credentials, an unreachable Keycloak, the logs), the start check and the declared keys.
- `integration-tests/keycloak`: the end-to-end sign-in with the provider, in JVM mode, as the packaged application
  in production mode with the least-privilege client-credentials client, and natively in CI. The repository's
  CONTRIBUTING says how to give it the provider checkout.

## Differences from WordPress

| | WordPress | QuarkPress |
|---|---|---|
| K1 | `wp_insert_user` checks no capability; WordPress's screens and importers check their own | `UserImports` requires `create_users` (in a network, by default only network administrators hold it), before Keycloak is called |
| K2 | `wp_insert_user` refuses an existing login | importing an existing username returns that Keycloak user unchanged, as WordPress's importers map an existing login to its user; usernames compare ignoring case |
| K3 | `wp_insert_user` keeps a given `user_registered` date, else the current time | `NewUser` has no registration date: the profile's creation time is the import's |
| K4 | `wp_insert_user` strict-sanitizes and trims the login (`O'Brien` becomes `OBrien`) and keeps its case | the login is sent unchanged; Keycloak refuses spaces, `'`, `<` and fewer than 3 characters unless its realm is relaxed, and stores it lower-cased |
