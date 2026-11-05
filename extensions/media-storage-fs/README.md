# QuarkPress Media Storage FS

A storage backend for QuarkPress media: it keeps the uploaded files in a directory on the server
(`quarkpress.media.fs.directory`) and serves them under WordPress's uploads path (`/wp-content/uploads` by default),
or publishes their URLs under a configured base URL such as a CDN's. It requires the media module.
