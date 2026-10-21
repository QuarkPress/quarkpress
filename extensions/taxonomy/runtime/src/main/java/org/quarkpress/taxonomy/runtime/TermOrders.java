package org.quarkpress.taxonomy.runtime;

/** WordPress's normalization of a term query's direction. */
public final class TermOrders {

    private TermOrders() {}

    /** Ascending only for "ASC" in any letter case; anything else, the empty text and null included, is descending. */
    public static boolean ascending(String order) {
        return order != null && TermText.asciiLower(order).equals("asc");
    }
}
