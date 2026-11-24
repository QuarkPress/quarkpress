package org.quarkword.lib.wpkses;

import java.util.Objects;

/**
 * WordPress's e-mail address check ({@code is_email}), as a hand-written scan that looks at every character once. An
 * address it accepts is one WordPress's {@code sanitize_email} leaves unchanged. One difference: WordPress's
 * {@code is_email} accepts a local part ending in a line feed ({@code "a\n@b.co"}, which {@code sanitize_email} then
 * turns into {@code a@b.co}); this check refuses it.
 */
public final class Emails {

    private Emails() {}

    /**
     * Whether {@code text} is an e-mail address as {@code is_email} decides it: at least 6 characters; an '@' after the
     * first character; a local part of letters, digits and {@code !#$%&'*+/=?^_`{|}~.-}; a domain of at least two
     * dot-separated labels, without "..", not starting or ending with a dot or whitespace, each label of ASCII
     * letters, digits and '-' and not starting or ending with '-'. Nothing is trimmed.
     *
     * @throws NullPointerException if {@code text} is {@code null}
     */
    public static boolean isEmail(String text) {
        Objects.requireNonNull(text, "text");
        if (text.length() < 6) {
            return false;
        }
        int at = text.indexOf('@');
        if (at < 1) {
            return false;
        }
        for (int i = 0; i < at; i++) {
            if (!localChar(text.charAt(i))) {
                return false;
            }
        }
        int start = at + 1;
        int labels = 0;
        int labelStart = start;
        for (int i = start; i <= text.length(); i++) {
            if (i == text.length() || text.charAt(i) == '.') {
                if (!label(text, labelStart, i)) {
                    return false;
                }
                labels++;
                labelStart = i + 1;
            } else if (!domainChar(text.charAt(i))) {
                return false;
            }
        }
        return labels >= 2;
    }

    private static boolean label(String text, int start, int end) {
        if (start == end) {
            return false;
        }
        return text.charAt(start) != '-' && text.charAt(end - 1) != '-';
    }

    private static boolean localChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || digit(c) || "!#$%&'*+/=?^_`{|}~.-".indexOf(c) >= 0;
    }

    private static boolean domainChar(char c) {
        return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || digit(c) || c == '-';
    }

    private static boolean digit(char c) {
        return c >= '0' && c <= '9';
    }
}
