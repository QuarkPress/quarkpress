# QuarkPress Media Storage FS

A storage backend for QuarkPress media: it keeps the uploaded files in a directory on the server
(`quarkpress.media.fs.directory`) and serves them under WordPress's uploads path (`/wp-content/uploads` by default),
or publishes their URLs under a configured base URL such as a CDN's. It requires the media module.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-media-storage-fs</artifactId>
</dependency>
```

The extension brings `quarkpress-media` and `quarkus-vertx-http`. Its bean is the media module's `MediaStorage` named
`fs` (`@Identifier("fs")`); with another backend present, `quarkpress.media.storage=fs` chooses it (see the media
README). Everything in `org.quarkpress.media.storage.fs.runtime` is implementation and may change.

## Configuration

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.media.fs.directory` | none | The directory that holds the files. Required when this backend is the one in use: without it the start fails ("quarkpress.media.fs.directory is required by quarkpress-media-storage-fs"). It is created on the first write when missing. |
| `quarkpress.media.fs.base-url` | unset | The URL the files are published under, such as a CDN's. Set, the application does not serve the files itself. |
| `quarkpress.media.fs.path` | `/wp-content/uploads` | The URL path the files are served under, after the site's address. Without a base URL it must have a segment: `/` fails the start ("quarkpress.media.fs.path must name a path below the site's root, such as /wp-content/uploads: /"), since the route would take every request. |

When another backend is the one in use, the directory is not required, nothing is checked at start-up and no route is
registered.

## Files and URLs

A key is a path inside the directory: the main site's `2026/11/photo.jpg` is `<directory>/2026/11/photo.jpg`, another
site's files are under `<directory>/sites/<id>/`. A file's URL is the key appended to the base URL (its trailing
slashes removed) when one is configured, else to the site's address on `http` (the site record stores no scheme) and
the path: `http://example.com/blog/wp-content/uploads/2026/11/photo.jpg`. The key's characters are used as they are,
never percent-encoded. An attachment's guid holds this URL as it was at upload time and never changes, so a change of
the base URL or the path changes the URLs the media module answers, not the guids already stored.

## Serving

Without a base URL, the application serves the files on `GET` and `HEAD`, as WordPress's web server serves its uploads
folder:

- A request path is served when its segments, after a site path of up to four segments (`/blog/wp-content/uploads/…`
  for a site in a subdirectory), start with the configured path's segments; any other request goes on to the
  application's other routes. The rest of the path is the key, taken from the raw request path and percent-decoded
  once as UTF-8.
- A malformed escape, an invalid key, a key with a segment starting with `.` (so never the temporary folder), a folder
  (no listings), a symbolic link anywhere on the key's path (to a file or a folder, inside the directory or out of it)
  and a missing file all answer 404.
- The file served is the one checked: each folder of the key is opened relative to the one before it and the file
  relative to its folder, never following a link, and the answer is read from that open file, never reopened by name.
  Where the file system cannot open relative to a folder (the JDK offers it on Linux and other POSIX systems), each
  segment is checked first and the file opened after: a link put in a folder's place in between, by someone who can
  write to the directory, would be followed. On either way a name replaced between the check and the opening (by
  another regular file below the directory) serves that file with the `Last-Modified` of the one checked; replaced by a
  named pipe or another special file, which also needs write access to the directory, the opening waits on a worker
  thread until something writes to it.
- The content type is the one WordPress's upload type list gives the file's extension, else
  `application/octet-stream`, with `X-Content-Type-Options: nosniff`. `Last-Modified` is sent and `If-Modified-Since`
  answers 304; a single byte range is served (206, or 416 when no byte of the file satisfies it), and `If-Range`
  is honoured.
- The route serves files whatever a site's `media` feature says: they are public URLs, as WordPress serves its uploads
  without PHP. It does not check that the site path names a site.

## Writing files

- A file is first written to a temporary file in `<directory>/.tmp/` (on the same file system as the stored files,
  created readable by its owner only, out of every key's reach and never listed or served), given the stored files'
  permissions, then put in place in one step.
- A new file is put in place by a hard link, which fails when the name is taken, so a file is never replaced by a
  concurrent writer of the same name. Where a hard link cannot be made (a file system without them, or any failure but
  a taken name), an exclusively created empty file reserves the name and the temporary file is moved over it: this way
  never replaces a file either, but a reader may see the empty file for a moment, and a crash in between leaves it
  behind, keeping the name taken (uploads then choose another name). A replacing write moves the temporary file over
  the old one, so a reader sees the old file or the new one, never a part.
- Stored files are readable by all and writable by their owner (0644), new folders 0755, whatever the process's umask
  (on a POSIX file system).
- At start-up, temporary files older than an hour (left by an interrupted write) are removed.
- No key reaches outside the directory, neither through its segments nor through a symbolic link inside it, and the
  last segment of a key is never followed when it is a symbolic link: a link is no stored file.
- A folder that cannot be read refuses an upload with WordPress's "Unable to create directory …" message (see the
  media README, D6).

## Decisions

- Files are served with `X-Content-Type-Options: nosniff` only, as WordPress's web server serves them: HTML uploads
  need `unfiltered_html` and SVG is off by default, so no `Content-Security-Policy` or `Content-Disposition` is added.
- WordPress's `upload_path` and `upload_url_path` options are not read; `quarkpress.media.fs.directory` and
  `quarkpress.media.fs.base-url` take their place (media README, D6). The differences of this module are in the media
  README's table, which covers the media modules together.
