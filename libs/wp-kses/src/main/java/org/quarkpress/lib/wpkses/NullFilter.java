package org.quarkpress.lib.wpkses;

/** wp_kses_no_null: removes control characters and, optionally, backslash-zero runs. */
final class NullFilter {

    private NullFilter() {}

    /** True for the C0 controls that are removed: everything below 0x20 except tab, line feed and carriage return. */
    static boolean isRemovedControl(char c) {
        return c < 0x20 && c != '\t' && c != '\n' && c != '\r';
    }

    /** Removes control characters, then backslash-zero runs unless {@code keepSlashZero}. */
    static String clean(String s, boolean keepSlashZero) {
        String withoutControls = removeControls(s);
        return keepSlashZero ? withoutControls : removeSlashZero(withoutControls);
    }

    /** Removes C0 control characters other than tab, line feed and carriage return. */
    static String removeControls(String s) {
        int first = 0;
        while (first < s.length() && !isRemovedControl(s.charAt(first))) {
            first++;
        }
        if (first == s.length()) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length()).append(s, 0, first);
        for (int i = first; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!isRemovedControl(c)) {
                out.append(c);
            }
        }
        return out.toString();
    }

    /** Each run of backslashes that is followed by a run of '0' characters is removed with those zeros. */
    static String removeSlashZero(String s) {
        if (s.indexOf('\\') < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        int n = s.length();
        while (i < n) {
            char c = s.charAt(i);
            if (c != '\\') {
                out.append(c);
                i++;
                continue;
            }
            int j = i;
            while (j < n && s.charAt(j) == '\\') {
                j++;
            }
            if (j < n && s.charAt(j) == '0') {
                while (j < n && s.charAt(j) == '0') {
                    j++;
                }
            } else {
                out.append(s, i, j);
            }
            i = j;
        }
        return out.toString();
    }
}
