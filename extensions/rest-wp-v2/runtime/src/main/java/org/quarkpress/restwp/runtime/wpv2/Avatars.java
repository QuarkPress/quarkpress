package org.quarkpress.restwp.runtime.wpv2;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Optional;
import org.quarkpress.core.SiteOptions;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;
import org.quarkpress.security.UserProfile;

/**
 * A user's avatar URLs as WordPress gives them: Gravatar addresses at sizes 24, 48 and 96 (or one address at any size,
 * as the editor's lock dialogs need 64 and 128 pixels), keyed by the SHA-256 of the e-mail address trimmed and
 * lower-cased (a user without an address, or with "0", gets the address without a hash), with the site's default image
 * ({@code avatar_default}, {@code mystery} when the option is missing; {@code mystery} and {@code mysteryman} are sent
 * as {@code mm}; {@code gravatar_default} and an empty value send none) and rating ({@code avatar_rating}, {@code G}
 * when the option is missing, sent lower-cased; an empty value sends none); with the {@code initials} default, the
 * name's initials as a last {@code initials} parameter. None when the site's {@code show_avatars} option is off.
 */
public final class Avatars {

    private static final int[] SIZES = {24, 48, 96};

    private static final String BASE = "https://secure.gravatar.com/avatar/";

    private Avatars() {}

    /** The name a user's initials come from: its display name, else its nicename. */
    static String initialsName(UserProfile profile) {
        return profile.displayName().isEmpty() ? profile.nicename() : profile.displayName();
    }

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
        String path = hash(email.orElse("")) + "?s=";
        String query = query(name, options);
        ObjectNode urls = RestJson.NODES.objectNode();
        for (int size : SIZES) {
            urls.put(Integer.toString(size), BASE + path + size + query);
        }
        return Optional.of(urls);
    }

    /**
     * One avatar URL of {@code size} pixels for a user without a name, built as {@link #urls} builds each of its
     * entries (the same hash, default image and rating); empty when the site does not show avatars.
     */
    public static Optional<String> url(String email, int size, SiteOptions options) {
        return url(Optional.of(email), "", size, options);
    }

    /**
     * One avatar URL of {@code size} pixels, built as {@link #urls} builds each of its entries; {@code name} gives the
     * {@code initials} parameter with that default image. Empty when the site does not show avatars.
     */
    public static Optional<String> url(Optional<String> email, String name, int size, SiteOptions options) {
        if (size <= 0) {
            throw new IllegalArgumentException("an avatar size is a positive number of pixels: " + size);
        }
        if (!options.flag("show_avatars", true)) {
            return Optional.empty();
        }
        return Optional.of(BASE + hash(email.orElse("")) + "?s=" + size + query(name, options));
    }

    /** The hash of the address: none for an empty address or "0" (PHP's truth). */
    private static String hash(String address) {
        return address.isEmpty() || address.equals("0") ? "" : sha256(asciiLower(PhpValues.trim(address)));
    }

    /** The parameters after the size: the default image, the rating and, with the initials image, the initials. */
    private static String query(String name, SiteOptions options) {
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
        return query.toString();
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
