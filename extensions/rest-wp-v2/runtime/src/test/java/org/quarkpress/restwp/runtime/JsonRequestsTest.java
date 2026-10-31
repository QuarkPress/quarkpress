package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class JsonRequestsTest {

    @Test
    void wordPresssJsonRequestTest() {
        assertThat(JsonRequests.isJson(Optional.of("application/json, */*;q=0.1"), Optional.empty()))
                .isTrue();
        assertThat(JsonRequests.isJson(Optional.empty(), Optional.of("Application/JSON; charset=utf-8")))
                .isTrue();
        assertThat(JsonRequests.isJson(Optional.of("text/html,application/vnd.api+json"), Optional.empty()))
                .isTrue();
        assertThat(JsonRequests.isJson(Optional.of("text/html, */*"), Optional.of("text/plain")))
                .isFalse();
        assertThat(JsonRequests.isJson(Optional.of("application/jsonx"), Optional.empty()))
                .isFalse();
        assertThat(JsonRequests.isJson(Optional.empty(), Optional.empty())).isFalse();
        assertThat(JsonRequests.isJson(Optional.of("text/plain"), Optional.of("application/json")))
                .isTrue();
    }

    @Test
    void headerValuesAsWordPressReadsThem() {
        // each answer is wp_is_json_request() of WordPress 7.1.3 under PHP 8.4 with that Accept header
        String[] json = {
            "application/json+oembed",
            "application/x+json+oembed",
            " application/json",
            "text/html;x= application/json",
            "application/a,b+json",
            "application/json;",
            "application/json\u000b",
            "APPLICATION/JSON",
            "application/a/b+json",
            "application/a_b+json",
            "application/json+oembed;q=1",
            "application/json+json",
            "application/++json",
            "application/x+json,",
            ",application/json",
            "\tapplication/json\t",
            "application/json\r",
            "application/json\n",
            "application/json\f",
        };
        String[] notJson = {
            "application/+json",
            "xapplication/json",
            "text/html;x=application/json",
            "application/json" + cp(0x00a0),
            cp(0x00a0) + "application/json",
            "\u0085application/json",
            "application/json\u0085",
            "application/a b+json",
            "application/" + cp(0x00e9) + "+json",
            "application/a~b+json",
            "application/a{b+json",
            "application/a|b+json",
            "application/a`b+json",
            "application/json+oembedx",
            "application/+",
            "application/jso",
            "",
            "application/x+jsonapplication/json",
        };
        for (String value : json) {
            assertThat(JsonRequests.isJson(Optional.of(value), Optional.empty()))
                    .as(value)
                    .isTrue();
        }
        for (String value : notJson) {
            assertThat(JsonRequests.isJson(Optional.of(value), Optional.empty()))
                    .as(value)
                    .isFalse();
        }
    }

    @Test
    void aLongHeaderIsReadInLinearWork() {
        // overlapping candidates that a backtracking matcher would try one by one
        for (int n : new int[] {1_000, 10_000, 100_000}) {
            String hostile = ",application/".repeat(n) + "+".repeat(n) + "jsonx";
            assertThat(JsonRequests.isJson(Optional.of(hostile), Optional.empty()))
                    .isFalse();
            assertThat(JsonRequests.isJson(Optional.of(hostile + ",application/json"), Optional.empty()))
                    .isTrue();
            // four passes over the value, one step per character each
            assertThat(JsonRequests.steps(hostile)).isLessThanOrEqualTo(4L * hostile.length() + 4);
        }
    }

    private static String cp(int codePoint) {
        return new String(Character.toChars(codePoint));
    }
}
