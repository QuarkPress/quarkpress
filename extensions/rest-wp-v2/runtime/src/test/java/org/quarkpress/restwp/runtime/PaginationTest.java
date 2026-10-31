package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.Paging;

/**
 * WordPress's pagination headers. The expected links are WordPress 7.1.3's answers: REST verification list item 10, and
 * add_query_arg run by PHP 8.4 over urlencode_deep of parse_str's result, then again with page (as its collection
 * controllers build them).
 */
class PaginationTest {

    static final String BASE = "http://localhost/wp-json/wp/v2/tags";

    @Test
    void linksCarryEveryQueryParameterWithPageReplacedInPlace() {
        Map<String, List<String>> headers =
                Pagination.headers(25, 10, 2, BASE, PhpQuery.parse("per_page=10&page=2&_fields=id"));
        assertThat(headers.get("X-WP-Total")).containsExactly("25");
        assertThat(headers.get("X-WP-TotalPages")).containsExactly("3");
        // REST verification list item 10: ONE Link line holding prev and next (it replaces the API-root Link on the
        // response)
        assertThat(headers.get("Link"))
                .containsExactly("<http://localhost/wp-json/wp/v2/tags?per_page=10&page=1&_fields=id>; rel=\"prev\", "
                        + "<http://localhost/wp-json/wp/v2/tags?per_page=10&page=3&_fields=id>; rel=\"next\"");
        assertThat(headers.keySet()).containsExactly("X-WP-Total", "X-WP-TotalPages", "Link");
    }

    @Test
    void encodingAppendedPageAndTheEmptyCollectionQuirk() {
        // REST verification list item 10 confirmed the encoding: indexed brackets percent-encoded, comma as %2C, an
        // absent page appended last
        assertThat(Pagination.headers(30, 10, 1, BASE, PhpQuery.parse("include[]=1&include[]=2&_fields=id,name"))
                        .get("Link"))
                .containsExactly(
                        "<" + BASE + "?include%5B0%5D=1&include%5B1%5D=2&_fields=id%2Cname&page=2>; rel=\"next\"");
        Map<String, List<String>> empty = Pagination.headers(0, 10, 2, BASE, PhpQuery.parse("page=2"));
        assertThat(empty.get("X-WP-TotalPages")).containsExactly("0");
        assertThat(empty.get("Link")).containsExactly("<" + BASE + "?page=0>; rel=\"prev\"");
        assertThat(Paging.currentPage(25, 10)).isEqualTo(4); // offset 25, per_page 10
    }

    @Test
    void prevIsCappedAtTheLastPageAndOnePageHasNoLink() {
        assertThat(Pagination.headers(25, 10, 7, BASE, PhpQuery.parse("")).get("Link"))
                .containsExactly("<" + BASE + "?page=3>; rel=\"prev\"");
        Map<String, List<String>> one = Pagination.headers(10, 10, 1, BASE, PhpQuery.parse("a=1"));
        assertThat(one.get("X-WP-TotalPages")).containsExactly("1");
        assertThat(one).doesNotContainKey("Link");
        assertThat(Pagination.headers(11, 10, 1, BASE, PhpQuery.parse("")).get("X-WP-TotalPages"))
                .containsExactly("2");
        assertThat(Paging.currentPage(0, 10)).isEqualTo(1);
        assertThat(Paging.currentPage(10, 10)).isEqualTo(2);
        assertThat(Paging.currentPage(-5, 10)).isEqualTo(1);
    }

    @Test
    void namesStayAsTheyAreAndTheQueryIsReadBackAsPhpReadsIt() {
        // each line: PHP 8.4 + WordPress 7.1.3 for the same query string, page 3
        assertThat(link("a=0&b=%20&c[]=0")).isEqualTo("a=0&b=+&c%5B0%5D=0&page=3");
        // an empty value is written as the name alone
        assertThat(link("x[]=&y[a]=&PAGE=1")).isEqualTo("x%5B0%5D&y%5Ba%5D&PAGE=1&page=3");
        assertThat(link("include[3]=a&include[]=b&q=")).isEqualTo("include%5B3%5D=a&include%5B4%5D=b&q&page=3");
        // a page array is replaced by the page, in its place
        assertThat(link("page[]=1&x=2")).isEqualTo("page=3&x=2");
        // names are not encoded: a name holding '&' splits when read back, '+' turns into '_'
        assertThat(link("a%26b=1&x[k%20y]=c d&x[]=2&e=%C3%A9&n[a][b]=1"))
                .isEqualTo("a&b=1&x%5Bk y%5D=c+d&x%5B0%5D=2&e=%C3%A9&n%5Ba%5D%5Bb%5D=1&page=3");
        assertThat(link("a%2Bb=1&a%26page=1")).isEqualTo("a_b=1&a&page=3");
        assertThat(link("a=%26&b=%3D%23&s=a+b%2Bc~*()!.-_"))
                .isEqualTo("a=%26&b=%3D%23&s=a+b%2Bc%7E%2A%28%29%21.-_&page=3");
        assertThat(link("x[a=1&b]=2")).isEqualTo("x_a=1&b]=2&page=3");
        assertThat(link("")).isEqualTo("page=3");
    }

    @Test
    void namesWithBytesAHeaderCannotCarryArePercentEncoded() {
        // WordPress writes these bytes raw into the header; QuarkPress encodes them (README Limits)
        assertThat(link("%C3%A9[%C3%A8]=2&k%01x=1")).isEqualTo("%C3%A9%5B%C3%A8%5D=2&k%01x=1&page=3");
    }

    @Test
    void sanitizedListsAreIndexedFromZero() {
        ObjectNode query = PhpQuery.parse("include[5]=1&include[9]=2");
        // as the array sanitizer leaves them: a list
        query.set("include", RestJson.NODES.arrayNode().add(1).add(2));
        assertThat(Pagination.link(BASE, query, 2)).isEqualTo(BASE + "?include%5B0%5D=1&include%5B1%5D=2&page=2");
    }

    @Test
    void linkWorkGrowsLinearlyWithTheQuery() {
        // the work counts every name built, every value encoded and every character read back
        long base = work(250, 16, 30);
        // four times the pairs, four times the key length, four times the values: about four times the work
        assertThat(work(1000, 16, 30)).isLessThanOrEqualTo(base * 9 / 2);
        assertThat(ratio(250, 64, 30, 250, 16, 30)).isLessThanOrEqualTo(4.5);
        assertThat(ratio(250, 16, 120, 250, 16, 30)).isLessThanOrEqualTo(4.5);
        // nesting is bounded (64 levels, as PHP's max_input_nesting_level): at the deepest, a constant factor
        String deep = query(1000, 14, 50, 63);
        long[] work = new long[1];
        String link = Pagination.link(BASE, PhpQuery.parse(deep), 2, work);
        assertThat(link).endsWith("&page=2");
        assertThat(work[0]).isLessThan(64L * 4 * deep.length());
    }

    private static double ratio(int pairs, int key, int value, int basePairs, int baseKey, int baseValue) {
        return (double) work(pairs, key, value) / work(basePairs, baseKey, baseValue);
    }

    private static long work(int pairs, int key, int value) {
        long[] work = new long[1];
        Pagination.link(BASE, PhpQuery.parse(query(pairs, key, value, 3)), 2, work);
        return work[0];
    }

    /** {@code pairs} pairs {@code n<i>[k..k]...=v..v}, {@code depth} brackets deep. */
    private static String query(int pairs, int key, int value, int depth) {
        StringBuilder raw = new StringBuilder();
        String segment = "[" + "k".repeat(key) + "]";
        for (int i = 0; i < pairs; i++) {
            if (i > 0) {
                raw.append('&');
            }
            raw.append('n').append(i).append(segment.repeat(depth)).append('=').append("v".repeat(value));
        }
        return raw.toString();
    }

    private static String link(String raw) {
        String link = Pagination.link(BASE, PhpQuery.parse(raw), 3);
        assertThat(link).startsWith(BASE + "?");
        return link.substring(BASE.length() + 1);
    }
}
