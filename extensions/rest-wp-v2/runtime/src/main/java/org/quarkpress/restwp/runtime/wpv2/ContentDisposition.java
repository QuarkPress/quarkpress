package org.quarkpress.restwp.runtime.wpv2;

import java.util.List;
import java.util.Optional;
import org.quarkpress.restwp.runtime.PhpValues;

/**
 * The file name of a raw-body upload, read from its {@code Content-Disposition} header as WordPress reads it. Each
 * header line is read on its own; a line without a semicolon is skipped, and the disposition type before the first
 * semicolon is not checked. The rest is cut at every semicolon, without regard to quotes; a piece holding {@code =} is
 * cut at its first one, and both halves are trimmed as PHP trims. Every piece named exactly (case-sensitively)
 * {@code filename} ({@code filename*} does not count) replaces the name so far, in the order of the lines and pieces,
 * even when its value is empty; one pair of surrounding double quotes is removed and nothing is unescaped. The last
 * such value is the name, unless it is empty in PHP's sense ({@code ""} or {@code "0"}). The work is linear in the
 * header's length.
 */
final class ContentDisposition {

    private ContentDisposition() {}

    /** The file name the header's lines {@code values} give, in the order received; empty when none does. */
    static Optional<String> fileName(List<String> values) {
        String found = null;
        for (String value : values) {
            int semicolon = value.indexOf(';');
            if (semicolon < 0) {
                continue;
            }
            int start = semicolon + 1;
            while (start <= value.length()) {
                int end = value.indexOf(';', start);
                if (end < 0) {
                    end = value.length();
                }
                String name = fromPiece(value, start, end);
                if (name != null) {
                    found = name;
                }
                start = end + 1;
            }
        }
        return found == null || found.isEmpty() || found.equals("0") ? Optional.empty() : Optional.of(found);
    }

    /** The value of the piece {@code value[start, end)} when it is named {@code filename}, quotes removed; else null. */
    private static String fromPiece(String value, int start, int end) {
        int equals = -1;
        for (int i = start; i < end; i++) {
            if (value.charAt(i) == '=') {
                equals = i;
                break;
            }
        }
        if (equals < 0 || !PhpValues.trim(value, start, equals).equals("filename")) {
            return null;
        }
        String name = PhpValues.trim(value, equals + 1, end);
        if (name.length() >= 2 && name.charAt(0) == '"' && name.charAt(name.length() - 1) == '"') {
            name = name.substring(1, name.length() - 1);
        }
        return name;
    }
}
