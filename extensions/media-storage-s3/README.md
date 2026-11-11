# QuarkPress Media Storage S3

A storage backend for QuarkPress media: it keeps the uploaded files in an S3 bucket, on AWS or on an S3-compatible
store, through the Quarkus Amazon S3 extension (`quarkus-amazon-s3`) and its synchronous client on the JDK's URL
connection. The files are served by S3 or by a CDN in front of it, never by the application. It requires the media
module.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-media-storage-s3</artifactId>
</dependency>
```

The extension brings `quarkpress-media`, `quarkus-amazon-s3` and the AWS SDK's URL connection client
(`software.amazon.awssdk:url-connection-client`), which is the client `quarkus.s3.sync-client.type` uses by default:
nothing else is needed. Its bean is the media module's `MediaStorage` named `s3` (`@Identifier("s3")`); with another
backend present, `quarkpress.media.storage=s3` chooses it (see the media README). Everything in
`org.quarkpress.media.storage.s3.runtime` is implementation and may change. The module works in a native image; CI
builds and tests it natively.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.media.s3.bucket` | none | The bucket that holds the files. Required when this backend is the one in use: without it the start fails ("quarkpress.media.s3.bucket is required by quarkpress-media-storage-s3"). The bucket must exist; it is not created. |
| `quarkpress.media.s3.key-prefix` | unset | A prefix every object key starts with, such as `media` for `media/2026/11/photo.jpg`, so the bucket can hold other things. Without a leading or trailing `/`, and a valid key itself (see the media README's "Storage"), else the start fails naming the setting. Unset, the keys start at the bucket's root. |
| `quarkpress.media.s3.public-base-url` | unset | The URL the files are published under, such as a CDN's or the bucket's website; its trailing slashes are removed. See [URLs](#urls). |
| `quarkpress.media.s3.presigned-url-ttl` | unset | How long presigned URLs are valid, for a private bucket without a public base URL: a duration longer than 0 and at most 7 days (`P7D`, S3's limit), else the start fails naming the setting. See [URLs](#urls) before using it. |
| `quarkpress.media.s3.conditional-writes` | `true` | Whether new files are written with a conditional request. See [Conditional writes](#conditional-writes). |

The settings are checked at start-up, without a request to the store: a missing bucket or wrong credentials show on
the first upload or read, as an error naming the S3 status and error code. When another backend is the one in use,
nothing here is required or checked, and the S3 client is never built (an application with both modules that chooses
`fs` needs neither these keys nor `quarkus.s3.*`).

### The S3 client

The client's own settings are Quarkus's `quarkus.s3.*` (see the Quarkus Amazon S3 documentation): the region
(`quarkus.s3.aws.region`), the credentials (`quarkus.s3.aws.credentials.type`, by default the AWS SDK's default chain:
environment, system properties, profile files, container or instance roles), the endpoint of an S3-compatible store
(`quarkus.s3.endpoint-override`) and path-style addressing (`quarkus.s3.path-style-access`, off by default). On AWS a
region and credentials from the environment are usually all that is needed.

An S3-compatible store, as an example (the endpoint, region and addressing each store needs are in its own
documentation):

```properties
quarkpress.media.s3.bucket=media
quarkpress.media.s3.public-base-url=https://media.example.com
quarkus.s3.endpoint-override=https://s3.store.example.com
quarkus.s3.aws.region=us-east-1
quarkus.s3.path-style-access=true
quarkus.s3.aws.credentials.type=static
quarkus.s3.aws.credentials.static-provider.access-key-id=${S3_ACCESS_KEY_ID}
quarkus.s3.aws.credentials.static-provider.secret-access-key=${S3_SECRET_ACCESS_KEY}
```

## Keys and objects

A storage key is an object key after the key prefix and a `/`: with the prefix `media`, the main site's
`2026/11/photo.jpg` is the object `media/2026/11/photo.jpg`, another site's files are under `media/sites/<id>/`. S3
takes object keys of at most 1,024 UTF-8 bytes, prefix included, while a storage key alone may already be that long:
a key that is too long with the prefix is refused before any request, naming S3's limit (an upload then fails with the
media module's "could not be moved" message).

## URLs

A file's URL is one of three, in this order:

1. **Public base URL** (`public-base-url` set; recommended): the base URL, a `/` and the object key, its characters as
   they are, never percent-encoded, as the filesystem backend publishes its files:
   `https://media.example.com/media/2026/11/photo.jpg`.
   The URLs are stable and serve as long as whatever is behind the base URL (a CDN, the bucket's website, a public
   bucket) serves the objects.
2. **Presigned URL** (`presigned-url-ttl` set, no base URL): a signed URL of the object, valid for the configured time,
   for a bucket that stays private. Such URLs expire, and WordPress keeps file URLs in post content: an image
   inserted into a post saves its URL into the post, so a saved post's images and links stop working once the time is
   up (at most 7 days later, and sooner when the credentials that signed them expire first: role, instance or STS
   session credentials last hours, and S3 refuses a URL signed with them once they expire), while the media library,
   which asks for fresh URLs on every read, keeps working. Use a public base URL for a site whose posts show media;
   presigned URLs suit a private library only.
3. **Bucket URL** (neither set): the object's URL at the bucket, as the S3 client addresses it (the endpoint and
   path-style settings). It serves the file only when the bucket, or the object, is publicly readable; S3 answers 403
   otherwise.

Presigned and bucket URLs are made by the SDK, which percent-encodes the key; no request is sent for any URL. Every
size of an image gets the URL of its own file in the same mode, so presigned sizes are signed each on their own.

The attachment's guid, recorded at upload time and never changed, is the storage's permanent URL (the media module's
`MediaStorage.guidUrl`): the public base URL form when one is set, otherwise the bucket URL, never a presigned URL,
which expires and is often longer than the 255 characters a guid holds. In the presigned mode the guid is therefore an
unsigned URL that a private bucket does not serve; as in WordPress, a guid is an identifier, not the link a page uses.

The URLs are the same for every site of a network: a site's keys already start with its `sites/<id>/` folder.

On a request made over `https`, the REST API rewrites the main and size URLs of a file to `https`, as WordPress does
for its own URLs. For an `https` site, use an `https` endpoint or an `https` public base URL: a store reached over
plain `http` gets `https://` URLs on that endpoint, which it does not serve.

## Conditional writes

A new file is written with `If-None-Match: *`, so the store refuses the write (412) when the key holds an object,
even when a concurrent upload stored it a moment before; the media module then chooses another name, as it does for
a name taken on the filesystem. A conflict with a concurrent write (409) is retried once. AWS S3 and Cloudflare R2
document this header; whether other S3-compatible stores honour it has not been checked here.

A store that ignores the header would silently replace an existing file. For such a store, set
`quarkpress.media.s3.conditional-writes=false`: a new file is then written after a request that asks whether the key
holds an object. That leaves a race: a concurrent upload of the same name can store its file between the two requests
and see it replaced. Files written to replace (`WriteMode.REPLACE`, such as an edited image) are plain uploads either
way, which readers see whole or not at all.

## Limits

- A file is stored in a single upload, which S3 limits to 5 GiB; a larger file is refused with an error naming the
  limit. There is no multipart upload. The application's request body limit (`quarkus.http.limits.max-body-size`)
  and the media module's upload size rules usually cap uploads far below that.
- An object key is at most 1,024 UTF-8 bytes, key prefix included (see [Keys and objects](#keys-and-objects)).
- A delete asks whether the key holds an object, then deletes it (S3 answers a delete the same either way): it is not
  atomic, so two concurrent deletes of one file can both report that they removed it.
- A folder listing reads every page of the answer (S3 answers at most 1,000 entries per page), lists each name once,
  skips the zero-byte folder markers that the S3 console makes (such as `2026/11/`) and never lists anything outside
  the key prefix.
- A missing object answers as missing whatever 404 the store gives (S3's `NoSuchKey`, or a bare 404 from an
  S3-compatible store). Any other error (403 `AccessDenied`, 500, 503 `SlowDown`) is an error naming the operation,
  the object, the status and the error code, never a missing file.
- A missing bucket (a misspelt `quarkpress.media.s3.bucket`; start-up sends no request) is an error naming
  `NoSuchBucket` only for the requests whose answer has a body: uploads (`put`), reads (`open`) and listings (`list`).
  A `HEAD` answer has no body, and the AWS SDK turns every 404 answer to one into `NoSuchKey`, so the operations that
  ask with a `HEAD` cannot tell a missing bucket from a missing file and answer missing: `exists` answers false,
  `size` answers empty, `delete` answers false without deleting, and the check before a new file's upload with
  `conditional-writes=false` finds the key free (the upload that follows then fails with `NoSuchBucket`).
- The temporary files the media module asks for a request body on its way into the storage are created in the JVM's
  temporary folder (`java.io.tmpdir`), so put that folder on a disk with room for the largest upload, times the uploads
  running at once. The caller deletes each one when the request is answered; nothing sweeps the folder, so files left
  by a crash stay until the folder is cleaned.
- No request checksum setting is offered: the AWS SDK sends its default checksums, and the Quarkus Amazon S3 extension
  gives no hook on the client it builds to change that. A store that refuses them cannot be used yet.

## Serving

The application registers no route for the files: S3, the bucket's website or a CDN serves them, with the content type
each object was stored with (the upload's type, as the media module checked it).

## Switching from the filesystem backend

The media rows hold storage keys, not locations, so switching from `quarkpress-media-storage-fs` is a copy of the
files: copy every file below `quarkpress.media.fs.directory` to the object of the same key below the key prefix (the
directory's `2026/11/photo.jpg` to `<key-prefix>/2026/11/photo.jpg`, `sites/<id>/…` alike; leave out the `.tmp`
folder), then choose the S3 backend. No database change is needed. The guids of existing attachments keep the URLs
they were stored with, as in WordPress; the URLs the media module answers come from the new backend.

## Dev Services and tests

The module's tests and the `integration-tests/media-s3` application use Dev Services for S3 with moto
(`quarkus.s3.devservices.provider=moto`, a pinned `quarkus.aws.devservices.moto.image-name`, the bucket from
`quarkus.s3.devservices.buckets`), so they need a container runtime. Dev Services start only while
`quarkus.s3.endpoint-override` is unset. An application or test with both backends that chooses `fs` sets
`quarkus.s3.devservices.enabled=false`, since the S3 extension would otherwise start a container it never uses.

The AWS SDK reads the shared profile files (`~/.aws/config`, `~/.aws/credentials`) even when Dev Services hand it
static credentials, and fails on a file it cannot parse. The test runs of this module and of the integration
application therefore point `AWS_CONFIG_FILE` and `AWS_SHARED_CREDENTIALS_FILE` at files that do not exist below
`target/`, so a host's AWS setup never matters; a project that runs its own tests against this backend on a host with
a broken profile file needs the same in its Surefire and Failsafe `environmentVariables`.

## Known gaps

- No multipart upload: files above 5 GiB cannot be stored.
- No request checksum setting (see [Limits](#limits)).
- The audio and video player markup of the REST API's attachment view (its `description`) expects a URL without a
  query string: with presigned URLs an audio or video file gets the plain link in place of the player, and the
  `?_=<n>` suffix the player adds to its source would break the signed URL.
