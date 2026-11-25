package org.quarkword.front.runtime;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.quarkword.front.FrontAnswer;

/**
 * WordPress's {@code wp_die} answer to a front-end request it refuses before rendering (a bad preview pair, a site
 * that is gone): for a request that wants JSON (its {@code Accept} or {@code Content-Type} names a JSON media type, as
 * WordPress's {@code wp_is_json_request} tells), {@code {"code":"wp_die","message":...,"data":{"status":...},
 * "additional_errors":[]}}; otherwise a minimal error page with the message, whose robots meta follows the site's
 * {@code blog_public} option alone, whatever the status. Both carry WordPress's no-cache headers and nothing else.
 */
final class WpDie {
    static final String HTML = "text/html; charset=UTF-8";
    static final String JSON = "application/json; charset=UTF-8";

    private WpDie() {}

    /** The answer with {@code status} and {@code message} (a constant text without markup). */
    static FrontAnswer died(int status, String message, String accept, String contentType, boolean publicSite) {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        byte[] body;
        if (wantsJson(accept) || wantsJson(contentType)) {
            headers.put("Content-Type", List.of(JSON));
            body = ("{\"code\":\"wp_die\",\"message\":\"" + jsonText(message) + "\",\"data\":{\"status\":" + status
                            + "},\"additional_errors\":[]}")
                    .getBytes(StandardCharsets.UTF_8);
        } else {
            headers.put("Content-Type", List.of(HTML));
            // WordPress adds its no-robots rule to the default ones: only blog_public counts, never the status
            String robots = publicSite ? "max-image-preview:large, noindex, follow" : "noindex, nofollow";
            body = ("<!DOCTYPE html>\n<html lang=\"en-US\">\n<head>\n"
                            + "\t<meta http-equiv=\"Content-Type\" content=\"text/html; charset=UTF-8\" />\n"
                            + "\t<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">\n"
                            + "\t<meta name='robots' content='" + robots + "' />\n"
                            + "\t<title>Error</title>\n</head>\n<body id=\"error-page\">\n"
                            + "\t<div class=\"wp-die-message\">" + message + "</div></body>\n</html>\n")
                    .getBytes(StandardCharsets.UTF_8);
        }
        headers.put("Expires", List.of(FrontHeaders.EXPIRES));
        headers.put("Cache-Control", List.of(FrontHeaders.NO_CACHE));
        return new FrontAnswer(status, headers, body);
    }

    private static String jsonText(String text) {
        StringBuilder out = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\\') {
                out.append('\\').append(c);
            } else if (c < 0x20) {
                out.append(String.format("\\u%04x", (int) c));
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    // ---- wp_is_json_media_type, as the REST module's JsonRequests reads it
    // -------------------------------------------

    private static final String APPLICATION = "application/";

    /**
     * Whether a header value names JSON: ignoring ASCII case, {@code application/} preceded by the start of the
     * value, a comma or ASCII white space, then either {@code json} or a subtype of at least one character from the
     * letters, digits and {@code _ ! # $ & ' ( ) * + , - . / : ; < = > ? @ [ \ ] ^} followed by {@code +json}, then
     * optionally {@code +oembed}, then the end of the value, a comma, a semicolon or ASCII white space.
     */
    static boolean wantsJson(String value) {
        if (value == null) {
            return false;
        }
        int length = value.length();
        // jsonAt[j]: "json" starts at j and is followed by an optional "+oembed" and a terminator
        boolean[] jsonAt = new boolean[length + 1];
        for (int j = 0; j + 4 <= length; j++) {
            jsonAt[j] = jsonWithTail(value, j);
        }
        // nextPlusJson[i]: the least j >= i with '+' at j - 1 and jsonAt[j], or length + 1 when there is none
        int[] nextPlusJson = new int[length + 2];
        nextPlusJson[length + 1] = length + 1;
        for (int j = length; j >= 0; j--) {
            nextPlusJson[j] = j >= 1 && value.charAt(j - 1) == '+' && jsonAt[j] ? j : nextPlusJson[j + 1];
        }
        // runEnd[i]: the first index >= i whose character is not a subtype character
        int[] runEnd = new int[length + 1];
        runEnd[length] = length;
        for (int i = length - 1; i >= 0; i--) {
            runEnd[i] = subtypeChar(value.charAt(i)) ? runEnd[i + 1] : i;
        }
        for (int a = 0; a + APPLICATION.length() <= length; a++) {
            if ((a == 0 || separator(value.charAt(a - 1))) && regionIgnoringCase(value, a, APPLICATION)) {
                int p = a + APPLICATION.length();
                if (jsonAt[p]) {
                    return true;
                }
                // a subtype of at least one character, its last one the '+' before "json"
                int j = nextPlusJson[Math.min(p + 2, length + 1)];
                if (j <= runEnd[p]) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean jsonWithTail(String value, int j) {
        if (!regionIgnoringCase(value, j, "json")) {
            return false;
        }
        int after = j + 4;
        if (terminator(value, after)) {
            return true;
        }
        return regionIgnoringCase(value, after, "+oembed") && terminator(value, after + 7);
    }

    private static boolean terminator(String value, int at) {
        if (at == value.length()) {
            return true;
        }
        char c = value.charAt(at);
        return c == ';' || c == ',' || space(c);
    }

    private static boolean separator(char c) {
        return c == ',' || space(c);
    }

    /** ASCII white space as PCRE's {@code \s} matches it: space, tab, line feed, vertical tab, form feed, return. */
    private static boolean space(char c) {
        return c == ' ' || (c >= '\t' && c <= '\r');
    }

    /** Letters, digits and {@code _ ! # $ .}, plus every character from {@code &} to {@code ^}. */
    private static boolean subtypeChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= '&' && c <= '^') || c == '_' || c == '!' || c == '#' || c == '$';
    }

    private static boolean regionIgnoringCase(String value, int at, String lowerCase) {
        if (at + lowerCase.length() > value.length()) {
            return false;
        }
        for (int i = 0; i < lowerCase.length(); i++) {
            char c = value.charAt(at + i);
            if (c >= 'A' && c <= 'Z') {
                c = (char) (c + 32);
            }
            if (c != lowerCase.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
