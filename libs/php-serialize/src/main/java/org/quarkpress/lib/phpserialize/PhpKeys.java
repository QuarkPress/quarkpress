package org.quarkpress.lib.phpserialize;

/** PHP's rule for array keys given as strings. */
final class PhpKeys {

    private PhpKeys() {}

    /**
     * The integer PHP uses for a string key, or null when the key stays a string: an optional minus sign and
     * decimal digits with no leading zero ({@code 0} alone is fine, {@code -0} is not), within the 64-bit range.
     */
    static Long integerValue(byte[] text) {
        int n = text.length;
        if (n == 0 || n > 20) {
            return null;
        }
        boolean negative = text[0] == '-';
        int first = negative ? 1 : 0;
        if (first == n) {
            return null;
        }
        if (text[first] == '0') {
            return n == 1 ? 0L : null;
        }
        long accumulated = 0; // kept negative so that Long.MIN_VALUE fits
        for (int i = first; i < n; i++) {
            int digit = text[i] - '0';
            if (digit < 0 || digit > 9 || accumulated < (Long.MIN_VALUE + digit) / 10) {
                return null;
            }
            accumulated = accumulated * 10 - digit;
        }
        if (!negative && accumulated == Long.MIN_VALUE) {
            return null;
        }
        return negative ? accumulated : -accumulated;
    }
}
