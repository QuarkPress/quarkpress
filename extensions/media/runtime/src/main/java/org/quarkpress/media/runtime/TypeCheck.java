package org.quarkpress.media.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * WordPress's check of an upload's type against its name and content.
 *
 * <ol>
 *   <li>The client's file name gives the extension and type (the first pattern of the site's whole type list
 *       holding the name's last extension, without regard to case); no type, no upload.
 *   <li>For an image type, the real image type is read from the content. When it differs from the name's type, or
 *       the extension is literally {@code heif}, {@code heics} or {@code heifs}, the name's last extension is
 *       replaced by the real type's ({@code photo.png} holding a JPEG becomes {@code photo.jpg}) and the type is
 *       looked up again for the new name.
 *   <li>Otherwise the content type (Tika, content only) must agree with the name's type: a non-specific container
 *       answer passes for application, audio and video types; an audio or video answer for a type of the same top
 *       level; plain text for the text types WordPress lists; CSV and RTF answers for their related types; any other
 *       answer only when it equals the name's type.
 *   <li>The final extension must be one the user may upload, with the same type: the user's narrowed list and the
 *       denied extensions apply to the name after any correction (a JPEG named {@code x.webp} where WebP is denied is
 *       accepted as {@code x.jpg}).
 * </ol>
 *
 * The type an accepted upload is stored as is {@link TypeResult#mimeType()}: the allowed list's type for the final
 * extension, never the type read from the content.
 */
public final class TypeCheck {

    /** The extension an image of each real type is renamed to. */
    private static final Map<String, String> IMAGE_EXTENSIONS = Map.ofEntries(
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/png", "png"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("image/tiff", "tif"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/avif", "avif"),
            Map.entry("image/heic", "heic"),
            Map.entry("image/heif", "heic"),
            Map.entry("image/heic-sequence", "heic"),
            Map.entry("image/heif-sequence", "heic"));

    /** Extensions always renamed by an image's real type, even when the types agree (compared with their case). */
    private static final Set<String> ALWAYS_RENAMED = Set.of("heif", "heics", "heifs");

    /** Content answers that name a container, not a format. */
    private static final Set<String> NON_SPECIFIC = Set.of(
            "application/octet-stream", "application/encrypted", "application/CDFV2-encrypted", "application/zip");

    /** Top-level types a non-specific answer passes for. */
    private static final Set<String> NON_SPECIFIC_TOP_LEVELS = Set.of("application", "video", "audio");

    /** The name types plain-text content passes for. */
    private static final Set<String> PLAIN_TEXT_TYPES =
            Set.of("text/plain", "text/csv", "application/csv", "text/richtext", "text/tsv", "text/vtt");

    /** The name types CSV content passes for. */
    private static final Set<String> CSV_TYPES = Set.of("text/csv", "text/plain", "application/csv");

    /** The name types RTF content passes for. */
    private static final Set<String> RTF_TYPES = Set.of("text/rtf", "text/plain", "application/rtf");

    private TypeCheck() {}

    /**
     * The outcome of the check.
     *
     * @param extension the final extension, as the (possibly corrected) name writes it
     * @param mimeType the type the upload is stored as: the allowed list's type for {@code extension}
     * @param properName the corrected file name, when the content renamed it
     */
    public record TypeResult(Optional<String> extension, Optional<String> mimeType, Optional<String> properName) {

        /** A refused upload. */
        public static final TypeResult REFUSED = new TypeResult(Optional.empty(), Optional.empty(), Optional.empty());

        /** Whether the type was refused (no extension or no type). */
        public boolean refused() {
            return extension.isEmpty() || mimeType.isEmpty();
        }
    }

    /**
     * Checks the type of an upload.
     *
     * @param file the uploaded bytes (a missing file leaves the content unchecked)
     * @param clientName the file name the client sent
     * @param fullMap the site's whole type list (pattern to MIME type, in lookup order), before any user, denial or
     *     network narrowing: the name and the corrected name are looked up in it
     * @param allowed the types the acting user may upload on the current site: the final extension must be one of
     *     them, with the same type
     * @return the extension, the stored type and any corrected name, or {@link TypeResult#REFUSED}
     * @throws IOException when the file cannot be read
     */
    public static TypeResult check(
            Path file, String clientName, List<Map.Entry<String, String>> fullMap, AllowedTypes allowed)
            throws IOException {
        AllowedTypes full = AllowedTypes.of(fullMap, true, List.of(), Optional.empty());
        Optional<AllowedTypes.Match> match = full.lookup(clientName);
        if (match.isEmpty()) {
            return TypeResult.REFUSED;
        }
        String extension = match.get().extension();
        String type = match.get().mimeType();
        Optional<String> properName = Optional.empty();
        if (Files.exists(file)) {
            byte[] prefix = TikaTypes.prefix(file);
            boolean realTypeSet = false;
            if (type.startsWith("image/")) {
                Optional<String> real = ImageSignatures.realType(prefix);
                if (real.isPresent() && (!real.get().equals(type) || ALWAYS_RENAMED.contains(extension))) {
                    // the real type is always one of the table's: the signatures name no other image type
                    String newName =
                            clientName.substring(0, clientName.lastIndexOf('.') + 1) + IMAGE_EXTENSIONS.get(real.get());
                    if (!newName.equals(clientName)) {
                        properName = Optional.of(newName);
                    }
                    Optional<AllowedTypes.Match> again = full.lookup(newName);
                    if (again.isEmpty()) {
                        return TypeResult.REFUSED;
                    }
                    extension = again.get().extension();
                    type = again.get().mimeType();
                }
                realTypeSet = real.isPresent();
            }
            if (!realTypeSet && !contentAgrees(TikaTypes.detect(prefix), type)) {
                return TypeResult.REFUSED;
            }
        }
        String finalType = type;
        boolean allowedFinal = allowed.lookup(properName.orElse(clientName))
                .filter(found -> found.mimeType().equals(finalType))
                .isPresent();
        if (!allowedFinal) {
            return TypeResult.REFUSED;
        }
        return new TypeResult(Optional.of(extension), Optional.of(type), properName);
    }

    /** Whether content of type {@code sniffed} may carry a name of type {@code type}. */
    static boolean contentAgrees(String sniffed, String type) {
        if (NON_SPECIFIC.contains(sniffed)) {
            return NON_SPECIFIC_TOP_LEVELS.contains(topLevel(type));
        }
        String sniffedTop = topLevel(sniffed);
        if (sniffedTop.equals("video") || sniffedTop.equals("audio")) {
            return sniffedTop.equals(topLevel(type));
        }
        return switch (sniffed) {
            case "text/plain" -> PLAIN_TEXT_TYPES.contains(type);
            case "application/csv" -> CSV_TYPES.contains(type);
            case "text/rtf" -> RTF_TYPES.contains(type);
            default -> sniffed.equals(type);
        };
    }

    private static String topLevel(String type) {
        int slash = type.indexOf('/');
        return slash < 0 ? type : type.substring(0, slash);
    }
}
