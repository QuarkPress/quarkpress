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

    /** WordPress's route key of {@code /wp/v2/themes/{stylesheet}}, as its index prints it. */
    static final String STYLESHEET = "/themes/(?P<stylesheet>[^\\/:<>\\*\\?\"\\|]+(?:\\/[^\\/:<>\\*\\?\"\\|]+)?)";

    @Test
    void blockTypeNamespaces() {
        RoutePattern namespace = RoutePattern.parse("wp/v2", "/block-types/(?P<namespace>[a-zA-Z0-9_-]+)");
        assertThat(namespace.key()).isEqualTo("/wp/v2/block-types/(?P<namespace>[a-zA-Z0-9_-]+)");
        assertThat(namespace.template()).isEqualTo("/wp/v2/block-types/{namespace}");
        assertThat(namespace.match("/wp/v2/block-types/core")).contains(Map.of("namespace", "core"));
        assertThat(namespace.match("/wp/v2/block-types/My_ns-2")).contains(Map.of("namespace", "My_ns-2"));
        assertThat(namespace.match("/wp/v2/block-types/a/b")).isEmpty();
        assertThat(namespace.match("/wp/v2/block-types/a.b")).isEmpty();
        assertThat(namespace.match("/wp/v2/block-types/")).isEmpty();
        RoutePattern item =
                RoutePattern.parse("wp/v2", "/block-types/(?P<namespace>[a-zA-Z0-9_-]+)/(?P<name>[a-zA-Z0-9_-]+)");
        assertThat(item.match("/wp/v2/block-types/core/paragraph"))
                .contains(Map.of("namespace", "core", "name", "paragraph"));
        assertThat(item.match("/wp/v2/block-types/core/paragraph").orElseThrow().keySet())
                .containsExactly("namespace", "name");
    }

    @Test
    void twoSegmentNames() {
        RoutePattern renderer = RoutePattern.parse("wp/v2", "/block-renderer/(?P<name>[a-z0-9-]+/[a-z0-9-]+)");
        assertThat(renderer.key()).isEqualTo("/wp/v2/block-renderer/(?P<name>[a-z0-9-]+/[a-z0-9-]+)");
        assertThat(renderer.template()).isEqualTo("/wp/v2/block-renderer/{name}");
        assertThat(renderer.match("/wp/v2/block-renderer/core/archives")).contains(Map.of("name", "core/archives"));
        // the route expressions are case-insensitive, their classes included
        assertThat(renderer.match("/wp/v2/block-renderer/Core/Archives")).contains(Map.of("name", "Core/Archives"));
        assertThat(renderer.match("/wp/v2/block-renderer/qp-it/notice-2")).contains(Map.of("name", "qp-it/notice-2"));
        assertThat(renderer.match("/wp/v2/block-renderer/core/archives\n")).contains(Map.of("name", "core/archives"));
        for (String name : new String[] {"core", "core/a/b", "core/a_b", "/core/", "core/", "/core", "core//a"}) {
            assertThat(renderer.match("/wp/v2/block-renderer/" + name)).as(name).isEmpty();
        }
        RoutePattern below = RoutePattern.parse("x", "/(?P<name>[a-z0-9-]+/[a-z0-9-]+)/end");
        assertThat(below.template()).isEqualTo("/x/{name}/end");
        assertThat(below.match("/x/a/b/end")).contains(Map.of("name", "a/b"));
        assertThat(below.match("/x/a/end")).isEmpty();
        assertThat(below.match("/x/a/b/c/end")).isEmpty();
    }

    @Test
    void stylesheets() {
        RoutePattern theme = RoutePattern.parse("wp/v2", STYLESHEET);
        assertThat(theme.key()).isEqualTo("/wp/v2" + STYLESHEET);
        assertThat(theme.template()).isEqualTo("/wp/v2/themes/{stylesheet}");
        assertThat(theme.match("/wp/v2/themes/quarkpress")).contains(Map.of("stylesheet", "quarkpress"));
        assertThat(theme.match("/wp/v2/themes/parent/child")).contains(Map.of("stylesheet", "parent/child"));
        String other = "a b.%20_" + new String(Character.toChars(0xE9)) + "\\";
        assertThat(theme.match("/wp/v2/themes/" + other)).contains(Map.of("stylesheet", other));
        // a final line feed is a character of the group, which the greedy class takes before the end matches
        assertThat(theme.match("/wp/v2/themes/quarkpress\n")).contains(Map.of("stylesheet", "quarkpress\n"));
        for (String stylesheet :
                new String[] {"a/b/c", "a:b", "a*b", "a<b", "a>b", "a?b", "a\"b", "a|b", "", "a/", "/a", "a//b", "a/b:c"
                }) {
            assertThat(theme.match("/wp/v2/themes/" + stylesheet))
                    .as(stylesheet)
                    .isEmpty();
        }
        RoutePattern below =
                RoutePattern.parse("x", "/s/(?P<stylesheet>[^\\/:<>\\*\\?\"\\|]+(?:\\/[^\\/:<>\\*\\?\"\\|]+)?)/v");
        assertThat(below.match("/x/s/a/v")).contains(Map.of("stylesheet", "a"));
        assertThat(below.match("/x/s/a/b/v")).contains(Map.of("stylesheet", "a/b"));
        assertThat(below.match("/x/s/a/b/c/v")).isEmpty();
        assertThat(below.match("/x/s/v")).isEmpty();
    }

    @Test
    void groupsOverSeveralSegmentsFillTheirSegments() {
        for (String pattern : new String[] {
            "/x/a(?P<name>[a-z0-9-]+/[a-z0-9-]+)",
            "/x/(?P<name>[a-z0-9-]+/[a-z0-9-]+)b",
            "/x/(?P<s>[^\\/:<>\\*\\?\"\\|]+(?:\\/[^\\/:<>\\*\\?\"\\|]+)?)-v",
            "/x/(?P<s>[^\\/:<>\\*\\?\"\\|]+(?:\\/[^\\/:<>\\*\\?\"\\|]+)?)/(?P<t>[^\\/:<>\\*\\?\"\\|]+(?:\\/[^\\/:<>\\*\\?\"\\|]+)?)"
        }) {
            assertThatThrownBy(() -> RoutePattern.parse("wp/v2", pattern))
                    .as(pattern)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageStartingWith("unsupported route pattern: " + pattern);
        }
    }

    @Test
    void longSegmentsStayLinear() {
        String run = "a".repeat(2000);
        RoutePattern renderer = RoutePattern.parse("wp/v2", "/block-renderer/(?P<name>[a-z0-9-]+/[a-z0-9-]+)");
        RoutePattern theme = RoutePattern.parse("wp/v2", STYLESHEET);
        RoutePattern namespace = RoutePattern.parse("wp/v2", "/block-types/(?P<namespace>[a-zA-Z0-9_-]+)");
        String[][] cases = {
            {"/wp/v2/block-renderer/" + run + "/" + run, "renderer"},
            {"/wp/v2/block-renderer/" + run + "/" + run + "/" + run, "renderer"},
            {"/wp/v2/block-renderer/" + run + "_", "renderer"},
            {"/wp/v2/themes/" + run + "/" + run, "theme"},
            {"/wp/v2/themes/" + run + "/" + run + "/" + run, "theme"},
            {"/wp/v2/themes/" + run + ":", "theme"},
            {"/wp/v2/block-types/" + run, "namespace"},
            {"/wp/v2/block-types/" + run + ".", "namespace"}
        };
        for (String[] path : cases) {
            RoutePattern pattern = switch (path[1]) {
                case "renderer" -> renderer;
                case "theme" -> theme;
                default -> namespace;
            };
            long[] examined = new long[1];
            pattern.match(path[0], examined);
            // a bound on the characters looked at, not on wall time (which this host's load makes unreliable)
            assertThat(examined[0]).as(path[0]).isLessThanOrEqualTo(3L * path[0].length());
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
