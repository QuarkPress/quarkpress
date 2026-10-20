package org.quarkpress.posts;

/**
 * The date a condition compares, as WordPress names its columns: the local post date ({@code post_date}), its GMT
 * form ({@code post_date_gmt}), and the modification date, local or GMT. A local date is the stored instant in the
 * site's zone; a draft whose date floats has no GMT post date, and its local post date is its modification date.
 */
public enum DateColumn {
    DATE,
    DATE_GMT,
    MODIFIED,
    MODIFIED_GMT
}
