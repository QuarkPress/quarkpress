package org.quarkpress.posts;

/**
 * What a query orders by: WordPress's orderby keys. Keys are applied in the order given, then the id breaks ties in the
 * first key's direction. {@code DATE} is the post date (the modification date while a draft's date floats), {@code
 * TITLE} ignores case, and {@code AUTHOR} and {@code PARENT} count a missing one as 0. {@code POST_IN} orders by
 * position in the post-in list (its direction is ignored) and is skipped without that list. {@code RELEVANCE} ranks
 * search results when it comes first, and the keys given after it are then dropped; anywhere else, or without a
 * search, it is skipped. Its direction applies to the date and id that follow the rank, never to the rank itself. A
 * search with no key orders as a descending {@code RELEVANCE}. A search of excluded terms alone has no rank, so a
 * leading {@code RELEVANCE} then orders by date and id; WordPress fails to build that query and finds nothing.
 * {@code NONE} anywhere in the list orders nothing.
 *
 * <p>When every key is skipped the query orders newest first, as with no key at all. WordPress orders nothing then
 * when the keys come as a list, and newest first when they come as one string; QuarkPress picks newest first so that
 * paging stays stable.
 */
public enum OrderKey {
    DATE,
    MODIFIED,
    TITLE,
    NAME,
    AUTHOR,
    PARENT,
    TYPE,
    ID,
    MENU_ORDER,
    POST_IN,
    RELEVANCE,
    NONE
}
