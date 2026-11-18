# QuarkPress Media Image Vips

An image processor for QuarkPress media built on [libvips](https://www.libvips.org/), through the
[quarkus-libvips](https://github.com/SiteNetSoft/quarkus-libvips) extension: it reads, orients, crops, scales and
writes images with the native library, as WordPress does with Imagick when that is installed. It requires the media
module, Java 25 and libvips 8.15 or later on the host (on Debian and Ubuntu, `libvips42t64` with
`libheif-plugin-libde265` for HEIC and `libheif-plugin-aomenc` for AVIF).
