# QuarkPress Media

WordPress media for QuarkPress: uploads checked with WordPress's file name, type and size rules, stored through a
storage backend module, image sizes generated through an image processor module, and the media library of a site as
attachments, with a per-site switch (`media`). It requires the posts module and works on a single site and in a
network. A storage backend is needed: add `quarkpress-media-storage-fs` (files in a directory) or
`quarkpress-media-storage-s3` (files in an S3 bucket), or provide a `MediaStorage` bean.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-media</artifactId>
</dependency>
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-media-storage-fs</artifactId>
  <!-- or quarkpress-media-storage-s3 -->
</dependency>
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-media-image-imageio</artifactId>
</dependency>
```

The extension brings `quarkpress-core`, `quarkpress-persistence`, `quarkpress-security`, `quarkpress-posts`,
`quarkpress-lib-wp-kses`, Apache Tika (`tika-core`) and metadata-extractor. Without an image processor module an
upload that declares an image type (other than SVG, HEIC or HEIF) is refused with
`rest_upload_image_type_not_supported`, as WordPress without an image editor refuses it, unless it asks for no sizes
or `accept-unprocessable-images` is set; such images, and images that declare no type, are stored with their
dimensions and embedded data, without generated sizes. Public API lives in `org.quarkpress.media`;
`org.quarkpress.media.runtime` is implementation and may change. The migrations are in band V7: the `qp_media`
table, and WordPress's network upload options as its network setup writes them (only a network reads them; options
already set are kept).

## What it does

Inject `Media`. It works on the site the request is bound to; every method refuses with
`SiteFeatureDisabledException` when the site's `media` feature is off (the stored files are still served: they are
public URLs, as WordPress serves them without PHP).

```java
MediaSnapshot photo = media.upload(Upload.builder("kite.jpg", receivedFile).title("Kite").build());
String thumbnail = photo.url("thumbnail");
media.update(photo.post().id(), MediaChanges.builder().altText("A red kite").build());
MediaPage images = media.query(MediaQuery.builder().mimeTypes(List.of("image")).perPage(20).build());
```

- `store(Upload)` stores an upload as WordPress's REST upload does: the
  caller needs `upload_files` (and `edit_others_posts` of attachments to name another author); a parent must exist on
  the site (else `rest_cannot_edit`, as WordPress answers it) and be editable by the caller; an image type the image
  processor cannot make sizes for is refused (`rest_upload_image_type_not_supported`) unless it is SVG, HEIC or HEIF,
  the upload asks for no sizes, or `accept-unprocessable-images` is set; a parent that is an attachment or a revision
  is refused (`rest_invalid_param`). Then come the size limit, the network's limits on a network, the upload filters,
  the empty file, the type check against the name and the content, the cleaned file name, the folder and a unique
  name, and only then the file, the attachment post (its guid the file's URL) and the media row, `pending`. The checks
  that read rows run in the caller's transaction, or else in a short one of their own; the file is then checked, read
  and stored outside any transaction of the upload's, so copying a large file holds no database connection; only the
  attachment's rows are written in a transaction, which joins the caller's when there is one. Every refusal comes
  before anything is written; a failure after the file was stored marks the transaction for rollback, and the file is
  removed once it has rolled back. After `store` alone the attachment stays `pending` in the `sync`
  mode until `process` makes its files; in the `async` mode it is processed in the background as an upload is.
- `upload(Upload)` is `store` in a transaction of its own that commits, then the processing (see
  [Processing](#processing)). `upload(Upload, AfterStore)` runs a step of the caller's between the two, with the
  stored, still pending attachment and outside any transaction (the REST upload writes its featured image and meta
  there); an exception the step throws reaches the caller unchanged and leaves the attachment stored and unprocessed
  (`pending`, without metadata), in either mode, and in the `async` mode the background run is queued only once the
  step returned. `Upload`'s fields beyond the file and its name are the title, caption, description, alternative
  text, parent, author, status, date (`date`, `dateGmt`; a date never schedules an attachment), slug, comment and ping
  status, `generateSubSizes`, `convertFormat`, `imageTypeCheck` (whether the image type check before the file is read
  applies, true by default; the REST raw upload turns it off, as WordPress checks only a multipart file part's
  declared type) and `clientNameTitle` (whether the client's file name gives the title when neither the request nor
  the file does, true by default). `process(id)` is WordPress's post-process of an attachment (`edit_post` needed);
  `missingSizes(id)` lists the registered sizes it lacks, as WordPress lists them for its REST view.
- `find(id)` reads an attachment with its media row, with the posts module's read rules; `read(id)` reads it with no
  capability check, as WordPress's own lookup, for a caller that decides access itself (empty for an attachment
  without a media row). `update(id, MediaChanges)` saves the title, caption, description,
  parent, author and status through `Posts.update` and the alternative text (cleaned as a text field) into the media
  row; the post is saved even when only the alternative text changes, as WordPress saves it.
- `delete(id)` deletes an attachment for good through `Posts.delete` with `force` (`delete_post` needed): featured
  images that name it are cleared and its media row goes with the post; once the delete has committed, its files are
  removed (a legacy thumbnail no other attachment names, the sizes, the untouched original, the files of the
  client-side flow, the image editor's backups, then the main file). Deleting an attachment through `Posts.delete`
  removes its files alike.
- `query(MediaQuery)` lists the site's attachments as a post query of type `attachment`, with the posts module's read
  rules, filtered by MIME type as WordPress reads its MIME-type argument (`image`, `image/*`, `image/jpeg`, comma
  lists), or by exact types (`exactMimeTypes`, compared case-sensitively, in place of `mimeTypes`). No statuses means
  `inherit`, plus `private` for a caller who may read others' private attachments. `unfiltered` reads the rows of the
  statuses given, of every author, whoever asks and without any capability, all counted as found, for a caller that
  decides per row what it shows (WordPress's REST collection). `authorNotIn`, `parentNotIn` (0 leaves out the
  unattached attachments), `include` (`OrderKey.POST_IN` orders by it), `exclude` and `slugs` (`OrderKey.NAME_IN`
  orders by it) are read as `PostQuery`'s; `exactSearch` and `searchColumns` are passed to the post query.
  `search` also matches the attached file's path relative to the site's uploads folder (each term as a part of it, a
  `-term` as one it must not hold, an exact search the whole path), whatever columns are searched. `countOnly` counts
  without reading the attachments. Attachments without a media row are neither listed nor counted (D22).
- `spool(InputStream)` copies a request body to a new file in the storage's temporary area
  (`MediaStorage.temporaryFile`) and answers it; the caller deletes it once done. `allowedTypes()` answers the
  caller's upload types on the site (extension patterns with their MIME types, in lookup order, the network's list and
  `unfiltered_html` applied), `outputType(mime)` the type an image's sizes are written in when the image processor
  supports it and `output-formats` changes it, and `refusesImageType(declared, generateSubSizes)` whether an upload
  declaring that type is refused as an image the server cannot make sizes for, before its file is read.

Refusals of an upload are `MediaException`s (an `IllegalArgumentException`) whose `code()` and message are
WordPress's: `upload_error` ("Sorry, you are not allowed to upload this file type.", the empty-file messages, "The
uploaded file could not be moved to …", "Unable to create directory …", an upload filter's message, and
QuarkPress's own "This file is too big. Files must be less than {n} KB in size." for `max-upload-size`),
`rest_upload_limited_space`, `rest_upload_file_too_big` and `rest_upload_user_quota_exceeded` (the network's limits),
`rest_upload_image_type_not_supported`, `rest_invalid_param` and `rest_cannot_edit`. Capability refusals are Quarkus's
`UnauthorizedException` and `ForbiddenException`. An id that is no attachment of the site (or has no media row) is
the posts module's `invalid_post` for `update`, `process` and `missingSizes`; `find` and `delete` answer empty.

| Call | Needs |
|---|---|
| `store`, `upload` | `upload_files`; `edit_others_posts` of attachments to name another author; `edit_post` on the parent |
| `process` | `edit_post` on the attachment |
| `update` | `edit_post` on the attachment, then the posts module's update rules |
| `delete` | `delete_post` on the attachment |
| `find`, `missingSizes`, `query` | the posts module's read rules |
| `MediaQuery.unfiltered` | checks nothing: the caller decides per row what it shows |
| `read`, `spool`, `allowedTypes`, `outputType`, `refusesImageType` | checks nothing |

Every call needs the site's `media` feature on.

`MediaSnapshot` holds the attachment post and the file side: the type, the storage key, the attached file (the path
relative to the site's uploads folder, as `_wp_attached_file`), the untouched original's name when the main file is a
scaled, rotated or converted copy, the size, the dimensions, the sizes, the embedded data (`image_meta`), the other
metadata keys, the alternative text, the processing state and the URL. `url(sizeName)` gives a size's URL and
`wordPressMetadata()` the metadata as WordPress stores it in `_wp_attachment_metadata` (`{}` while pending).

## Configuration

Run time unless said; every key is checked at start-up with the other `quarkpress.*` keys.

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.media.max-upload-size` | unset: no limit | The largest file a site accepts (`20M`); a larger one is refused with "This file is too big. Files must be less than {n} KB in size.", `n` the limit's bytes / 1024 rounded down. On a network, the network's limits (`fileupload_maxk`, the site's upload space) still apply. |
| `quarkpress.media.network-limits.enabled` | unset: follow the network | Whether the network's upload limits apply on a network; unset, they apply when the network option `upload_space_check_disabled` is false as PHP reads it. A network QuarkPress creates sets that option to `"1"`, as WordPress's network setup does, so the limits start off. |
| `quarkpress.media.allowed-types."<pattern>"` | none | Upload types added to WordPress's list, extension pattern (`jpg\|jpeg`) to MIME type. A pattern WordPress's list holds takes the new type in its place; the others come after the list, sorted by pattern. A pattern holding a dot fails the start (a file's type is looked up by the part of its name after its last dot), and so does a type longer than 100 characters (an attachment's type holds at most 100). |
| `quarkpress.media.denied-extensions` | none | Extensions no site may upload, whatever the types say; each is trimmed and may start with a dot (`.exe`). A pattern left without extensions is dropped. |
| `quarkpress.media.accept-unprocessable-images` | `false` | Store an image the image processor cannot make sizes for (WebP and AVIF with ImageIO), without sizes, instead of refusing it. |
| `quarkpress.media.processing` | `sync` | When the sizes are made: during `upload` (`sync`) or after it, in the background (`async`). |
| `quarkpress.media.big-image-threshold` | `2560` | The longest side above which an image is scaled down into a `-scaled` copy used in place of the original, which is kept; `0` turns the scaling off. |
| `quarkpress.media.max-image-pixels` | `100000000` | The most pixels (width × height, read from the file's header) an image may have to be decoded at all; a larger image is stored without sizes. |
| `quarkpress.media.max-concurrent-decodes` | unset: half the available processors, at most one per 512 MiB of the maximum heap, at least 1 | How many images are decoded at once; further runs wait for their turn, at most `decode-wait`. |
| `quarkpress.media.decode-wait` | `30s` | How long a processing run waits for a decode permit. A run that waits longer stops, logged: an attachment still `pending` is marked `failed` (a post-process starts it again), one already `done` or `partial` is left as it was. |
| `quarkpress.media.async-runs` | unset: the number of concurrent decodes | How many background runs (the `async` mode) hold a temporary copy of their file at once; further runs wait for their turn, without a transaction and without a time limit, before they copy anything. |
| `quarkpress.media.output-formats."<mime>"` | `image/heic`, `image/heif`, `image/heic-sequence`, `image/heif-sequence` → `image/jpeg` | The type sizes of an image type are written in, applied over the defaults; a mapping to a type the image processor does not write is ignored. |
| `quarkpress.media.storage` (build time) | unset | The storage backend to use when several are present: a backend module's name (`fs`, `s3`) or the `@Identifier` (else the class's simple name) of an application's `MediaStorage` bean. Needed only then. |

The filesystem backend's keys (`quarkpress.media.fs.*`) and the S3 backend's (`quarkpress.media.s3.*`) are described
in their READMEs.

The sizes are WordPress's: `thumbnail`, `medium`, `medium_large` and `large` from the site's options
(`thumbnail_size_w`, `thumbnail_crop` and the others), then `1536x1536` and `2048x2048`, then the sizes of
`ImageSizeProvider` beans; a size of 0×0 is not made. The site options `uploads_use_yearmonth_folders` and `WPLANG`
(which picks the language of an XMP alternative text) are read as WordPress reads them.

## Storage

Exactly one storage backend is used: the build fails without one, with several and no `quarkpress.media.storage`
naming one, with `quarkpress.media.storage` naming a backend that is not present, or with two beans under one name.
The modules `quarkpress-media-storage-fs` (`fs`, a directory on the server) and `quarkpress-media-storage-s3` (`s3`,
an S3 bucket on AWS or an S3-compatible store) are the backends QuarkPress ships; only the chosen one's settings are
required and checked.

Keys are paths of `/`-separated segments: the main site's files are under `<year>/<month>/` (or directly at the root
when the site turned year and month folders off), another site's under `sites/<id>/<year>/<month>/`, as WordPress
lays out its uploads folder. The year and month are those of the upload, in the site's zone, or the parent's date when
the parent is not a page. The main file, its sizes, the untouched original and any other derived file of an attachment
share its folder. A key is at most 1,024 UTF-8 bytes, never starts with `/`, and has no empty, `.` or `..` segment, no
`\`, no control character below U+0020 and no unpaired surrogate; every storage method refuses any other key before it
touches the storage.

New files are always written create-exclusive: when another upload took a name between the folder listing and the
write, a new unique name is chosen from a fresh listing, at most ten times. A size whose name is taken by a file of
another attachment is numbered (`photo-150x150-1.jpg`).

The guid of an attachment is its file's permanent URL when it is stored (`MediaStorage.guidUrl`, by default its
public URL; never an expiring one: the S3 backend gives an unsigned URL even when it serves presigned ones), at most
255 characters: a longer one refuses the upload with the "could not be moved" message, before anything is stored. It
never changes, as in WordPress. The URLs of `MediaSnapshot` (the main file's and each size's, each the storage's
public URL of that file) are computed from the active backend on every read. Switching from the filesystem to another
backend (S3) is therefore a copy of the files under the same keys (below the S3 backend's key prefix, when one is
set): the media rows name keys, not locations; only the guids of existing attachments keep the URLs they were stored
with.

## Processing

`upload` and `process` must be called outside a transaction and refuse inside one with `IllegalStateException`
("Media.upload and Media.process must be called outside a transaction: processing commits each save on its own");
`store` alone joins a caller's transaction. A processing run saves the metadata in transactions of its own, each
locking the media row and writing it whole: the base metadata (dimensions and `image_meta`, and the main-file copy
when there is one) first, then one save per size, then the final state. A file is stored inside the save that names
it, so a stored entry always names an existing file; two runs of one attachment at once agree on what is made.

A new image whose type and dimensions are read from its header gets, when the image processor handles its type and
it is within `max-image-pixels`: one main-file copy when one applies (scaled down above `big-image-threshold` and
turned upright from its JPEG orientation, else converted to the output type, else turned upright), then its
registered sizes, each cut from the upright original decoded once, encoded at quality 82. Audio and video get the
metadata their container states; a box or chunk that declares more bytes than the file holds ends the reading there,
and an MP4 or QuickTime file is read from a checked copy of at most 32 MB of its movie headers and sample tables, so
reading the metadata never allocates more than the file holds. Any other file gets its size only.

- In the `sync` mode `upload` answers the processed attachment. In the `async` mode it answers the stored one, still
  `pending`, and the run starts once the storing transaction has committed, on a thread of its own bound to the
  attachment's site, as the system identity. At start-up in the `async` mode, the attachments of every site still
  `pending` whose post is older than five minutes (left by a run of the application that stopped first) are processed
  again, oldest first, at most a hundred per start; failures are logged and never fail the start.
- An upload that asks for no sizes, or no format conversion, keeps that choice with its pending row, so a background
  run, a start-up resume or a post-process honours it.
- `process` on an attachment without metadata processes it from scratch, as WordPress does: one still `pending` or
  `failed`; `missingSizes` then lists every registered size, whatever the image's dimensions. Otherwise it makes the
  missing sizes from the original (and the main-file copy again when an earlier run failed to make it, see D20); with
  nothing missing the attachment is returned unchanged and nothing is written. A file of an image type that cannot be
  read as an image keeps its file size as its metadata: nothing is missing and `process` changes nothing, as in
  WordPress.
- Processing never fails an upload. The processing state (`MediaSnapshot.processing`) is `pending` until a run ends,
  then `done` (everything made, or nothing to make), `partial` (a decode, a size or the main-file copy failed; a
  post-process makes what is missing; also a later save that failed, or a run out of memory) or `failed` (no metadata
  was saved: the stored file could not be read, the first save failed, or the run threw before it; an error such as
  `StackOverflowError` is rethrown after marking). A run that throws never leaves the attachment `pending`. The
  `MediaProcessed` event is fired by a run that changed the attachment or its state; a second run of the same attachment
  that found everything made is not announced.
- Decodes wait for one of `max-concurrent-decodes` permits, handed out in arrival order and held from the decode to
  the run's last size, at most `decode-wait` (30 s by default): a run that gets none in time stops, logged, and a
  still `pending` attachment is marked `failed`. Memory: see the image processor's README (ImageIO: about 2–3× the
  decoded raster of an image at peak); `max-image-pixels` bounds the largest raster, checked from the header before
  any processor decodes.
- In the `async` mode a background run first waits for one of `async-runs` run permits (by default as many as the
  decode permits), handed out in arrival order, so at most that many temporary copies exist at once. The wait has no
  time limit and holds a thread of the shared executor that runs asynchronous observers while it waits, so a burst of
  uploads larger than the permits can delay other asynchronous observers.
- A run copies the stored file to the JVM's temporary folder (`java.io.tmpdir`) and writes each copy and size there
  before storing it, outside any transaction; only the saves that name the stored files lock and write the media row.
  The copies are removed at the end of the run.

## Events

Events are `SiteEvent`s, fired synchronously inside the transaction that caused them and again to `@ObservesAsync`
observers once it has committed:

| Event | WordPress | When |
|---|---|---|
| `AttachmentAdded` (posts) | `add_attachment` | an attachment is created; fired by `Posts.create` inside the storing transaction, before the media row exists |
| `MediaStored` | (none) | an upload is stored: its file, attachment and media row exist, its sizes are still to be made |
| `MediaProcessed` | (none) | a processing run ended, after its last save; a post-process that finds nothing to make fires nothing |
| `AttachmentEdited` (posts) | `edit_attachment`, `attachment_updated` | an attachment is updated, also when only its alternative text changes |

An upload fires `AttachmentAdded`, `MediaStored`, then `MediaProcessed`. Attachments fire none of the posts module's
save events (`PostStatusChanged`, `PostPublished`, `PostScheduled`, `PostUpdated`, `PostSaved`, `PostCreated`), as
in WordPress; publishing, trashing,
restoring and deleting one fire what the posts module fires for them (see its README). The taxonomy module gives
attachments the default terms of taxonomies registered for them on `AttachmentAdded` and `AttachmentEdited`.

## SPIs

- `MediaStorage`: where the files are kept (`put` create-exclusive or replacing, `open`, `exists`, `list`, `delete`,
  `size`, `publicUrl` for the URL a file is served under, `guidUrl` for the permanent URL recorded as the attachment's
  guid: by default the public URL, and a backend whose public URLs expire, such as signed ones, answers one that does
  not; and `temporaryFile` for a request body on its way into the storage: by default a new file in the JVM's
  temporary folder; a backend that writes local files answers one on the file system it stores on). A backend
  module produces a `MediaStorageBuildItem` with its name and one bean of this type with `@Identifier` of that name; an
  application may provide its own bean instead. A bean is named by its `@Identifier`, else by its class's simple name; a
  producer method's bean is named by the class that declares the method, so give produced beans an `@Identifier`.
- `ImageProcessor`: decodes, turns, cuts, scales and encodes images (`supports` a type means it reads and writes it).
  An image processor module provides one bean; without one, the module's default handles no type.
- `UploadFilter`: refuses an upload before it is stored, as WordPress's upload prefilter does; the message becomes an
  `upload_error`. Every bean runs, highest `@Priority` first, until one refuses. Filters run with the file work,
  outside any transaction of the upload's: in the caller's transaction when it has one, else in none, so a filter that
  reads the database opens its own.
- `Media.AfterStore`: the step `upload(Upload, AfterStore)` runs between storing and processing, with the stored
  attachment and outside any transaction.
- `MimeTypes`: the upload types of every site (WordPress's list and the configured ones); an application bean replaces
  the default one.
- `ImageSizeProvider`: registers image sizes, as `add_image_size`; read once at start-up, higher `@Priority` first,
  then by class name. A name registered twice keeps the place of its first registration and the definition of its
  last; a provider may so redefine a built-in size, whose site options are then ignored. `ImageSizes` answers the
  current site's sizes.

## Limits

- There is no `unfiltered_upload`: no user may upload a file whose type the site does not allow, as in WordPress
  without its `ALLOW_UNFILTERED_UPLOADS` constant.
- Uploads arrive as files: PHP's upload error codes (a partial upload, a missing temporary folder) do not exist.
- HEIC and HEIF images are stored with their file size only (no dimensions, no sizes), whatever image processor is
  installed: their dimensions are not read from their header, and no image processor module decodes them yet. PDFs
  get no preview image, as in WordPress without Imagick.
- The block editor's `allowedMimeTypes` is not narrowed to what the image processor can handle, as WordPress does
  not narrow it; the module that serves the editor's settings gives it the user's upload types.
- On a network, the quota is checked per upload against the space the site's committed attachments take: concurrent
  uploads and the sizes made after an upload can take the site beyond it, as in WordPress.
- An upload is refused when its guid URL would be longer than the 255 characters a guid holds.

## Decisions

- Files are served with `X-Content-Type-Options: nosniff` only, as WordPress's web server serves them: HTML uploads
  need `unfiltered_html` and SVG is off by default, so no `Content-Security-Policy` or `Content-Disposition` is added.
- Malformed network options: a `fileupload_maxk` with no leading number refuses every non-empty file (WordPress fails
  with an error); a fractional `blog_upload_space` is floored to whole bytes; an `upload_filetypes` that is null or an
  array counts as unset, so WordPress's fallback list `jpg jpeg png gif` applies.
- A crash between storing a file and committing its attachment leaves that file (and any temporary file) behind. It is
  not counted in the site's used space, and later uploads number their names around it. So does a transaction whose
  outcome is unknown (the connection was lost while it committed): its files are kept and a warning is logged, since a
  file no attachment names is better than an attachment naming a file that is gone.
- Patterns added through `quarkpress.media.allowed-types` take part in the type check, but not in file-name cleaning's
  rule that renames a name that is only an extension to `unnamed-file.<ext>`, which uses WordPress's list of types.
- Configured upload types without a place in WordPress's list come after it sorted by pattern, so the lookup order is
  the same from one start to the next (the configuration hands its map over without an order).
- An image's dimensions are read from its header, never from the image processor, as WordPress reads them without an
  editor; `image_meta` is read only for images whose dimensions are read.
- A MIME filter of `MediaQuery` holds at most 1,000 values (comma pieces included); more are refused with
  `IllegalArgumentException`, so a query binds a bounded number of parameters.
- Used space is the sum of the stored sizes of every file of the site's attachments, kept with each media row (D5).

## Differences from WordPress

The media parity run (`MediaParityDbTest` in `integration-tests/media`) knows these differences by label; its known
differences file names the scenarios that show each, or says why none can. The table covers the media, filesystem
storage and ImageIO modules together.

| Label | Difference |
|---|---|
| D1 | An existing file is never overwritten: where WordPress's sub-size listing check moves a name onto an existing non-JPEG file and writes over it, QuarkPress takes the next number. The numbering replaces only the name's end (WordPress replaces every occurrence of the extension). |
| D2 | BMP images get sub-sizes in BMP, as WordPress with Imagick; WordPress with GD refuses BMP uploads that declare `image/bmp`. |
| D3 | Sub-sizes and `-scaled`/`-rotated` files keep an RGB or grey colour profile (in JPEG and PNG output) and drop EXIF, IPTC and XMP (GD drops all four; Imagick keeps all four). Pixels, resampling and the byte sizes of files QuarkPress writes differ from WordPress's. |
| D4 | Audio and video metadata holds `filesize`, `mime_type`, `length`, `length_formatted` and, for video, `width` and `height` when the container gives them: `length` only for MP4, MOV and M4A, AVI and WAV; MP3, OGG, WebM, FLAC and other formats get `filesize` and `mime_type` only; no tags, bit rates, codecs or `created_timestamp`, and an embedded cover image does not become an attachment. `mime_type` is the attachment's type, where WordPress stores getID3's (an MP4 can read `video/quicktime`, an M4A `audio/mp4`). |
| D5 | A site's used space is the sum of its attachments' stored file sizes, kept with each media row; WordPress walks the site's upload folder and sums the sizes of the files in it. The files live in a storage backend, which need not be a folder that can be walked. |
| D6 | The options `upload_path` and `upload_url_path`, where WordPress places the files and their URLs, are not read; `quarkpress.media.fs.base-url` (or the S3 backend's `quarkpress.media.s3.public-base-url`) takes their place, since the storage backend decides where files live. Refusal messages name `wp-content/uploads` (WordPress names its real folder, which a backend does not have), and a folder the storage cannot list is refused with WordPress's "Unable to create directory …" message. |
| D7 | File names lose accents by the default locale's rules whatever the site's locale (`ß` → `s`), where WordPress applies the site's locale (its German rules give `ss`); one fixed rule set cleans a name the same way on every site. |
| D8 | Content types come from Apache Tika read from the content alone, where WordPress uses libmagic; where the two disagree for a file, the accept-or-refuse outcome can differ (each case is listed in the parity known differences). Image detection is Tika's too: an OS/2 BMP and a `.tif` holding a camera raw (CR2, DNG) are refused, where WordPress's image detector accepts them. |
| D9 | Files are deleted after the transaction that deletes the attachment commits, and a storage error is logged; `Media.delete` deletes for good, never into the trash (WordPress's `MEDIA_TRASH` off). |
| D10 | Only the REST upload rules exist: WordPress's admin upload path, with its title from the sanitized name, its audio description and its "last refusal wins" multisite order, is not reproduced, as QuarkPress has no admin upload screens. |
| D11 | `quarkpress.media.max-upload-size` refuses with "This file is too big. Files must be less than {n} KB in size."; WordPress leaves the limit to PHP. |
| D13 | Encode quality is fixed at 82 and the output format mapping is configuration only; WordPress lets filters choose them per file and per size. QuarkPress has no per-file hooks for them. |
| D14 | Images above `quarkpress.media.max-image-pixels` are stored without sizes; WordPress tries and may exhaust its memory. |
| D16 | WebP and AVIF uploads that declare their type are refused unless `quarkpress.media.accept-unprocessable-images` is set (ImageIO cannot write them); WordPress with a GD or Imagick build that writes them accepts them and makes WebP/AVIF sub-sizes (until the libvips module). |
| D19 | `image_meta` is read within bounds, so reading an untrusted upload takes bounded memory and time: an XMP packet holding a DOCTYPE, or nested deeper than 256 elements, gives an empty `alt` (the depth is libxml2's default limit, which PHP's DOM is taken to keep); only the first 1 MiB of a file is searched for XMP and at most 1,000 IPTC keywords are kept; only the first 8 MiB of a JPEG are read for its segments; dates are read only in EXIF's `YYYY:MM:DD HH:MM:SS` form and IPTC's date and time forms; EXIF comes from the first Exif APP1 segment's IFD0 and SubIFD only, with its embedded documents (XMP, IPTC, Photoshop, ICC, maker notes) skipped; IPTC only from the first Photoshop APP13 segment's `8BIM` resources; an ICO's dimensions are its first entry's. WordPress scans the whole file for XMP, keeps every keyword, reads the whole JPEG, takes the segments PHP's readers find and parses other date forms too. |
| D20 | `Media.process` also makes again a `-scaled`, `-rotated` or converted main-file copy that an earlier run failed to make (a `partial` image without an original), so a copy a failed run could not make is not lost for good; WordPress's post-process makes only the missing sizes. |
| D21 | Deleting an attachment skips a size, backup or other derived file whose name holds a `/`, and an attachment of the main site whose main file is under `sites/<n>/` keeps every file; WordPress deletes such files when they stay inside the uploads folder. A derived name is placed only as a single name in the main file's folder, and a main-site file under `sites/` belongs to another site's uploads. |
| D22 | Attachments without a media row (created through `Posts.create`, or before the media module was installed) are left out of `Media.query`'s items and counts, since a library item needs its file data; WordPress lists every attachment. |
| D23 | HEIC conversion and PDF previews behave as WordPress with GD as its image editor: a HEIC or HEIF upload keeps its file size only and a PDF gets no preview image. WordPress with Imagick reads HEIC images, makes their sizes (as JPEG by default, which is why `output-formats` maps HEIC and HEIF to JPEG) and renders a PDF's first page as its preview sizes. No image processor module decodes HEIC or renders PDFs yet. |

Labels D12, D15, D17 and D18 are not used: the differences they were kept for turned out not to exist once WordPress's
answers were recorded (WordPress keeps an AVIF's header dimensions, refuses a real JPEG named `.webp` when WebP is not
allowed, and drops the MIME filter for a whitespace-only value, as QuarkPress does), or no longer exist (library
searches match the attached file's path, as WordPress's do). The labels are not renumbered.
