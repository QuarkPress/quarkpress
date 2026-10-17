package org.quarkpress.lib.wpkses;

/**
 * wp_strip_all_tags: script and style elements removed with their content, then a strip_tags state machine, then
 * optional line-break collapsing, then PHP trim. The state machine reproduces the documented behavior of PHP's C
 * strip_tags function (PHP License); it is not derived from WordPress code.
 */
final class StripTags {

    private StripTags() {}

    static String stripAllTags(String text, boolean removeBreaks) {
        String s = phpStripTags(removeScriptAndStyle(text));
        if (removeBreaks) {
            s = collapseBreaks(s);
        }
        return TextUtils.phpTrim(s);
    }

    /**
     * Removes each "<script...>...</script>" and "<style...>...</style>": names compared without case, the
     * opening tag up to its first '>', the shortest content up to the matching closing tag written exactly
     * "</name>".
     */
    static String removeScriptAndStyle(String s) {
        String lower = TextUtils.asciiLower(s);
        Finder greaterThan = new Finder(lower, ">");
        Finder scriptEnd = new Finder(lower, "</script>");
        Finder styleEnd = new Finder(lower, "</style>");
        StringBuilder out = null;
        int copied = 0;
        int i = lower.indexOf('<');
        while (i >= 0) {
            String name =
                    lower.startsWith("script", i + 1) ? "script" : lower.startsWith("style", i + 1) ? "style" : null;
            if (name != null) {
                int gt = greaterThan.next(i + 1 + name.length());
                int close = gt < 0 ? -1 : (name.equals("script") ? scriptEnd : styleEnd).next(gt + 1);
                if (close >= 0) {
                    if (out == null) {
                        out = new StringBuilder(s.length());
                    }
                    out.append(s, copied, i);
                    copied = close + name.length() + 3;
                    i = lower.indexOf('<', copied);
                    continue;
                }
            }
            i = lower.indexOf('<', i + 1);
        }
        return out == null ? s : out.append(s, copied, s.length()).toString();
    }

    /** PHP's strip_tags without allowed tags, as a state machine over UTF-16 code units. */
    static String phpStripTags(String s) {
        final int text = 0;
        final int tag = 1;
        final int php = 2;
        final int declaration = 3;
        final int comment = 4;
        StringBuilder out = new StringBuilder(s.length());
        int n = s.length();
        int state = text;
        int depth = 0;
        char inQuote = 0;
        char last = 0;
        int brackets = 0;
        boolean xml = false;
        for (int p = 0; p < n; p++) {
            char c = s.charAt(p);
            char prev = p > 0 ? s.charAt(p - 1) : 0;
            boolean nextIsSpace = p + 1 < n && TextUtils.isPcreSpace(s.charAt(p + 1));
            switch (state) {
                case text -> {
                    if (c == '\0') {
                        break;
                    }
                    if (c == '<') {
                        if (nextIsSpace) {
                            out.append(c);
                        } else {
                            last = '<';
                            state = tag;
                        }
                    } else if (c == '>') {
                        if (depth > 0) {
                            depth--;
                        } else {
                            out.append(c);
                        }
                    } else {
                        out.append(c);
                    }
                }
                case tag -> {
                    switch (c) {
                        case '<' -> {
                            if (inQuote == 0 && !nextIsSpace) {
                                depth++;
                            }
                        }
                        case '>' -> {
                            if (depth > 0) {
                                depth--;
                            } else if (inQuote == 0) {
                                last = '>';
                                if (!(xml && prev == '-')) {
                                    inQuote = 0;
                                    xml = false;
                                    state = text;
                                }
                            }
                        }
                        case '"', '\'' -> {
                            if (p > 0 && (inQuote == 0 || c == inQuote)) {
                                inQuote = inQuote == 0 ? c : 0;
                            }
                        }
                        case '!' -> {
                            if (prev == '<') {
                                state = declaration;
                                last = c;
                            }
                        }
                        case '?' -> {
                            if (prev == '<') {
                                brackets = 0;
                                state = php;
                            }
                        }
                        default -> {}
                    }
                }
                case php -> {
                    switch (c) {
                        case '(' -> {
                            if (last != '"' && last != '\'') {
                                last = '(';
                                brackets++;
                            }
                        }
                        case ')' -> {
                            if (last != '"' && last != '\'') {
                                last = ')';
                                brackets--;
                            }
                        }
                        case '>' -> {
                            if (depth > 0) {
                                depth--;
                            } else if (inQuote == 0 && brackets == 0 && last != '"' && prev == '?') {
                                inQuote = 0;
                                state = text;
                            }
                        }
                        case '"', '\'' -> {
                            if (p > 0 && prev != '\\') {
                                if (last == c) {
                                    last = 0;
                                } else if (last != '\\') {
                                    last = c;
                                }
                            }
                            if (p > 0 && prev != '\\' && (inQuote == 0 || c == inQuote)) {
                                inQuote = inQuote == 0 ? c : 0;
                            }
                        }
                        case 'l', 'L' -> {
                            if (p > 4
                                    && TextUtils.toAsciiLower(s.charAt(p - 1)) == 'm'
                                    && TextUtils.toAsciiLower(s.charAt(p - 2)) == 'x'
                                    && s.charAt(p - 3) == '?'
                                    && s.charAt(p - 4) == '<') {
                                state = tag;
                                xml = true;
                            }
                        }
                        default -> {}
                    }
                }
                case declaration -> {
                    switch (c) {
                        case '>' -> {
                            if (depth > 0) {
                                depth--;
                            } else if (inQuote == 0) {
                                state = text;
                            }
                        }
                        case '"', '\'' -> {
                            if (p > 0 && prev != '\\' && (inQuote == 0 || c == inQuote)) {
                                inQuote = inQuote == 0 ? c : 0;
                            }
                        }
                        case '-' -> {
                            if (p >= 2 && prev == '-' && s.charAt(p - 2) == '!') {
                                state = comment;
                            }
                        }
                        case 'E', 'e' -> {
                            if (p > 6 && TextUtils.regionMatchesAsciiIgnoreCase(s, p - 6, "doctyp")) {
                                state = tag;
                            }
                        }
                        default -> {}
                    }
                }
                default -> {
                    if (c == '>' && inQuote == 0 && p >= 2 && prev == '-' && s.charAt(p - 2) == '-') {
                        inQuote = 0;
                        state = text;
                    }
                }
            }
        }
        return out.toString();
    }

    /** remove_breaks: each run of CR, LF, tab and space becomes one space. */
    static String collapseBreaks(String s) {
        StringBuilder out = new StringBuilder(s.length());
        boolean inRun = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean breakChar = c == '\r' || c == '\n' || c == '\t' || c == ' ';
            if (breakChar) {
                if (!inRun) {
                    out.append(' ');
                }
            } else {
                out.append(c);
            }
            inRun = breakChar;
        }
        return out.toString();
    }
}
