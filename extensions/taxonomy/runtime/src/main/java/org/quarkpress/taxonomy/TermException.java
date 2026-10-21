package org.quarkpress.taxonomy;

import java.util.Objects;
import java.util.Optional;
import org.quarkpress.core.TermId;

/**
 * A term write or read refused for its input, with WordPress's error code: {@code invalid_taxonomy}, {@code
 * invalid_term} (no such term in the taxonomy on this site), {@code empty_term_name}, {@code invalid_term_name} (a
 * name that sanitizes to nothing), {@code term_exists} (a duplicate name; {@link #existing()} names the term),
 * {@code duplicate_term_slug}, {@code missing_parent}, {@code rest_taxonomy_not_hierarchical} (a parent in a flat
 * taxonomy), or {@code db_insert_error} / {@code db_update_error} (a value its column cannot hold). The messages are
 * WordPress's English ones.
 */
public class TermException extends IllegalArgumentException {

    private static final long serialVersionUID = 1L;

    private final String code;
    private final String detail;
    private final Long existing;

    public TermException(String code, String message) {
        this(code, message, "", Optional.empty());
    }

    public TermException(String code, String message, String detail, Optional<TermId> existing) {
        super(message);
        this.code = Objects.requireNonNull(code, "code");
        this.detail = Objects.requireNonNull(detail, "detail");
        this.existing =
                Objects.requireNonNull(existing, "existing").map(TermId::value).orElse(null);
    }

    /** WordPress's error code. */
    public String code() {
        return code;
    }

    /** The taxonomy of an {@code invalid_taxonomy}, the database's reason for a failed write; empty otherwise. */
    public String detail() {
        return detail;
    }

    /** For {@code term_exists}, the term that holds the name already. */
    public Optional<TermId> existing() {
        return Optional.ofNullable(existing).map(TermId::new);
    }

    public static TermException invalidTaxonomy(String taxonomy) {
        return new TermException("invalid_taxonomy", "Invalid taxonomy.", taxonomy, Optional.empty());
    }

    public static TermException invalidTerm() {
        return new TermException("invalid_term", "Empty Term.");
    }

    public static TermException emptyName() {
        return new TermException("empty_term_name", "A name is required for this term.");
    }

    public static TermException invalidName() {
        return new TermException("invalid_term_name", "Invalid term name.");
    }

    public static TermException exists(TermId existing, boolean hierarchical) {
        return new TermException(
                "term_exists",
                hierarchical
                        ? "A term with the name provided already exists with this parent."
                        : "A term with the name provided already exists in this taxonomy.",
                "",
                Optional.of(existing));
    }

    public static TermException duplicateSlug(String slug) {
        return new TermException(
                "duplicate_term_slug", "The slug &#8220;" + slug + "&#8221; is already in use by another term.");
    }

    public static TermException missingParent() {
        return new TermException("missing_parent", "Parent term does not exist.");
    }

    public static TermException notHierarchical() {
        return new TermException(
                "rest_taxonomy_not_hierarchical", "Cannot set parent term, taxonomy is not hierarchical.");
    }

    /** A value that does not fit its column, as WordPress's failed database write reports it. */
    public static TermException dbError(String detail) {
        return new TermException(
                "db_insert_error", "Could not insert term into the database.", detail, Optional.empty());
    }

    /** A value that does not fit its column on an update, as WordPress's failed database write reports it. */
    public static TermException dbUpdateError(String detail) {
        return new TermException("db_update_error", "Could not update term in the database.", detail, Optional.empty());
    }
}
