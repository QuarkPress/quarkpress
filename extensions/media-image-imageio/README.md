# QuarkWord Media Image ImageIO

An image processor for QuarkWord media: it reads, orients, crops, scales and writes images with the JDK's ImageIO and
the TwelveMonkeys JPEG and WebP plugins, in the JVM and in a native image, with no native library to install beyond
what quarkus-awt needs. It requires the media module.

## Installation

```xml
<dependency>
  <groupId>org.quarkword</groupId>
  <artifactId>quarkword-media-image-imageio</artifactId>
</dependency>
```

The extension brings `quarkword-media`, `quarkus-awt` and TwelveMonkeys' `imageio-jpeg` and `imageio-webp`. Its bean
replaces the media module's default processor, which handles no type. Everything in
`org.quarkword.media.image.imageio.runtime` is implementation and may change; there is nothing to configure beyond
the media module's keys.

With the libvips module (`quarkword-media-image-vips`, Java 25) installed, ImageIO handles only the types libvips does
not decode (BMP), and takes over every type when libvips is missing and `quarkus.libvips.required` is false;
`quarkword.media.image-processor=imageio` makes it the processor again (see the libvips module's README).

## Formats

- Read and written: JPEG, PNG and GIF. Read, with sizes written in JPEG (as WordPress with Imagick writes them): BMP.
  These are the types it `supports`, so the media module makes sizes for them and accepts their uploads.
- Read only: WebP (TwelveMonkeys) and TIFF (the JDK's reader). The media module never decodes them, since the
  processor cannot write them: WebP, AVIF and TIFF uploads that declare their type are refused unless
  `quarkword.media.accept-unprocessable-images` is set, then stored without sizes. For TIFF that is what WordPress
  with GD does; for WebP and AVIF see the media README, D16.
- The reader is chosen by the file's content, never by its name or declared type. A file a reader reports as damaged
  (a truncated file) fails to decode, and the media module keeps it without sizes. For a PNG, and with Imagick a GIF,
  that is what WordPress does; a JPEG whose data stops early is a known difference of this processor, since WordPress
  makes its sizes with either editor, decoding as far as the data goes (the libvips module does too).
- An animated GIF gives its first frame, drawn at its place on a transparent canvas of the GIF's logical screen.

## Writing

- JPEG is written baseline (never progressive) at the quality the media module asks (82), clamped to 1–100, with its
  own JFIF segment; PNG losslessly, with its alpha; GIF with one transparent colour. BMP is never written.
- Scaling is bilinear, pixel centres onto pixel centres, colours weighted by alpha so that transparent pixels lend no
  colour to their neighbours; cutting and turning move samples only.
- Metadata kept: an RGB or grey ICC colour profile, in JPEG (APP2) output. The pixels stay in that profile's colour
  space, never converted, so the kept profile still describes them. PNG output keeps the same pixels and no profile,
  as WordPress's editors write PNG copies. Any other image (CMYK, for one) is converted to sRGB and written without a
  profile, and GIF output is sRGB. EXIF, IPTC and XMP are never written (media README, D3).

## Memory

A decoded image is held as 8-bit samples, up to 4 bytes per pixel. For a run's whole length, while its sizes are
made, it holds the decoded original and its upright copy (about 8 bytes per pixel); while an image is decoded or a size
is cut and encoded, further rasters exist for a moment, so processing one image needs about 2–3× its decoded raster
(width × height × 4 bytes, so 8–12 bytes per pixel) of heap at peak. `quarkword.media.max-image-pixels`
bounds the largest raster: the media module checks it from the file's header before any decode, and this processor
checks it again from the reader's dimensions (for a GIF, the logical screen's too) before reading any pixel. It also
refuses an image with a side longer than 65535 pixels, whatever the pixel limit, since the row buffers of a decode and
of scaling grow with one side alone: its decode fails, and the media module keeps it without sizes. Plan the
heap for about 8 bytes × `max-image-pixels` × `max-concurrent-decodes` (the steady state of each run), plus the
sizes in flight and the peak of one decode (up to 12 bytes per pixel): with the defaults (100 million pixels, half
the processors) that is about 800 MB per concurrent decode, so lower either setting for a small heap.

## Native images

- quarkus-awt is a preview extension: ImageIO and Java2D run in a native image on Linux x64 only (not macOS, not
  aarch64).
- The native executable loads the AWT shared libraries copied next to it, which need FreeType and fontconfig: install
  `libfreetype6` and `fontconfig` (or your distribution's `freetype` and `fontconfig`) in the runtime image.
- The deployment module registers TwelveMonkeys' service providers and colour profile resources, initialises its
  packages at run time and registers `java.awt.image.ColorConvertOp` for reflection; the media module registers
  metadata-extractor's classes and Tika's type database.
