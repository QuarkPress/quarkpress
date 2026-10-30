package org.quarkpress.restwp.runtime;

import java.util.Optional;

/**
 * Checks a JSON text as PHP's {@code json_decode} (default depth 512) reads it and reports the error PHP would: the
 * first problem in the text wins, as PHP's scanner and parser meet them. A nesting of 512 arrays or objects is too
 * deep; a control character, inside or outside a string, is a control character error; bytes that are not UTF-8 are
 * a UTF-8 error; an unpaired {@code \}{@code u} surrogate escape is its own error; a closing bracket of the wrong kind
 * where one may close is a state mismatch; anything else is a syntax error. Iterative and linear in the text.
 */
public final class PhpJsonCheck {

    /** PHP's default depth: nesting this deep fails. */
    static final int MAX_DEPTH = 512;

    private enum Token {
        BEGIN_OBJECT,
        END_OBJECT,
        BEGIN_ARRAY,
        END_ARRAY,
        COLON,
        COMMA,
        SCALAR,
        STRING,
        END
    }

    private static final class Failure extends RuntimeException {

        private static final long serialVersionUID = 1L;

        final transient RestJsonFailure failure;

        Failure(RestJsonFailure failure) {
            super(failure.message(), null, false, false);
            this.failure = failure;
        }
    }

    private final byte[] text;
    private int at;

    private PhpJsonCheck(byte[] text) {
        this.text = text;
    }

    /** The error PHP would report for {@code text}, or empty when PHP decodes it. */
    public static Optional<RestJsonFailure> check(byte[] text) {
        try {
            new PhpJsonCheck(text).document();
            return Optional.empty();
        } catch (Failure failure) {
            return Optional.of(failure.failure);
        }
    }

    private void document() {
        // what each open container expects next: 'v' a first value or close, 'n' a comma or close, 'k' a key,
        // 'K' a first key or close, 'c' a colon, 'w' a value after a colon or a comma
        byte[] stack = new byte[MAX_DEPTH + 1];
        boolean[] objects = new boolean[MAX_DEPTH + 1];
        Token token = next();
        value(token, stack, objects, 0);
        int depth = token == Token.BEGIN_ARRAY || token == Token.BEGIN_OBJECT ? 1 : 0;
        while (depth > 0) {
            boolean object = objects[depth];
            byte expect = stack[depth];
            token = next();
            switch (expect) {
                case 'v', 'K' -> {
                    if (closes(token, object)) {
                        depth--;
                        continue;
                    }
                    if (closesOther(token, object)) {
                        throw new Failure(RestJsonFailure.STATE_MISMATCH);
                    }
                    if (object) {
                        if (token != Token.STRING) {
                            throw syntax();
                        }
                        stack[depth] = 'c';
                    } else {
                        stack[depth] = 'n';
                        depth = enter(token, stack, objects, depth);
                    }
                }
                case 'n' -> {
                    if (closes(token, object)) {
                        depth--;
                        continue;
                    }
                    if (closesOther(token, object)) {
                        throw new Failure(RestJsonFailure.STATE_MISMATCH);
                    }
                    if (token != Token.COMMA) {
                        throw syntax();
                    }
                    stack[depth] = (byte) (object ? 'k' : 'w');
                }
                case 'k' -> {
                    if (token != Token.STRING) {
                        throw syntax();
                    }
                    stack[depth] = 'c';
                }
                case 'c' -> {
                    if (token != Token.COLON) {
                        throw syntax();
                    }
                    stack[depth] = 'w';
                }
                case 'w' -> {
                    stack[depth] = 'n';
                    depth = enter(token, stack, objects, depth);
                }
                default -> throw new IllegalStateException();
            }
        }
        if (next() != Token.END) {
            throw syntax();
        }
    }

    /** Checks the value starting with {@code token}; a container opens one level deeper. */
    private int enter(Token token, byte[] stack, boolean[] objects, int depth) {
        if (value(token, stack, objects, depth)) {
            return depth + 1;
        }
        return depth;
    }

    private static boolean value(Token token, byte[] stack, boolean[] objects, int depth) {
        switch (token) {
            case BEGIN_ARRAY, BEGIN_OBJECT -> {
                if (depth + 1 >= MAX_DEPTH) {
                    throw new Failure(RestJsonFailure.DEPTH);
                }
                boolean object = token == Token.BEGIN_OBJECT;
                objects[depth + 1] = object;
                stack[depth + 1] = (byte) (object ? 'K' : 'v');
                return true;
            }
            case SCALAR, STRING -> {
                return false;
            }
            default -> throw syntax();
        }
    }

    private static boolean closes(Token token, boolean object) {
        return token == (object ? Token.END_OBJECT : Token.END_ARRAY);
    }

    private static boolean closesOther(Token token, boolean object) {
        return token == (object ? Token.END_ARRAY : Token.END_OBJECT);
    }

    private static Failure syntax() {
        return new Failure(RestJsonFailure.SYNTAX);
    }

    private Token next() {
        while (at < text.length) {
            int c = text[at] & 0xFF;
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
                at++;
                continue;
            }
            switch (c) {
                case '{' -> {
                    at++;
                    return Token.BEGIN_OBJECT;
                }
                case '}' -> {
                    at++;
                    return Token.END_OBJECT;
                }
                case '[' -> {
                    at++;
                    return Token.BEGIN_ARRAY;
                }
                case ']' -> {
                    at++;
                    return Token.END_ARRAY;
                }
                case ':' -> {
                    at++;
                    return Token.COLON;
                }
                case ',' -> {
                    at++;
                    return Token.COMMA;
                }
                case '"' -> {
                    string();
                    return Token.STRING;
                }
                case 't' -> {
                    return literal("true");
                }
                case 'f' -> {
                    return literal("false");
                }
                case 'n' -> {
                    return literal("null");
                }
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        number();
                        return Token.SCALAR;
                    }
                    throw new Failure(unexpected(c));
                }
            }
        }
        return Token.END;
    }

    /** What PHP's scanner reports for a character that starts no token. */
    private RestJsonFailure unexpected(int c) {
        if (c < 0x20) {
            return RestJsonFailure.CONTROL_CHARACTER;
        }
        if (c >= 0x80 && utf8Length(at) < 0) {
            return RestJsonFailure.UTF8;
        }
        return RestJsonFailure.SYNTAX;
    }

    private Token literal(String word) {
        for (int i = 0; i < word.length(); i++) {
            if (at + i >= text.length || text[at + i] != word.charAt(i)) {
                throw syntax();
            }
        }
        at += word.length();
        return Token.SCALAR;
    }

    private void number() {
        if (text[at] == '-') {
            at++;
            if (at >= text.length || !digit(text[at])) {
                throw syntax();
            }
        }
        if (text[at] == '0') {
            at++;
        } else {
            while (at < text.length && digit(text[at])) {
                at++;
            }
        }
        if (at + 1 < text.length && text[at] == '.' && digit(text[at + 1])) {
            at++;
            while (at < text.length && digit(text[at])) {
                at++;
            }
        }
        if (at < text.length && (text[at] == 'e' || text[at] == 'E')) {
            int exponent = at + 1;
            if (exponent < text.length && (text[exponent] == '+' || text[exponent] == '-')) {
                exponent++;
            }
            if (exponent < text.length && digit(text[exponent])) {
                at = exponent;
                while (at < text.length && digit(text[at])) {
                    at++;
                }
            }
        }
    }

    private static boolean digit(byte b) {
        return b >= '0' && b <= '9';
    }

    private void string() {
        at++;
        while (true) {
            if (at >= text.length) {
                // PHP reads the text's terminating NUL here: a control character
                throw new Failure(RestJsonFailure.CONTROL_CHARACTER);
            }
            int c = text[at] & 0xFF;
            if (c == '"') {
                at++;
                return;
            }
            if (c == '\\') {
                escape();
            } else if (c < 0x20) {
                throw new Failure(RestJsonFailure.CONTROL_CHARACTER);
            } else if (c < 0x80) {
                at++;
            } else {
                int length = utf8Length(at);
                if (length < 0) {
                    throw new Failure(RestJsonFailure.UTF8);
                }
                at += length;
            }
        }
    }

    private void escape() {
        if (at + 1 >= text.length) {
            throw syntax();
        }
        switch (text[at + 1]) {
            case '"', '\\', '/', 'b', 'f', 'n', 'r', 't' -> at += 2;
            case 'u' -> {
                int unit = hex4(at + 2);
                if (unit < 0) {
                    throw syntax();
                }
                at += 6;
                if (unit >= 0xDC00 && unit <= 0xDFFF) {
                    throw new Failure(RestJsonFailure.UTF16);
                }
                if (unit >= 0xD800 && unit <= 0xDBFF) {
                    int low = at + 1 < text.length && text[at] == '\\' && text[at + 1] == 'u' ? hex4(at + 2) : -1;
                    if (low < 0xDC00 || low > 0xDFFF) {
                        throw new Failure(RestJsonFailure.UTF16);
                    }
                    at += 6;
                }
            }
            default -> throw syntax();
        }
    }

    private int hex4(int from) {
        if (from + 4 > text.length) {
            return -1;
        }
        int value = 0;
        for (int i = from; i < from + 4; i++) {
            int b = text[i];
            int digit;
            if (b >= '0' && b <= '9') {
                digit = b - '0';
            } else if (b >= 'a' && b <= 'f') {
                digit = b - 'a' + 10;
            } else if (b >= 'A' && b <= 'F') {
                digit = b - 'A' + 10;
            } else {
                return -1;
            }
            value = value * 16 + digit;
        }
        return value;
    }

    /** The length of the well-formed UTF-8 sequence of more than one byte at {@code from}, or -1. */
    private int utf8Length(int from) {
        int b0 = text[from] & 0xFF;
        int length;
        int min2;
        int max2;
        if (b0 >= 0xC2 && b0 <= 0xDF) {
            length = 2;
            min2 = 0x80;
            max2 = 0xBF;
        } else if (b0 == 0xE0) {
            length = 3;
            min2 = 0xA0;
            max2 = 0xBF;
        } else if ((b0 >= 0xE1 && b0 <= 0xEC) || b0 == 0xEE || b0 == 0xEF) {
            length = 3;
            min2 = 0x80;
            max2 = 0xBF;
        } else if (b0 == 0xED) {
            length = 3;
            min2 = 0x80;
            max2 = 0x9F;
        } else if (b0 == 0xF0) {
            length = 4;
            min2 = 0x90;
            max2 = 0xBF;
        } else if (b0 >= 0xF1 && b0 <= 0xF3) {
            length = 4;
            min2 = 0x80;
            max2 = 0xBF;
        } else if (b0 == 0xF4) {
            length = 4;
            min2 = 0x80;
            max2 = 0x8F;
        } else {
            return -1;
        }
        if (from + length > text.length) {
            return -1;
        }
        int b1 = text[from + 1] & 0xFF;
        if (b1 < min2 || b1 > max2) {
            return -1;
        }
        for (int i = from + 2; i < from + length; i++) {
            int b = text[i] & 0xFF;
            if (b < 0x80 || b > 0xBF) {
                return -1;
            }
        }
        return length;
    }
}
