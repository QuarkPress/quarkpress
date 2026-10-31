package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.quarkpress.core.SiteOptions;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * A user's avatar URLs as WordPress gives them: Gravatar addresses at sizes 24, 48 and 96, keyed by the SHA-256 of the
 * e-mail address trimmed and lower-cased (a user without an address, or with "0", gets the address without a hash),
 * with the site's default image ({@code avatar_default}, {@code mystery} when the option is missing; {@code mystery}
 * and {@code mysteryman} are sent as {@code mm}; {@code gravatar_default} and an empty value send none) and rating
 * ({@code avatar_rating}, {@code G} when the option is missing, sent lower-cased; an empty value sends none); with the
 * {@code initials} default, the name's initials as a last {@code initials} parameter. None when the site's
 * {@code show_avatars} option is off.
 */
public final class Avatars {

    private static final int[] SIZES = {24, 48, 96};

    private static final String BASE = "https://secure.gravatar.com/avatar/";

    private Avatars() {}

    /** The avatar URLs by size of a user without a name, as text keys; empty when the site does not show avatars. */
    public static Optional<ObjectNode> urls(Optional<String> email, SiteOptions options) {
        return urls(email, "", options);
    }

    /**
     * The avatar URLs by size, as text keys; empty when the site does not show avatars. With the {@code initials}
     * default image, {@code name} (the user's display name, else its login) gives the {@code initials} parameter.
     */
    public static Optional<ObjectNode> urls(Optional<String> email, String name, SiteOptions options) {
        if (!options.flag("show_avatars", true)) {
            return Optional.empty();
        }
        String address = email.orElse("");
        // PHP's truth: no hash for an empty address or "0"
        String hash = address.isEmpty() || address.equals("0") ? "" : sha256(asciiLower(phpTrim(address)));
        String image = options.string("avatar_default", "mystery");
        image = switch (image) {
            case "mystery", "mysteryman" -> "mm";
            case "gravatar_default" -> "";
            default -> image;
        };
        String rating = asciiLower(options.string("avatar_rating", "G"));
        StringBuilder query = new StringBuilder();
        if (!image.isEmpty()) {
            query.append("&d=").append(encode(image));
        }
        if (!rating.isEmpty()) {
            query.append("&r=").append(encode(rating));
        }
        if (image.equals("initials")) {
            String initials = initials(name);
            // PHP's truth: an empty value or "0" is dropped
            if (!initials.isEmpty() && !initials.equals("0")) {
                query.append("&initials=").append(encode(initials));
            }
        }
        ObjectNode urls = RestJson.NODES.objectNode();
        for (int size : SIZES) {
            urls.put(Integer.toString(size), BASE + hash + "?s=" + size + query);
        }
        return Optional.of(urls);
    }

    /**
     * WordPress's initials of a name: the first two characters when the name has no space or holds a Han, Hiragana,
     * Katakana or Hangul character; else the first character and the one after the last space, where WordPress takes
     * the last space's position in UTF-8 bytes as a position in characters (so a multibyte name may give another
     * character, or none); the case is kept.
     */
    static String initials(String name) {
        if (name.isEmpty()) {
            return "";
        }
        int[] points = name.codePoints().toArray();
        boolean cjk = false;
        for (int point : points) {
            Character.UnicodeScript script = Character.UnicodeScript.of(point);
            if (script == Character.UnicodeScript.HAN
                    || script == Character.UnicodeScript.HIRAGANA
                    || script == Character.UnicodeScript.KATAKANA
                    || script == Character.UnicodeScript.HANGUL) {
                cjk = true;
                break;
            }
        }
        int space = name.lastIndexOf(' ');
        if (space < 0 || cjk) {
            return new String(points, 0, Math.min(2, points.length));
        }
        int byteOffset = name.substring(0, space).getBytes(StandardCharsets.UTF_8).length;
        StringBuilder initials = new StringBuilder().appendCodePoint(points[0]);
        if (byteOffset + 1 < points.length) {
            initials.appendCodePoint(points[byteOffset + 1]);
        }
        return initials.toString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    /** PHP's {@code trim}: spaces, tabs, line feeds, carriage returns, NUL and vertical tabs off both ends. */
    private static String phpTrim(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && trimmed(text.charAt(start))) {
            start++;
        }
        while (end > start && trimmed(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(start, end);
    }

    private static boolean trimmed(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r' || c == '\u0000' || c == '\u000B';
    }

    /** PHP 8's {@code strtolower}: ASCII letters only. */
    private static String asciiLower(String text) {
        StringBuilder lower = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + 32) : c);
        }
        return lower.toString();
    }

    /** PHP's {@code rawurlencode} of the text's UTF-8 bytes. */
    private static String encode(String text) {
        StringBuilder encoded = new StringBuilder(text.length());
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xff;
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.'
                    || c == '~') {
                encoded.append((char) c);
            } else {
                encoded.append('%').append(HexFormat.of().withUpperCase().toHexDigits((byte) c));
            }
        }
        return encoded.toString();
    }
}
