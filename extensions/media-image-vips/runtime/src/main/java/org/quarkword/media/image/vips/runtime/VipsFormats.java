package org.quarkword.media.image.vips.runtime;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jboss.logging.Logger;
import org.sitenetsoft.quarkus.libvips.Image;
import org.sitenetsoft.quarkus.libvips.Keep;
import org.sitenetsoft.quarkus.libvips.Libvips;
import org.sitenetsoft.quarkus.libvips.LibvipsException;
import org.sitenetsoft.quarkus.libvips.SaveOptions;
import org.sitenetsoft.quarkus.libvips.Version;

/**
 * The image types the installed libvips handles, as found by trying each at start-up rather than from a list: what
 * libvips can do depends on how it was built and on the codec plugins installed next to it.
 *
 * @param writable the types decoded and written: sub-sizes are made in these
 * @param readable the types only decoded; their sub-sizes are written in another type
 * @param acceptedOnly the types whose headers are read but that are never decoded
 */
public record VipsFormats(Set<String> writable, Set<String> readable, Set<String> acceptedOnly) {

    private static final Logger LOG = Logger.getLogger(VipsFormats.class);

    /** No type at all: libvips is unavailable or has not been probed yet. */
    public static final VipsFormats NONE = new VipsFormats(Set.of(), Set.of(), Set.of());

    /** The types that may be written, in the order the start-up line names them. */
    static final List<String> WRITE_ORDER = List.of("image/jpeg", "image/png", "image/webp", "image/avif", "image/gif");

    private static final List<String> READ_ORDER = List.of("image/heic", "image/heif");

    /**
     * Where the probe images are, both made by us with libvips: {@code probe.png}, 8&times;8 of one colour (RGB 40,
     * 120, 200), and {@code probe.heic}, 16&times;16 red, saved with {@code vips heifsave --compression hevc}.
     */
    private static final String RESOURCES = "/org/quarkword/media/image/vips/";

    public VipsFormats {
        writable = Set.copyOf(writable);
        readable = Set.copyOf(readable);
        acceptedOnly = Set.copyOf(acceptedOnly);
    }

    /** Whether {@code mimeType} is handled at all: written, read, or accepted without sizes. */
    public boolean supports(String mimeType) {
        return writable.contains(mimeType) || readable.contains(mimeType) || acceptedOnly.contains(mimeType);
    }

    /** Whether images of {@code mimeType} are decoded. */
    public boolean decodes(String mimeType) {
        return writable.contains(mimeType) || readable.contains(mimeType);
    }

    /** Whether images are written as {@code mimeType}. */
    public boolean writes(String mimeType) {
        return writable.contains(mimeType);
    }

    /**
     * The start-up line: what this libvips writes, reads and accepts, and the package that adds AVIF writing or HEIC
     * reading when either is missing.
     */
    public String describe(Version version) {
        StringBuilder line = new StringBuilder("libvips ").append(version).append(": sub-sizes in ");
        line.append(names(WRITE_ORDER, writable));
        List<String> read = READ_ORDER.stream().filter(readable::contains).toList();
        if (!read.isEmpty()) {
            line.append("; reads ").append(String.join(", ", read));
        }
        for (String type : acceptedOnly.stream().sorted().toList()) {
            line.append("; ").append(type).append(" accepted");
        }
        if (!writable.contains("image/avif")) {
            line.append("; image/avif is not written: install libheif-plugin-aomenc");
        }
        if (!readable.contains("image/heic")) {
            line.append("; image/heic is not read: install libheif-plugin-libde265");
        }
        return line.toString();
    }

    private static String names(List<String> order, Set<String> types) {
        List<String> names = order.stream().filter(types::contains).toList();
        return names.isEmpty() ? "nothing" : String.join(", ", names);
    }

    /**
     * Tries each type on {@code libvips}. A type is written when a small image saved in it loads back as that type at
     * its size and decodes. HEIC and HEIF are read when libvips's HEIF loader is present, not blocked, and decodes a
     * small HEVC image to its colours: without libheif's HEVC decoder plugin, libvips decodes HEIC as black pixels
     * without an error. TIFF is accepted without sizes when libvips reads and writes it. {@link #NONE} when libvips is
     * unavailable.
     */
    public static VipsFormats probe(Libvips libvips) {
        if (!libvips.isAvailable()) {
            return NONE;
        }
        Map<String, SaveOptions> savers = new LinkedHashMap<>();
        savers.put("image/jpeg", SaveOptions.jpeg(82));
        savers.put("image/png", SaveOptions.png());
        savers.put("image/webp", SaveOptions.webp(86));
        savers.put("image/avif", new SaveOptions.Avif(82, false, 2, Set.of(Keep.ICC)));
        savers.put("image/gif", SaveOptions.gif());
        Set<String> writable = new LinkedHashSet<>();
        try (Image source = libvips.load(resource("probe.png"))) {
            for (Map.Entry<String, SaveOptions> saver : savers.entrySet()) {
                if (roundTrips(libvips, source, saver.getKey(), saver.getValue())) {
                    writable.add(saver.getKey());
                }
            }
        } catch (LibvipsException failure) {
            LOG.debugf("libvips could not load its probe image: %s: %s", failure.getMessage(), failure.detail());
        }
        Set<String> readable = new LinkedHashSet<>();
        if (available(libvips, "heifload", "VipsForeignLoadHeifFile") && decodesHevc(libvips)) {
            readable.addAll(READ_ORDER);
        }
        Set<String> acceptedOnly = new LinkedHashSet<>();
        if (available(libvips, "tiffload", "VipsForeignLoadTiffFile") && libvips.hasOperation("tiffsave_buffer")) {
            acceptedOnly.add("image/tiff");
        }
        return new VipsFormats(writable, readable, acceptedOnly);
    }

    private static boolean available(Libvips libvips, String operation, String fileLoader) {
        return libvips.hasOperation(operation) && !libvips.isBlocked(fileLoader);
    }

    /** Saves {@code source} as {@code type}, loads it back and decodes it. */
    private static boolean roundTrips(Libvips libvips, Image source, String type, SaveOptions options) {
        try {
            byte[] saved = source.save(options);
            try (Image back = libvips.load(saved)) {
                return back.width() == source.width()
                        && back.height() == source.height()
                        && VipsTypes.mimeOf(back).equals(Optional.of(type))
                        && back.save(SaveOptions.png()).length > 0;
            }
        } catch (LibvipsException failure) {
            LOG.debugf("libvips does not write %s: %s: %s", type, failure.getMessage(), failure.detail());
            return false;
        }
    }

    /**
     * Whether the bundled solid red HEVC image decodes to something other than black: its pixels written as PNG differ
     * from the same image with every sample set to 0.
     */
    private static boolean decodesHevc(Libvips libvips) {
        try (Image heic = libvips.load(resource("probe.heic"))) {
            if (!VipsTypes.mimeOf(heic).equals(Optional.of("image/heic"))) {
                return false;
            }
            byte[] decoded = heic.save(SaveOptions.png());
            byte[] black;
            try (Image zero =
                    heic.call("linear", Map.of("a", new double[] {0}, "b", new double[] {0}, "uchar", Boolean.TRUE))) {
                black = zero.save(SaveOptions.png());
            }
            return !Arrays.equals(decoded, black);
        } catch (LibvipsException failure) {
            LOG.debugf("libvips does not read HEIC: %s: %s", failure.getMessage(), failure.detail());
            return false;
        }
    }

    private static byte[] resource(String name) {
        try (InputStream in = VipsFormats.class.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IllegalStateException("the resource " + RESOURCES + name + " is missing");
            }
            return in.readAllBytes();
        } catch (IOException failure) {
            throw new UncheckedIOException(failure);
        }
    }
}
