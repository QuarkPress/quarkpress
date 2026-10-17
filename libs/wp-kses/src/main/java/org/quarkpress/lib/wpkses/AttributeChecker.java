package org.quarkpress.lib.wpkses;

import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;

/** Accepts or rejects one attribute of an allowed element, applying its rule's value checks. */
final class AttributeChecker {

    /** What a check may consult: the CSS filter for {@code style} and the PDF-object URL test. */
    record Environment(UnaryOperator<String> safeCss, Predicate<String> pdfObjectUrl) {
        Environment {
            Objects.requireNonNull(safeCss, "safeCss");
            Objects.requireNonNull(pdfObjectUrl, "pdfObjectUrl");
        }
    }

    /** The verdict; an accepted attribute carries its value and whole text, both possibly rewritten for style. */
    record Result(boolean accepted, String value, String whole) {
        static final Result REJECTED = new Result(false, "", "");
    }

    private AttributeChecker() {}

    /**
     * Checks one attribute of an allowed element. {@code value} is the re-encoded value ("" for a boolean
     * attribute) and {@code whole} the attribute text that would be written.
     */
    static Result check(
            String name, String value, String whole, boolean valueless, ElementRule element, Environment env) {
        String lower = TextUtils.asciiLower(name);
        AttributeRule rule = element.attributes().get(lower);
        if (rule == null) {
            AttributeRule wildcard = element.attributes().get("data-*");
            if (wildcard == null || !isDataAttributeName(lower)) {
                return Result.REJECTED;
            }
            rule = wildcard;
        }
        if (lower.equals("style")) {
            String decoded = CharacterReferences.decodeAttribute(value);
            String filtered = env.safeCss().apply(decoded);
            if (TextUtils.phpEmpty(filtered)) {
                return Result.REJECTED;
            }
            if (!filtered.equals(decoded)) {
                String encoded = HtmlEscaper.escape(filtered);
                whole = value.isEmpty() ? whole : whole.replace(value, encoded);
                value = encoded;
            }
        }
        for (AttributeCheck check : rule.checks()) {
            if (!passes(check, value, valueless, env)) {
                return Result.REJECTED;
            }
        }
        return new Result(true, value, whole);
    }

    /** "data-" followed by one or more ASCII lowercase letters, digits, '_' or '-'. */
    static boolean isDataAttributeName(String lowerName) {
        if (!lowerName.startsWith("data-") || lowerName.length() == "data-".length()) {
            return false;
        }
        for (int i = "data-".length(); i < lowerName.length(); i++) {
            char c = lowerName.charAt(i);
            if (!((c >= 'a' && c <= 'z') || TextUtils.isAsciiDigit(c) || c == '_' || c == '-')) {
                return false;
            }
        }
        return true;
    }

    private static boolean passes(AttributeCheck check, String value, boolean valueless, Environment env) {
        return switch (check) {
            case AttributeCheck.MaxLength max -> TextUtils.utf8Length(value) <= max.bytes();
            case AttributeCheck.MinLength min -> TextUtils.utf8Length(value) >= min.bytes();
            case AttributeCheck.MaxValue max -> isSmallNumber(value) && parseSmallNumber(value) <= max.max();
            case AttributeCheck.MinValue min -> isSmallNumber(value) && parseSmallNumber(value) >= min.min();
            case AttributeCheck.Valueless flag ->
                TextUtils.asciiLower(flag.flag()).equals(valueless ? "y" : "n");
            case AttributeCheck.Values values -> values.allowed().contains(TextUtils.asciiLower(value));
            case AttributeCheck.Callback callback -> callback.test().test(value);
            case AttributeCheck.PdfObjectUrl pdf -> env.pdfObjectUrl().test(value);
        };
    }

    /**
     * The maxval/minval shape: up to six whitespace characters, one to six ASCII digits, up to six whitespace
     * characters; one more line feed may end the value (PCRE's '$' also matches before a final line feed).
     */
    static boolean isSmallNumber(String value) {
        int n = value.length();
        int i = 0;
        while (i < n && i < 6 && TextUtils.isPcreSpace(value.charAt(i))) {
            i++;
        }
        int digits = i;
        while (i < n && i - digits < 6 && TextUtils.isAsciiDigit(value.charAt(i))) {
            i++;
        }
        if (i == digits) {
            return false;
        }
        int trailing = 0;
        while (i < n && TextUtils.isPcreSpace(value.charAt(i)) && trailing < 6) {
            i++;
            trailing++;
        }
        return i == n || (i == n - 1 && value.charAt(i) == '\n');
    }

    private static long parseSmallNumber(String value) {
        long number = 0;
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (TextUtils.isAsciiDigit(c)) {
                number = number * 10 + (c - '0');
            }
        }
        return number;
    }
}
