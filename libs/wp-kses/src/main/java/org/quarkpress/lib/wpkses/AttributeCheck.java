package org.quarkpress.lib.wpkses;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * One value check of an {@link AttributeRule}. Checks see the attribute value as kses re-encoded it, so {@code &}
 * counts as the five characters {@code &amp;}.
 */
public sealed interface AttributeCheck {

    /** {@code maxlen}: at most this many UTF-8 bytes. */
    record MaxLength(long bytes) implements AttributeCheck {}

    /** {@code minlen}: at least this many UTF-8 bytes. */
    record MinLength(long bytes) implements AttributeCheck {}

    /** {@code maxval}: a small whole number (up to six digits, up to six spaces around it) of at most this value. */
    record MaxValue(long max) implements AttributeCheck {}

    /** {@code minval}: a small whole number of at least this value. */
    record MinValue(long min) implements AttributeCheck {}

    /** {@code valueless}: "y" requires a boolean attribute, "n" one with a value; compared without case. */
    record Valueless(String flag) implements AttributeCheck {
        public Valueless {
            Objects.requireNonNull(flag, "flag");
        }
    }

    /** {@code values}: the lowercased value must equal one entry exactly (entries with uppercase never match). */
    record Values(List<String> allowed) implements AttributeCheck {
        public Values {
            allowed = List.copyOf(allowed);
        }
    }

    /** {@code value_callback}: the value must pass the test. */
    record Callback(Predicate<String> test) implements AttributeCheck {
        public Callback {
            Objects.requireNonNull(test, "test");
        }
    }

    /**
     * WordPress's {@code _wp_kses_allow_pdf_objects} callback: a .pdf URL on the host of the configured upload base
     * URL, without query or fragment. Never passes when no upload base URL is configured.
     */
    record PdfObjectUrl() implements AttributeCheck {}

    static AttributeCheck maxLength(long bytes) {
        return new MaxLength(bytes);
    }

    static AttributeCheck minLength(long bytes) {
        return new MinLength(bytes);
    }

    static AttributeCheck maxValue(long max) {
        return new MaxValue(max);
    }

    static AttributeCheck minValue(long min) {
        return new MinValue(min);
    }

    static AttributeCheck valueless(String flag) {
        return new Valueless(flag);
    }

    static AttributeCheck values(List<String> allowed) {
        return new Values(allowed);
    }

    static AttributeCheck callback(Predicate<String> test) {
        return new Callback(test);
    }

    static AttributeCheck pdfObjectUrl() {
        return new PdfObjectUrl();
    }
}
