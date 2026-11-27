package org.quarkword.lib.wpformatting;

/** {@code capital_P_dangit}: corrects "Wordpress" to "WordPress", everywhere or only after certain prefixes. */
final class CapitalP {

    private static final String WRONG = "Wordpress";
    private static final String RIGHT = "WordPress";
    static final String ENGLISH_OPENING_DOUBLE_QUOTE = "&#8220;";

    private CapitalP() {}

    static String apply(String text, CapitalPMode mode, String openingDoubleQuote) {
        if (!text.contains(WRONG)) {
            return text;
        }
        if (mode == CapitalPMode.SIMPLE) {
            return text.replace(WRONG, RIGHT);
        }
        // one literal pass per prefix, in this order, each over the result of the one before
        String result = text;
        for (String prefix : new String[] {" ", "&#8216;", openingDoubleQuote, ">", "("}) {
            result = result.replace(prefix + WRONG, prefix + RIGHT);
        }
        return result;
    }
}
