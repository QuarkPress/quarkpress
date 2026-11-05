package org.quarkpress.media.runtime;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Chooses the name an upload is stored under in its folder, as WordPress does, except that an existing file is never
 * chosen.
 *
 * <p>The name is split at its last dot into a base and an extension. A base that already ends like a sub-size
 * ({@code -150x150}, {@code -scaled}, {@code -rotated}) is numbered from the start. Then, sharing one number: while the
 * name or its twin with a lower-case extension exists, the number grows, and the extension is lower-cased; while the
 * folder holds a sub-size of the candidate (compared without regard to the case of ASCII letters), the number grows;
 * for image types with alternate formats (HEIC and JPEG by default), while the candidate or one of its alternate-format
 * twins exists or has a sub-size, the number grows. Finally, while the candidate exists, the number grows and the two
 * sub-size checks run again. Numbering puts {@code -n} before the extension, at the name's end only. Lower-casing
 * changes the ASCII letters {@code A} to {@code Z} only. Every loop is bounded by the folder's entry count.
 */
public final class UniqueNames {

    private UniqueNames() {}

    /**
     * The name {@code sanitizedName} is stored under in {@code folder}.
     *
     * @param sanitizedName the upload's name, already sanitized
     * @param folder the upload folder
     * @param allowed the uploader's allowed types (an image type by extension gets the alternate-format check)
     * @param outputFormats the type each image type's sub-sizes are written in; empty when the upload keeps its
     *     format
     */
    public static String choose(
            String sanitizedName, UploadFolder folder, AllowedTypes allowed, Map<String, String> outputFormats) {
        int dot = sanitizedName.lastIndexOf('.');
        String base = dot < 0 ? sanitizedName : sanitizedName.substring(0, dot);
        String givenExtension = dot < 0 ? "" : sanitizedName.substring(dot);
        String extension = asciiLower(givenExtension);

        List<String> entries = folder.entries();
        int limit = entries.size() + 1;
        Set<String> subSized = base.isEmpty() || extension.isEmpty() ? Set.of() : subSizedNames(entries);
        List<String> alternates = alternateExtensions(base + extension, allowed, outputFormats);

        int number = looksLikeSubSize(base) ? 1 : 0;
        for (int i = 0; i < limit && existsEither(folder, base, number, givenExtension, extension); i++) {
            number++;
        }
        number = afterSubSizeChecks(folder, base, extension, number, limit, subSized, alternates);
        for (int pass = 0; existsEither(folder, base, number, givenExtension, extension); pass++) {
            number++;
            if (pass > limit) {
                break;
            }
            number = afterSubSizeChecks(folder, base, extension, number, limit, subSized, alternates);
        }
        return name(base, number, extension);
    }

    /** The number after the sub-size listing check and the alternate-format check. */
    private static int afterSubSizeChecks(
            UploadFolder folder,
            String base,
            String extension,
            int number,
            int limit,
            Set<String> subSized,
            List<String> alternates) {
        for (int i = 0; i < limit && hasSubSize(subSized, base, number, extension); i++) {
            number++;
        }
        if (!alternates.isEmpty()) {
            for (int i = 0; i < limit && alternateTaken(folder, base, number, extension, alternates, subSized); i++) {
                number++;
            }
        }
        return number;
    }

    /** Whether the candidate or one of its alternate-format twins exists or has a sub-size in the folder. */
    private static boolean alternateTaken(
            UploadFolder folder,
            String base,
            int number,
            String extension,
            List<String> alternates,
            Set<String> subSized) {
        if (folder.exists(name(base, number, extension)) || hasSubSize(subSized, base, number, extension)) {
            return true;
        }
        for (String alternate : alternates) {
            if (folder.exists(name(base, number, alternate)) || hasSubSize(subSized, base, number, alternate)) {
                return true;
            }
        }
        return false;
    }

    private static boolean existsEither(
            UploadFolder folder, String base, int number, String givenExtension, String extension) {
        return folder.exists(name(base, number, givenExtension))
                || (!givenExtension.equals(extension) && folder.exists(name(base, number, extension)));
    }

    private static boolean hasSubSize(Set<String> subSized, String base, int number, String extension) {
        return !subSized.isEmpty() && subSized.contains(asciiLower(name(base, number, extension)));
    }

    private static String name(String base, int number, String extension) {
        return number == 0 ? base + extension : base + "-" + number + extension;
    }

    /**
     * The names, in lower case, whose sub-sizes the folder holds: an entry {@code photo-300x200.JPG} (or
     * {@code photo-scaled.jpg}, {@code photo-rotated.jpg}, any case) gives {@code photo.jpg}.
     */
    private static Set<String> subSizedNames(List<String> entries) {
        Set<String> names = new HashSet<>();
        for (String entry : entries) {
            int dot = entry.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String stem = asciiLower(entry.substring(0, dot));
            int cut = subSizeSuffixStart(stem);
            if (cut > 0) {
                names.add(stem.substring(0, cut) + asciiLower(entry.substring(dot)));
            }
        }
        return names;
    }

    /** Whether {@code base} ends, case-sensitively, like a sub-size. */
    private static boolean looksLikeSubSize(String base) {
        return subSizeSuffixStart(base) >= 0;
    }

    /**
     * Where a trailing {@code -{digits}x{digits}}, {@code -scaled} or {@code -rotated} starts in {@code stem}, or -1
     * (case-sensitively: callers lower-case the stem first for a comparison without regard to case).
     */
    private static int subSizeSuffixStart(String stem) {
        if (stem.endsWith("-scaled")) {
            return stem.length() - "-scaled".length();
        }
        if (stem.endsWith("-rotated")) {
            return stem.length() - "-rotated".length();
        }
        int at = stem.length();
        int heightDigits = 0;
        while (at > 0 && isDigit(stem.charAt(at - 1))) {
            at--;
            heightDigits++;
        }
        if (heightDigits == 0 || at == 0) {
            return -1;
        }
        char x = stem.charAt(at - 1);
        if (x != 'x') {
            return -1;
        }
        at--;
        int widthDigits = 0;
        while (at > 0 && isDigit(stem.charAt(at - 1))) {
            at--;
            widthDigits++;
        }
        if (widthDigits == 0 || at == 0 || stem.charAt(at - 1) != '-') {
            return -1;
        }
        return at - 1;
    }

    /** {@code text} with the ASCII letters {@code A} to {@code Z} lower-cased and every other character kept. */
    static String asciiLower(String text) {
        char[] chars = null;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z') {
                if (chars == null) {
                    chars = text.toCharArray();
                }
                chars[i] = (char) (c + ('a' - 'A'));
            }
        }
        return chars == null ? text : new String(chars);
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }

    /**
     * The extensions ({@code .heic}, {@code .jpg} and so on) of the formats an image named {@code name} shares derived files
     * with: when the output formats convert its type to another, every type converted to its type or to that one, and
     * that one; otherwise every type converted to its type; never its own type. Empty for a name that is not an image
     * by its extension.
     */
    private static List<String> alternateExtensions(
            String name, AllowedTypes allowed, Map<String, String> outputFormats) {
        Optional<AllowedTypes.Match> match = allowed.lookup(name);
        if (match.isEmpty() || !match.get().mimeType().startsWith("image/") || outputFormats.isEmpty()) {
            return List.of();
        }
        String type = match.get().mimeType();
        String target = outputFormats.get(type);
        Set<String> types = new LinkedHashSet<>();
        for (Map.Entry<String, String> format : outputFormats.entrySet()) {
            if (format.getValue().equals(type)
                    || (target != null && format.getValue().equals(target))) {
                types.add(format.getKey());
            }
        }
        if (target != null) {
            types.add(target);
        }
        types.remove(type);
        List<String> extensions = new ArrayList<>();
        for (String alternate : types) {
            String extension = DefaultMimeTypes.defaultExtension(alternate);
            if (extension != null && !extensions.contains("." + extension)) {
                extensions.add("." + extension);
            }
        }
        return extensions;
    }
}
