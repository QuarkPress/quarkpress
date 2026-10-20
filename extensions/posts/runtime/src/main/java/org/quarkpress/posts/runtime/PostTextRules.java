package org.quarkpress.posts.runtime;

/** Text rules of the save path that are not kses. */
public final class PostTextRules {

    /** Unicode code points of the Windows-1252 characters 128 to 159; 0 where the code page has none. */
    private static final int[] WINDOWS_1252 = {
        8364, 0, 8218, 402, 8222, 8230, 8224, 8225, 710, 8240, 352, 8249, 338, 0, 381, 0,
        0, 8216, 8217, 8220, 8221, 8226, 8211, 8212, 732, 8482, 353, 8250, 339, 0, 382, 376
    };

    private PostTextRules() {}

    /**
     * WordPress's {@code convert_invalid_entities}: each numeric entity {@code &#128;} to {@code &#159;} (written with
     * three digits) becomes the entity of the Windows-1252 character it was meant as, or is removed where the code
     * page has none; other text is unchanged.
     */
    public static String convertInvalidEntities(String text) {
        if (!text.contains("&#1")) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            if (i + 6 <= text.length()
                    && text.startsWith("&#1", i)
                    && isDigit(text.charAt(i + 3))
                    && isDigit(text.charAt(i + 4))
                    && text.charAt(i + 5) == ';') {
                int value = 100 + (text.charAt(i + 3) - '0') * 10 + (text.charAt(i + 4) - '0');
                if (value >= 128 && value <= 159) {
                    int replacement = WINDOWS_1252[value - 128];
                    if (replacement != 0) {
                        out.append("&#").append(replacement).append(';');
                    }
                    i += 6;
                    continue;
                }
            }
            out.append(text.charAt(i));
            i++;
        }
        return out.toString();
    }

    private static boolean isDigit(char c) {
        return c >= '0' && c <= '9';
    }
}
