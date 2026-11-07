# QuarkPress Media

WordPress media for QuarkPress: uploads checked with WordPress's file name, type and size rules, stored through a
storage backend module, image sizes generated through an image processor module, and the media library of a site as
attachments, with a per-site switch (`media`). It requires the posts module and works on a single site and in a
network. A storage backend is needed: add `quarkpress-media-storage-fs`, or provide a `MediaStorage` bean.

## Configuration

To be written.

## Differences from WordPress

The media parity run (`MediaParityDbTest` in `integration-tests/media`) knows these differences by label; its known
differences file names the scenarios that show each.

| Label | Difference |
|---|---|
| D1 | An existing file is never overwritten: where WordPress's sub-size listing check moves a name onto an existing non-JPEG file and writes over it, QuarkPress takes the next number. The numbering replaces only the name's end (WordPress replaces every occurrence of the extension). |
| D2 | BMP images get sub-sizes in BMP, as WordPress with Imagick; WordPress with GD refuses BMP uploads that declare `image/bmp`. |
| D4 | Audio and video metadata holds `filesize`, `mime_type`, `length`, `length_formatted` and, for video, `width` and `height` when the container gives them: `length` only for MP4, MOV and M4A, AVI and WAV; MP3, OGG, WebM, FLAC and other formats get `filesize` and `mime_type` only; no tags, bit rates, codecs or `created_timestamp`, and an embedded cover image does not become an attachment. `mime_type` is the attachment's type, where WordPress stores getID3's (an MP4 can read `video/quicktime`, an M4A `audio/mp4`). |
| D16 | WebP and AVIF uploads that declare their type are refused unless `quarkpress.media.accept-unprocessable-images` is set (ImageIO cannot write them); WordPress with a GD or Imagick build that writes them accepts them and makes WebP/AVIF sub-sizes (until the libvips module). |
