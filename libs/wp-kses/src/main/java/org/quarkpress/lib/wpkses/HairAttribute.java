package org.quarkpress.lib.wpkses;

/** One parsed attribute: the lowercase name and the decoded value, or null for a boolean attribute. */
record HairAttribute(String name, String value) {

    boolean valueless() {
        return value == null;
    }
}
