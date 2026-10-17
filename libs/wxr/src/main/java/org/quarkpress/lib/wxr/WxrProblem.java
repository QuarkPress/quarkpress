package org.quarkpress.lib.wxr;

/**
 * A recovery or notice recorded while reading. Line and column are 1-based positions in the decoded text; after
 * repairs earlier on the same line the column is approximate; -1 when unknown.
 */
public record WxrProblem(Kind kind, long line, long column, String detail) {

    public enum Kind {
        /** Bytes not valid in the file's encoding were replaced with U+FFFD. */
        INVALID_ENCODING,
        /** The declared encoding is unknown to the JVM; the file was read as UTF-8. */
        UNSUPPORTED_ENCODING,
        /** A character not allowed in XML 1.0 (or a reference to one) was replaced with U+FFFD. */
        ILLEGAL_CHARACTER,
        /** A {@code &} that starts no reference was read as text. */
        BARE_AMPERSAND,
        /** A named reference other than the five XML entities was kept as text. */
        UNDEFINED_ENTITY,
        /** Markup XML forbids in that place ({@code ]]>} in text, {@code --} in a comment) was escaped or split. */
        INVALID_MARKUP,
        /** A {@code <} that starts no markup was read as text. */
        BARE_LESS_THAN,
        /** A DOCTYPE was skipped (lenient) or refused (strict); DTDs are never processed. */
        DOCTYPE,
        /**
         * The {@code wp} or {@code excerpt} prefix is bound to a URI WordPress never used, or a prefix WordPress
         * writes is used without being declared (lenient mode only); read anyway.
         */
        UNKNOWN_NAMESPACE,
        /** The root is not {@code rss}, there is no {@code channel}, or the input has no element at all. */
        NOT_WXR,
        /** No {@code wp:wxr_version} before the first entry; the WordPress Importer rejects such files. */
        VERSION_MISSING,
        /** {@code wp:wxr_version} is not digits.digits; the WordPress Importer rejects such files. */
        VERSION_INVALID,
        /** {@code wp:wxr_version} is newer than 1.2; read anyway. */
        VERSION_NEWER,
        /** The XML is broken before its end; reading stopped there. */
        MALFORMED,
        /** The file ends before the document does; reading stopped there. */
        TRUNCATED,
        /** More than {@value ProblemLog#MAX_STORED} problems; the rest are only counted. */
        TOO_MANY_PROBLEMS,
        /**
         * A value is longer than {@link WxrReadOptions#maxCharsPerValue()}; thrown, except that a lenient read cuts off
         * an overlong comment, processing instruction, attribute name or attribute value and records this instead.
         */
        VALUE_TOO_LARGE,
        /** More items than {@link WxrReadOptions#maxItems()}; always thrown. */
        TOO_MANY_ITEMS
    }
}
