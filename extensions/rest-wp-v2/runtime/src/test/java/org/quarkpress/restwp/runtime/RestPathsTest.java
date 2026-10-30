package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RestPathsTest {

    @Test
    void urlForms() {
        assertThat(RestPaths.route("/wp-json", "/", "/wp-json")).contains("/");
        assertThat(RestPaths.route("/wp-json/wp/v2/types/", "/", "/wp-json")).contains("/wp/v2/types");
        assertThat(RestPaths.route("/index.php/wp-json/wp/v2", "/", "/wp-json")).contains("/wp/v2");
        assertThat(RestPaths.route("/news/wp-json/wp/v2", "/news/", "/wp-json")).contains("/wp/v2");
        assertThat(RestPaths.route("/foo/wp-json/wp/v2", "/", "/wp-json")).isEmpty();
        assertThat(RestPaths.trimRoute("/wp/v2//")).isEqualTo("/wp/v2");
        assertThat(RestPaths.trimRoute("")).isEqualTo("/");
        assertThat(RestPaths.trimRoute("//wp/v2")).isEqualTo("//wp/v2");
        assertThat(RestPaths.route("/it-plain/x/wp-json/ping", "/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/news-typo/wp-json/wp/v2", "/", "/wp-json")).isEmpty();
    }

    @Test
    void theBaseCountsOnlyRightAfterTheSitePathAndAsAWholeSegment() {
        assertThat(RestPaths.route("/wp-json/", "/", "/wp-json")).contains("/");
        assertThat(RestPaths.route("/wp-jsonx/wp/v2", "/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/WP-JSON/wp/v2", "/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/News/wp-json/wp/v2", "/news/", "/wp-json")).contains("/wp/v2");
        assertThat(RestPaths.route("/news/index.php/wp-json", "/news/", "/wp-json"))
                .contains("/");
        assertThat(RestPaths.route("/wp-json/wp/v2", "/news/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/news", "/news/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/api/v1/wp/v2", "/", "/api/v1/")).contains("/wp/v2");
        assertThat(RestPaths.route("/wp-json/wp/v2/\\", "/", "/wp-json")).contains("/wp/v2");
    }

    @Test
    void pathTricksNeverMakeAnotherPrefixCount() {
        // double slashes, dot segments and encoded slashes stay what they are: never the base after the site path
        assertThat(RestPaths.route("//wp-json/wp/v2", "/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/news//wp-json/wp/v2", "/news/", "/wp-json"))
                .isEmpty();
        assertThat(RestPaths.route("/news/../wp-json/wp/v2", "/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/./wp-json/wp/v2", "/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/news%2Fwp-json/wp/v2", "/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/news%2fwp-json/wp/v2", "/news/", "/wp-json"))
                .isEmpty();
        assertThat(RestPaths.route("/wp-json%2Fwp/v2", "/", "/wp-json")).isEmpty();
        assertThat(RestPaths.route("/%77p-json/wp/v2", "/", "/wp-json")).isEmpty();
        // a non-ASCII letter that folds to an ASCII one never matches the site path
        assertThat(RestPaths.route("/new" + new String(Character.toChars(0x17F)) + "/wp-json", "/news/", "/wp-json"))
                .isEmpty();
        // what follows the base is the route, still encoded, dot segments and all
        assertThat(RestPaths.route("/wp-json/../news/wp-json/x", "/", "/wp-json"))
                .contains("/../news/wp-json/x");
        assertThat(RestPaths.route("/wp-json//wp/v2", "/", "/wp-json")).contains("//wp/v2");
    }

    @Test
    void theBaseIsNormalizedAndCannotBeTheSiteItself() {
        assertThat(RestPaths.normalizeBase("wp-json/")).isEqualTo("/wp-json");
        assertThat(RestPaths.normalizeBase("/a/b")).isEqualTo("/a/b");
        assertThatThrownBy(() -> RestPaths.normalizeBase("/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("quarkpress.rest-wp-v2.path must name a path below the site, not /");
    }

    @Test
    void theNonApplicationRootFollowsTheRecordedPath() {
        try {
            RestPaths.installNonApplicationRoot("/q");
            assertThat(RestPaths.underNonApplicationRoot("/q/health")).isTrue();
            assertThat(RestPaths.underNonApplicationRoot("/q")).isTrue();
            assertThat(RestPaths.underNonApplicationRoot("/qx/health")).isFalse();
            assertThat(RestPaths.underNonApplicationRoot("/wp-json")).isFalse();
            RestPaths.installNonApplicationRoot("internal/");
            assertThat(RestPaths.underNonApplicationRoot("/internal/metrics")).isTrue();
            assertThat(RestPaths.underNonApplicationRoot("/q/health")).isFalse();
            RestPaths.installNonApplicationRoot("/");
            assertThat(RestPaths.underNonApplicationRoot("/q/health")).isFalse();
            assertThat(RestPaths.underNonApplicationRoot("/")).isFalse();
        } finally {
            RestPaths.installNonApplicationRoot("/q/");
        }
    }

    @Test
    void theEntryPathLiesBelowTheHttpRootAndTheRestApplicationPath() {
        assertThat(RestPaths.entryPath("/", "/")).isEqualTo("/_quarkpress/rest-wp-v2");
        assertThat(RestPaths.entryPath("/", "/api")).isEqualTo("/api/_quarkpress/rest-wp-v2");
        assertThat(RestPaths.entryPath("/app/", "api/")).isEqualTo("/app/api/_quarkpress/rest-wp-v2");
        assertThat(RestPaths.entryPath("/app", "/")).isEqualTo("/app/_quarkpress/rest-wp-v2");
        assertThatThrownBy(() -> RestPaths.entryPath("/", "/api/:id"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the REST entry path /api/:id/_quarkpress/rest-wp-v2 (quarkus.http.root-path /, REST"
                        + " application path /api/:id) cannot be re-routed to; use a plain path");
    }
}
