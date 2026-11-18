# QuarkPress Media Image Vips

An image processor for QuarkPress media built on [libvips](https://www.libvips.org/), through the
[quarkus-libvips](https://github.com/SiteNetSoft/quarkus-libvips) extension: it reads, turns, crops, scales and writes
images with the native library, as WordPress does with Imagick when that is installed. It makes JPEG, PNG, WebP, AVIF
and GIF sizes, reads HEIC, and leaves the types libvips does not handle (BMP) to the ImageIO module when that is
installed. It requires the media module and Java 25.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-media-image-vips</artifactId>
</dependency>
<!-- optional: BMP sizes, and every image when libvips is missing (see below) -->
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-media-image-imageio</artifactId>
</dependency>
```

- Java 25 only: quarkus-libvips calls libvips through the Foreign Function and Memory API. QuarkPress builds this module
  (and `integration-tests/media-vips`) on JDK 25 and leaves both out of a Java 21 build, or of any build given
  `-Dquarkpress.skip-vips`.
- quarkus-libvips is not on Maven Central yet. Install it once from
  [SiteNetSoft/quarkus-libvips](https://github.com/SiteNetSoft/quarkus-libvips), at the commit named by
  `quarkus-libvips.commit` in QuarkPress's root `pom.xml`: clone it, check out that commit and run,
  with JDK 25, `mvn install -DskipTests -pl runtime,deployment -am`. The version QuarkPress uses is
  `quarkus-libvips.version` in the same file.
- libvips 8.15 or later on every host that runs the application (and on the build host, for the tests). On Debian 13,
  Ubuntu 24.04 and later:

  ```bash
  apt install libvips42t64 libheif-plugin-libde265 libheif-plugin-aomenc
  ```

  - `libheif-plugin-libde265` is libheif's HEVC decoder. Ubuntu 24.04 only suggests it, so a default install lacks it,
    and libvips then decodes HEIC as black pixels without an error. The module tries a small HEIC at start-up: without
    the decoder it does not read HEIC (uploads keep their file size only, see Formats), and the start-up line says
    `image/heic is not read: install libheif-plugin-libde265`.
  - `libheif-plugin-aomenc` is the AV1 encoder that writes AVIF; it is only recommended. Without it AVIF is not
    handled and the start-up line says `image/avif is not written: install libheif-plugin-aomenc`.
  - Other systems: see quarkus-libvips's installation guide.

The extension brings `quarkpress-media` and `quarkus-libvips`; libvips's own settings are quarkus-libvips's
`quarkus.libvips.*` keys, and this module adds no key of its own. Everything in
`org.quarkpress.media.image.vips.runtime` is implementation and may change.

## Formats

What the installed libvips handles depends on how it was built and on the codec plugins next to it, so the module
tries the types at start-up rather than trusting a list: a small image is saved and read back in each written type, a
bundled HEIC is decoded (and must not come out black), and TIFF's loader and saver are looked up:

| Type | With libvips |
|---|---|
| JPEG, PNG, WebP, AVIF, GIF | read and written: their sizes are of their own type. AVIF needs `libheif-plugin-aomenc`; GIF needs a libvips built with cgif (Ubuntu 24.04's is). |
| HEIC, HEIF | read only, with `libheif-plugin-libde265`: the main file becomes a JPEG copy (`photo.jpg`, or `photo-scaled.jpg` above `big-image-threshold`), the upload is kept as the original, the sizes are JPEG and the attachment's type stays `image/heic`, as WordPress with Imagick stores them. |
| TIFF | accepted and stored with its file size only, no sizes, as WordPress with Imagick stores it |
| BMP | not decoded by libvips: the ImageIO module decodes it, and its sizes are JPEG, as WordPress with Imagick writes them. Without ImageIO, BMP uploads are refused as any type no processor handles. |
| HEIC and HEIF sequences | not handled: refused as WordPress refuses them |

- One line at start-up says what was found, for example `libvips 8.15.1: sub-sizes in image/jpeg, image/png,
  image/webp, image/avif, image/gif; reads image/heic, image/heif; image/tiff accepted; other types by
  ImageIoProcessor`, with the package to install for AVIF or HEIC when either is missing.
- libvips picks its loader by the file's content, never by its name or declared type.
- A JPEG whose data stops early is decoded as far as it goes and gets sizes, as WordPress makes them with either of its
  editors. Every other type is decoded strictly: a PNG or GIF whose data stops early fails to decode and is kept
  without sizes, as WordPress with Imagick keeps it. A failure of libvips is never retried with ImageIO.
- An animated GIF or WebP gives its first frame: every size is one still frame.
- Images are never turned on decoding: the media module turns a JPEG upright from its EXIF orientation, as WordPress
  turns only JPEG. A HEIC's own rotation (`irot`) is applied by libheif, so its dimensions and sizes are those
  displayed, as with Imagick.
- CMYK images are converted to sRGB.

## Precedence and fallback

- With the module installed, its processor takes precedence over ImageIO's: there is nothing to configure.
- `quarkpress.media.image-processor` (media module, build time) chooses when several processor modules are
  installed: `vips` (the default when this module is present) or `imageio`. A name no installed module provides fails
  the build, naming the installed ones.
- `image-processor=imageio` leaves this processor out of the application, but quarkus-libvips still loads libvips at
  start-up and, by default, fails the start when libvips is missing. Then also set `quarkus.libvips.enabled=false`
  (build time), so libvips is not loaded at all, or `quarkus.libvips.required=false`.
- The other processor (ImageIO's, when installed) is used:
  - per type, for the types libvips does not decode (BMP);
  - for every type, when libvips cannot be loaded and `quarkus.libvips.required=false`: the start logs a warning, and
    images are processed as with ImageIO alone (WebP and AVIF uploads are then refused, see the media README, D16).
    Without ImageIO the warning says images get no generated sizes, and image uploads are refused as WordPress
    refuses them without an image editor.
- The other processor is the application's other `ImageProcessor` bean, by class name when there are several. A bean of
  the application's own is only found when it is kept (`@Unremovable`): nothing injects it, since this processor takes
  precedence.
- While libvips is loaded, sizes are only written in the types libvips writes, so the sizes of an image ImageIO decodes
  are JPEG. ImageIO encodes those sizes itself and writes neither WebP nor AVIF, so a `quarkpress.media.output-formats`
  mapping of such a type (BMP) to WebP or AVIF stores that image without sizes. Map it to JPEG, PNG or GIF, or leave it
  unmapped.

## Writing

| Output | Written |
|---|---|
| JPEG | baseline, at the quality the media module asks; alpha is dropped |
| PNG | compression level 9, with its alpha |
| WebP | at the quality asked, or lossless |
| AVIF | at the quality asked, encoder speed 7 (as Imagick sets it) |
| GIF | one frame |

- The media module asks WordPress's default qualities: 86 for WebP and 82 for every other type. Every copy of a
  lossless WebP is written at 100, and lossless when it is a WebP, as WordPress's Imagick editor writes it (its GD
  editor asks 101, GD's own value for lossless WebP).
- Metadata kept: the colour profile in JPEG, WebP and AVIF copies. PNG copies keep no metadata, not even the profile,
  as both of WordPress's editors write them. EXIF, IPTC and XMP are never written, where WordPress with Imagick keeps
  them in JPEG sizes (media README, D3).
- An indexed (palette) PNG's sizes are written in truecolour; WordPress with Imagick writes a palette again.

## Threads

libvips calls block. The media module calls the processor on worker threads: a REST upload or post-process on the
request's worker thread, the `async` mode's runs on the worker pool that runs asynchronous observers, and the start-up
resume of pending attachments on a virtual thread (a libvips call holds its carrier thread for its whole length; the
media module's `max-concurrent-decodes` bounds how many run at once). An application that calls `Media.upload` or
`Media.process` itself does so from a worker or virtual thread (`@Blocking`): on a Vert.x event loop the processor
throws Quarkus's `BlockingOperationNotAllowedException`, rather than failing as an image it cannot decode.

## Limits and settings

- Input limits: quarkus-libvips refuses inputs above `quarkus.libvips.limits.max-input-size` (128M by default) and
  `quarkus.libvips.limits.max-input-pixels` (268402689). Left unset, they rise to what QuarkPress accepts:
  `max-input-size` to the larger of 128M and the largest upload (`quarkpress.media.max-upload-size`, else
  `quarkus.http.limits.max-body-size`, else 10240K), `max-input-pixels` to the larger of its default and
  `quarkpress.media.max-image-pixels`. Set lower (other than `0`, no limit), they fail the start with
  `quarkus.libvips.limits.max-input-size (…) is below the largest upload QuarkPress accepts (…): raise it or set it to 0`
  (and the same for the pixels). With `max-upload-size` unset, a file uploaded through the Java API with no size limit
  can still be larger than libvips's limit: it is stored with its dimensions and without sizes.
- `quarkus.libvips.concurrency`: libvips's worker threads per image, by default `VIPS_CONCURRENCY` or one per
  processor. On an upload server, 2 to 4 leave room for the requests that decode at the same time
  (`quarkpress.media.max-concurrent-decodes`).
- Memory and temporary files: a decoded image is held outside the Java heap. An image larger than libvips's
  `VIPS_DISC_THRESHOLD` (an environment variable, 100 MB of pixels by default, about 33 million RGB pixels) is decoded
  to a temporary file in `TMPDIR` instead, and the media module's own copies go to `java.io.tmpdir`. Where that folder
  is a tmpfs, those files are memory too: point `TMPDIR` at a disc folder, or plan the memory for it.
- Decoding ignores `quarkus.libvips.fail-on`: the processor sets it per type (see Formats).
- quarkus-libvips blocks the loaders libvips marks untrusted. Its matrix loader is not marked, and an upload never
  needs it: block it with `quarkus.libvips.security.blocked-operations=VipsForeignLoadMatrix`.
- Keep libheif up to date: it parses HEIC and AVIF uploads. libvips 8.18.6 and later mark their HEIF loader untrusted
  when built against a libheif older than 1.23.2, so quarkus-libvips blocks it; then neither HEIC nor AVIF is handled
  unless you allow it with `quarkus.libvips.security.allowed-operations=VipsForeignLoadHeif`.

## Native images

- A native executable is built with a Java 25 builder (Mandrel for JDK 25, for example
  `quay.io/quarkus/ubi9-quarkus-mandrel-builder-image:jdk-25`). libvips is never in the executable: it opens the host's
  `libvips.so.42` at start-up, so install libvips and the libheif plugins on the running host as above.
- The module registers the images of its start-up probe as resources of the native image.
- With ImageIO as the fallback, ImageIO's own native requirements apply (see its README).
- `integration-tests/media-vips` (libvips with ImageIO as its fallback, behind WordPress's REST API) is built and
  tested as a native executable in CI.
