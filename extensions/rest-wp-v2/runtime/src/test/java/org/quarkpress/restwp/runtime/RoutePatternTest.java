package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

class RoutePatternTest {

    @Test
    void parsesWordPressGroupsAndMatchesCaseInsensitively() {
        RoutePattern item = RoutePattern.parse("wp/v2", "/categories/(?P<id>[\\d]+)");
        assertThat(item.key()).isEqualTo("/wp/v2/categories/(?P<id>[\\d]+)");
        assertThat(item.template()).isEqualTo("/wp/v2/categories/{id}");
        assertThat(item.match("/WP/V2/Categories/12")).contains(Map.of("id", "12"));
        assertThat(item.match("/wp/v2/categories/x")).isEmpty();
        RoutePattern word = RoutePattern.parse("wp/v2", "/types/(?P<type>[\\w-]+)");
        assertThat(word.match("/wp/v2/types/wp-block_x")).contains(Map.of("type", "wp-block_x"));
        assertThat(word.match("/wp/v2/types/a/b")).isEmpty();
        assertThat(word.match("/wp/v2/types/caf" + new String(Character.toChars(0xE9))))
                .isEmpty();
        assertThatThrownBy(() -> RoutePattern.parse("wp/v2", "/x/(?P<id>.+)"))
                .hasMessage("unsupported route pattern: /x/(?P<id>.+)");
    }

    @Test
    void keysJoinNamespaceAndPatternAsWordPressDoes() {
        assertThat(RoutePattern.parse("", "/").key()).isEqualTo("/");
        assertThat(RoutePattern.parse("wp/v2", "").key()).isEqualTo("/wp/v2");
        assertThat(RoutePattern.parse("/qp-test/v1/", "items/").key()).isEqualTo("/qp-test/v1/items");
        assertThat(RoutePattern.parse("wp/v2", "/users/me").match("/wp/v2/users/me"))
                .contains(Map.of());
    }

    @Test
    void literalsFoldOnlyAsciiCase() {
        RoutePattern settings = RoutePattern.parse("wp/v2", "/settings");
        assertThat(settings.match("/WP/V2/SETTINGS")).isPresent();
        // U+017F LATIN SMALL LETTER LONG S upper-cases to 'S' in Java, never in a route expression
        assertThat(settings.match("/wp/v2/" + new String(Character.toChars(0x17F)) + "ettings"))
                .isEmpty();
        // U+212A KELVIN SIGN lower-cases to 'k'
        RoutePattern kind = RoutePattern.parse("wp/v2", "/kind");
        assertThat(kind.match("/wp/v2/" + new String(Character.toChars(0x212A)) + "ind"))
                .isEmpty();
        RoutePattern digits = RoutePattern.parse("wp/v2", "/posts/(?P<id>[\\d]+)");
        // ARABIC-INDIC DIGIT ONE is a digit to Java, not to [\d]
        assertThat(digits.match("/wp/v2/posts/" + new String(Character.toChars(0x661))))
                .isEmpty();
    }

    @Test
    void theWholePathMustMatchExceptOneFinalLineFeed() {
        RoutePattern types = RoutePattern.parse("wp/v2", "/types");
        assertThat(types.match("/wp/v2/types/x")).isEmpty();
        assertThat(types.match("x/wp/v2/types")).isEmpty();
        assertThat(types.match("/wp/v2/type")).isEmpty();
        // as PCRE's $ without the D modifier
        assertThat(types.match("/wp/v2/types\n")).isPresent();
        assertThat(types.match("/wp/v2/types\n\n")).isEmpty();
        RoutePattern item = RoutePattern.parse("wp/v2", "/posts/(?P<id>[\\d]+)");
        assertThat(item.match("/wp/v2/posts/5\n")).contains(Map.of("id", "5"));
        assertThat(item.match("/wp/v2/posts/")).isEmpty();
    }

    @Test
    void groupsGiveBackCharactersForTheRestOfThePattern() {
        RoutePattern suffix = RoutePattern.parse("x", "/(?P<a>[\\w-]+)-end");
        assertThat(suffix.match("/x/a-b-end")).contains(Map.of("a", "a-b"));
        assertThat(suffix.match("/x/-end")).isEmpty();
        RoutePattern nested = RoutePattern.parse("wp/v2", "/posts/(?P<parent>[\\d]+)/revisions/(?P<id>[\\d]+)");
        assertThat(nested.template()).isEqualTo("/wp/v2/posts/{parent}/revisions/{id}");
        assertThat(nested.match("/wp/v2/posts/3/revisions/41")).contains(Map.of("parent", "3", "id", "41"));
        assertThat(nested.match("/wp/v2/posts/3/revisions/41").orElseThrow().keySet())
                .containsExactly("parent", "id");
    }

    @Test
    void twoGroupsInOneSegmentAreRefusedAsTheirMatchingCouldBeQuadratic() {
        for (String pattern : new String[] {
            "/(?P<a>[\\w-]+)-(?P<b>[\\d]+)/end", "/x/(?P<a>[\\d]+)(?P<b>[\\d]+)", "/(?P<a>[\\w-]+)_v(?P<b>[\\w-]+)"
        }) {
            assertThatThrownBy(() -> RoutePattern.parse("x", pattern))
                    .as(pattern)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unsupported route pattern: " + pattern
                            + " (two groups in one path segment could make matching take quadratic time)");
        }
        // one group per segment, as every WordPress route has
        assertThat(RoutePattern.parse("x", "/(?P<a>[\\w-]+)/v/(?P<b>[\\d]+)").template())
                .isEqualTo("/x/{a}/v/{b}");
    }

    @Test
    void everyOtherExpressionIsRefused() {
        for (String pattern : new String[] {
            "/x/(?P<id>[\\d]*)",
            "/x/(?P<id>[a-z]+)",
            "/x/(?P<id>[\\d]+",
            "/x/(\\d+)",
            "/x.y",
            "/x?",
            "/x/(?P<1d>[\\d]+)",
            "/x/(?P<id>[\\d]+)/(?P<id>[\\d]+)",
            "/x|y",
            "/x/[\\d]+",
            "/x/(?P<>[\\d]+)",
            "/x y"
        }) {
            assertThatThrownBy(() -> RoutePattern.parse("wp/v2", pattern))
                    .as(pattern)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("unsupported route pattern: " + pattern);
        }
    }

    @Test
    void matchingIsLinearInThePath() {
        int n = 1_000_000;
        RoutePattern suffix = RoutePattern.parse("x", "/(?P<a>[\\w-]+)-end");
        String run = "-".repeat(n);
        String matching = "/x/" + run + "-end";
        long[] examined = new long[1];
        assertThat(suffix.match(matching, examined)).contains(Map.of("a", run));
        assertThat(examined[0]).isLessThanOrEqualTo(3L * matching.length());
        String failing = "/x/" + run;
        long[] failed = new long[1];
        assertThat(suffix.match(failing, failed)).isEmpty();
        assertThat(failed[0]).isLessThanOrEqualTo(3L * failing.length());
        RoutePattern digits = RoutePattern.parse("x", "/(?P<a>[\\d]+)");
        String lineFeeds = "/x/" + "1".repeat(n) + "\n\n";
        long[] digitsExamined = new long[1];
        assertThat(digits.match(lineFeeds, digitsExamined)).isEmpty();
        assertThat(digitsExamined[0]).isLessThanOrEqualTo(3L * lineFeeds.length());
        String id = "1".repeat(n);
        assertThat(digits.match("/x/" + id + "\n")).contains(Map.of("a", id));
    }
}
