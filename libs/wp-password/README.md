# quarkpress-lib-wp-password

A plain-Java verifier for the passwords WordPress stores in `wp_users.user_pass` and for application
passwords. `WpPasswords.check` gives the answer WordPress 7.1.3's `wp_check_password` gives for every format
WordPress core writes or accepts, and `needsRehash` gives the answer of `wp_password_needs_rehash`. Zero runtime
dependencies, no reflection, no Quarkus: bcrypt and BLAKE2b are implemented here from their specifications; MD5,
HMAC-SHA-384 and Base64 come from the JDK.

The library only verifies. It never writes WordPress hashes: after a successful login, store a new hash in your own format or identity
provider instead.

## Coordinates

```xml
<dependencyManagement>
  <dependencies>
    <dependency>
      <groupId>org.quarkpress</groupId>
      <artifactId>quarkpress-bom</artifactId>
      <version>${quarkpress.version}</version>
      <type>pom</type>
      <scope>import</scope>
    </dependency>
  </dependencies>
</dependencyManagement>

<dependency>
  <groupId>org.quarkpress.lib</groupId>
  <artifactId>quarkpress-lib-wp-password</artifactId>
</dependency>
```

## Usage

```java
// A login: the password as typed, user_pass as stored.
if (WpPasswords.verify(password, userPass)) {
    if (WpPasswords.needsRehash(userPass)) {
        // hash the password in the identity provider and stop using userPass
    }
}

HashType type = WpPasswords.detect(userPass); // MD5, WP_BCRYPT, PHPASS, PHPASS_PHPBB, BCRYPT, WP_FAST_HASH, UNKNOWN

// Application passwords (user meta _application_passwords):
boolean ok = WpPasswords.checkFastHash(secret, storedHash);
```

- `check(password, storedHash)` is `wp_check_password` with WordPress's default configuration: no trimming, no
  unslashing, no normalization. The `byte[]` overload takes the password bytes exactly as received.
- `verify(password, storedHash)` is `check`, then, if that fails and the password contains `'`, `"`, `\` or a NUL
  byte, `check` of its `addslashes()` form. WordPress hashes passwords set through its own screens and REST API,
  and checks logins, in that magic-quoted form, so a user who chose `O'Brien` has a hash of `O\'Brien`. Use
  `verify` for logins. A wrong password with such characters costs two hash computations.
- `checkFastHash(secret, storedHash)` is `WP_Application_Passwords::check_password`: a `$generic$` hash is keyed
  BLAKE2b of the secret; anything else goes to `check` (application passwords made before WordPress 6.8).
  The secret is used as given: remove every character other than ASCII letters and digits from the displayed
  `abcd EFGH …` form first, as WordPress's login does.
- `detect` never throws. `needsRehash` is `false` only for a `$wp$2y$` hash of cost 12 (PHP 8.4's default); it reads
  the cost as PHP does.
- `null` arguments throw `NullPointerException` (`detect(null)` is `UNKNOWN`); malformed hashes return `false`.
- Text is encoded as UTF-8 and every length is in bytes, as in PHP. A `String` holding an unpaired surrogate is
  encoded with `?` in its place; pass bytes when the input is not text.
- All methods are static, stateless and thread-safe.

## Formats

| `HashType` | Stored form | How `check` verifies it | Written by |
|---|---|---|---|
| `MD5` | 32 lowercase hex digits | MD5 of the password, constant-time compare | WordPress before 2.5 |
| `WP_BCRYPT` | `$wp$2y$12$…`, 63 characters | bcrypt of Base64(HMAC-SHA-384(key `wp-sha384`, password)) against the rest | WordPress 6.8 and later |
| `PHPASS` | `$P$B…`, 34 characters | phpass portable: 8-byte salt, 2^n chained MD5 rounds | WordPress 2.5 to 6.7 |
| `PHPASS_PHPBB` | `$H$…` | never: WordPress core rejects it | phpBB |
| `BCRYPT` | `$2y$`, `$2a$`, `$2b$` (and `$2x$`), 60 characters | bcrypt of the raw password, which ends at its first NUL byte and after 72 bytes | plugins before 6.8 |
| `WP_FAST_HASH` | `$generic$…`, 49 characters | only through `checkFastHash`: BLAKE2b, key `wp_fast_hash_6.8+`, 30 bytes, URL-safe Base64 | WordPress 6.8 and later |
| `UNKNOWN` | anything else | never | |

`check` applies WordPress's rules in WordPress's order:

1. A stored hash of 32 bytes or fewer is compared with the lowercase hex MD5 of the password. Empty strings, `*`,
   DES hashes and truncated hashes all land here and fail.
2. A password longer than 4096 bytes fails. This comes after rule 1, so a long password still matches its MD5.
3. `$wp` (case-sensitive): the pre-hash is checked against the rest with bcrypt.
4. `$P$`: phpass portable. Count characters `5` to `I` (2^7 to 2^20 rounds) are accepted; WordPress also accepts `J` to `S`, which
   would cost up to a billion MD5 rounds (D8).
5. Anything else: bcrypt with PHP's rules (`$2a$`, `$2b$`, `$2y$`, cost 04 to 16); every other format fails.

## Differences from WordPress

Each difference is exercised by the tests, and the `wp-parity` profile compares everything else with a real WordPress.

| Item | QuarkPress | WordPress | Reason |
|---|---|---|---|
| D1 | `$2x$` hashes (also after `$wp`) fail | verified by `password_verify` | the id of an old 8-bit bug; WordPress never writes it |
| D2 | `$1$`, `$5$`, `$6$`, `$argon2i$` and `$argon2id$` hashes fail; `detect` says `UNKNOWN` | verified by `password_verify` | WordPress core never writes them; support can be added later |
| D3 | bcrypt costs 17 to 31 in a stored hash fail without computing | computed (cost 31 takes days) | bounded work on hostile input; WordPress writes cost 12 |
| D4 | the `$wp_hasher` global and the `check_password`, `password_needs_rehash`, `wp_hash_password_algorithm` and `wp_hash_password_options` filters are not modeled; `needsRehash` assumes PHP 8.4's default cost 12 | plugins can change every answer | plugin territory |
| D5 | `verify` also tries the `addslashes()` form | no such step: WordPress's own screens and REST API pass the slashed form to the hash functions | the identity provider receives raw passwords |
| D6 | `checkFastHash` sends non-`$generic$` hashes to `check`, as `WP_Application_Passwords::check_password` does | `wp_verify_fast_hash` (reset and activation keys) sends them to phpass, which accepts `$H$` and rejects MD5 | application passwords are the case QuarkPress needs |
| D7 | `String` arguments are UTF-8 encoded; unpaired surrogates become `?` | PHP sees the bytes it received | Java strings are UTF-16; use `check(byte[], String)` for exact bytes |
| D8 | `$P$` hashes with a count character above `I` (more than 2^20 rounds) fail without computing | computed, up to 2^30 rounds | bounded work on hostile input; WordPress writes `B` (2^13) |
| D9 | hashing in WordPress's formats is not public | `wp_hash_password` and `wp_fast_hash` write them | the library only verifies; new hashes belong to the identity provider |

## Source policy

Written from a prose description of WordPress's observed behaviour, RFC 7693, the Blowfish and bcrypt papers and
the algorithms' published test vectors. WordPress PHP was read to understand that behaviour and runs as a test
oracle; no WordPress, phpass, crypt_blowfish or libsodium code is copied.
