package org.quarkpress.lib.wxr;

/** Integer fields: a value only when the text, without surrounding XML whitespace, is a whole number. */
final class Integers {

    private Integers() {}

    static Long parse(String text) {
        if (text == null) {
            return null;
        }
        String t = Texts.xmlTrim(text);
        if (t.isEmpty() || t.length() > 20) {
            return null;
        }
        int first = t.charAt(0) == '-' || t.charAt(0) == '+' ? 1 : 0;
        if (first == t.length()) {
            return null;
        }
        for (int i = first; i < t.length(); i++) {
            if (t.charAt(i) < '0' || t.charAt(i) > '9') {
                return null;
            }
        }
        try {
            return Long.parseLong(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
