package org.quarkpress.media.image.imageio.runtime;

import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Optional;
import java.util.zip.DataFormatException;
import java.util.zip.Deflater;
import java.util.zip.Inflater;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

/** The ICC colour profile embedded in an image, as ImageIO's JPEG and PNG metadata hold it. */
final class IccProfiles {

    /** The JPEG metadata node of an APP2 {@code ICC_PROFILE} segment; its user object is the profile. */
    static final String JPEG_NODE = "app2ICC";

    /**
     * The PNG metadata node of the {@code iCCP} chunk; the JDK's reader and writer hand its user object over as the
     * chunk holds it, zlib-compressed.
     */
    static final String PNG_NODE = "iCCP";

    /** An ICC profile's header; the bytes after it describe the colours. */
    private static final int HEADER_LENGTH = 128;

    /** At most this many bytes are inflated from a compressed profile; a larger one is ignored. */
    static final int MAX_PROFILE_BYTES = 4 * 1024 * 1024;

    private IccProfiles() {}

    /**
     * The profile of the image whose metadata is {@code metadata}: the JPEG APP2 profile or the PNG {@code iCCP} one;
     * empty when there is none or it cannot be parsed.
     */
    static Optional<ICC_Profile> of(IIOMetadata metadata) {
        if (metadata == null) {
            return Optional.empty();
        }
        String format = metadata.getNativeMetadataFormatName();
        if (format == null) {
            return Optional.empty();
        }
        try {
            Node tree = metadata.getAsTree(format);
            if (!(tree instanceof IIOMetadataNode root)) {
                return Optional.empty();
            }
            Optional<ICC_Profile> jpeg = first(root.getElementsByTagName(JPEG_NODE));
            return jpeg.isPresent() ? jpeg : first(root.getElementsByTagName(PNG_NODE));
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    /** Whether the profile describes RGB or grey colours, the two kinds an output keeps unconverted. */
    static boolean keepable(ICC_Profile profile) {
        int type = profile.getColorSpaceType();
        return type == ColorSpace.TYPE_RGB || type == ColorSpace.TYPE_GRAY;
    }

    /**
     * Whether two profiles describe the same colours: equal bytes, or equal bytes after the header (a reader may
     * rewrite header fields such as the rendering intent).
     */
    static boolean sameColours(ICC_Profile a, ICC_Profile b) {
        byte[] x = a.getData();
        byte[] y = b.getData();
        if (Arrays.equals(x, y)) {
            return true;
        }
        return x.length == y.length
                && x.length > HEADER_LENGTH
                && Arrays.equals(x, HEADER_LENGTH, x.length, y, HEADER_LENGTH, y.length);
    }

    private static Optional<ICC_Profile> first(NodeList nodes) {
        if (nodes.getLength() == 0 || !(nodes.item(0) instanceof IIOMetadataNode node)) {
            return Optional.empty();
        }
        Object value = node.getUserObject();
        if (value instanceof ICC_Profile profile) {
            return Optional.of(profile);
        }
        if (value instanceof byte[] bytes) {
            Optional<ICC_Profile> inflated = inflate(bytes).flatMap(IccProfiles::parse);
            return inflated.isPresent() || bytes.length > MAX_PROFILE_BYTES ? inflated : parse(bytes);
        }
        return Optional.empty();
    }

    private static Optional<ICC_Profile> parse(byte[] bytes) {
        try {
            return Optional.of(ICC_Profile.getInstance(bytes));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /** The zlib data of {@code bytes}, as an {@code iCCP} chunk holds a profile. */
    static byte[] deflate(byte[] bytes) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(bytes);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length / 2 + 64);
            byte[] buffer = new byte[8192];
            while (!deflater.finished()) {
                out.write(buffer, 0, deflater.deflate(buffer));
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * The zlib-inflated {@code bytes}; empty when they are not zlib data or inflate to more than
     * {@link #MAX_PROFILE_BYTES} (a compressed chunk of a few kilobytes can claim gigabytes).
     */
    private static Optional<byte[]> inflate(byte[] bytes) {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(bytes);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(bytes.length, MAX_PROFILE_BYTES / 4) * 4);
            byte[] buffer = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buffer);
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) {
                    return Optional.empty();
                }
                out.write(buffer, 0, n);
                if (out.size() > MAX_PROFILE_BYTES) {
                    return Optional.empty();
                }
            }
            return Optional.of(out.toByteArray());
        } catch (DataFormatException e) {
            return Optional.empty();
        } finally {
            inflater.end();
        }
    }
}
