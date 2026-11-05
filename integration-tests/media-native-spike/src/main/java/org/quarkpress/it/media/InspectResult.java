package org.quarkpress.it.media;

/**
 * What the native executable found in an uploaded image.
 *
 * @param mimeType Tika's default detector, by magic bytes only (no file name given)
 * @param readerFormat the first ImageIO reader's format name
 * @param readerClass that reader's class, to tell TwelveMonkeys from the JDK's own readers
 * @param width decoded width
 * @param height decoded height
 * @param jpegComponents a JPEG's colour component count from its SOF segment (3 for YCbCr, 4 for CMYK), read by
 *     metadata-extractor; null for other formats
 * @param topLeftRgb the decoded top-left pixel as {@code rrggbb}
 * @param exifOrientation EXIF IFD0 Orientation read by metadata-extractor, or null
 * @param iptcCaption IPTC 2:120 caption read by metadata-extractor, or null
 */
public record InspectResult(
        String mimeType,
        String readerFormat,
        String readerClass,
        int width,
        int height,
        Integer jpegComponents,
        String topLeftRgb,
        Integer exifOrientation,
        String iptcCaption) {}
