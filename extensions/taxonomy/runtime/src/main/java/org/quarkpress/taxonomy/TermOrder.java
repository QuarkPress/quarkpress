package org.quarkpress.taxonomy;

/**
 * What a term query orders by (WordPress's {@code orderby}). {@code TERM_GROUP} orders by id, since QuarkPress keeps no
 * term groups; {@code INCLUDE} and {@code SLUG_IN} order by the position in the query's include or slug list and fall
 * back to the name when that list is empty; {@code TERM_ORDER} orders by the assignment's position when the query names
 * posts and by id otherwise; {@code NONE} orders by id.
 */
public enum TermOrder {
    NAME,
    SLUG,
    TERM_GROUP,
    ID,
    COUNT,
    PARENT,
    TAXONOMY,
    DESCRIPTION,
    INCLUDE,
    SLUG_IN,
    TERM_ORDER,
    NONE;

    /**
     * WordPress's reading of an {@code orderby} text, ignoring the case of A to Z: {@code term_id}, {@code id},
     * {@code term_taxonomy_id} and an empty or absent text are the id; an unknown name is the name.
     */
    public static TermOrder parse(String text) {
        if (text == null) {
            return ID;
        }
        return switch (asciiLower(text)) {
            case "", "id", "term_id", "term_taxonomy_id" -> ID;
            case "name" -> NAME;
            case "slug" -> SLUG;
            case "term_group" -> TERM_GROUP;
            case "count" -> COUNT;
            case "parent" -> PARENT;
            case "taxonomy" -> TAXONOMY;
            case "description" -> DESCRIPTION;
            case "include" -> INCLUDE;
            case "slug__in" -> SLUG_IN;
            case "term_order" -> TERM_ORDER;
            case "none" -> NONE;
            default -> NAME;
        };
    }

    /** The text with only the letters A to Z lowered, as PHP lowers it. */
    private static String asciiLower(String text) {
        StringBuilder lower = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            lower.append(c >= 'A' && c <= 'Z' ? (char) (c + ('a' - 'A')) : c);
        }
        return lower.toString();
    }
}
