# QuarkPress Media Image ImageIO

An image processor for QuarkPress media: it reads, orients, crops, scales and writes images with the JDK's ImageIO and
the TwelveMonkeys JPEG and WebP plugins, in the JVM and in a native image, with no native library to install beyond
what quarkus-awt needs. It requires the media module.
