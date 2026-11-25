package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/**
 * PHP's {@code max_input_vars} (1000) and the next free index of an array, as PHP 8.4 parses a query string
 * ({@code $_GET}, {@code parse_str}) and an urlencoded form ({@code $_POST}); every expectation recorded with
 * {@code /usr/bin/php} (a {@code php -S} probe and {@code parse_str}).
 */
class PhpUrlsInputVarsTest {

    /** {@code count} pairs made of {@code pattern} with {@code #} replaced by 0, 1, … joined by {@code &}. */
    private static String pairs(int count, String pattern) {
        return IntStream.range(0, count)
                .mapToObj(i -> pattern.replace("#", Integer.toString(i)))
                .collect(Collectors.joining("&"));
    }

    @Test
    void aQueryKeepsItsFirstThousandVariables() {
        Map<String, Object> full = PhpUrls.parseStr(pairs(1000, "v#=1") + "&last=x");
        assertThat(full).hasSize(1000).doesNotContainKey("last").containsKey("v999");
        assertThat(PhpUrls.parseStr(pairs(999, "v#=1") + "&last=x"))
                .hasSize(1000)
                .containsEntry("last", "x");
        // empty pairs are not counted, pairs without a name are
        assertThat(PhpUrls.parseStr(pairs(999, "v#=1") + "&&&&&&last=x")).containsEntry("last", "x");
        assertThat(PhpUrls.parseStr("&&&&" + pairs(999, "v#=1") + "&last=x")).containsEntry("last", "x");
        assertThat(PhpUrls.parseStr(pairs(999, "=x") + "&post_password=s3").toString())
                .isEqualTo("{post_password=s3}");
        assertThat(PhpUrls.parseStr(pairs(1000, "=x") + "&post_password=s3")).isEmpty();
        // a nested variable counts once per pair
        Map<String, Object> nested = PhpUrls.parseStr(pairs(1000, "a[]=1") + "&post_password=s3");
        assertThat(nested).containsOnlyKeys("a");
        assertThat((Map<?, ?>) nested.get("a")).hasSize(1000);
        assertThat(PhpUrls.parseStr(pairs(999, "a[]=1") + "&post_password=s3")).containsEntry("post_password", "s3");
    }

    @Test
    void aFormKeepsOneVariableMoreAndCountsEmptyPairs() {
        assertThat(PhpUrls.parsePost(pairs(1000, "v#=1") + "&post_password=s3"))
                .hasSize(1001)
                .containsEntry("post_password", "s3");
        Map<String, Object> over = PhpUrls.parsePost(pairs(1001, "v#=1") + "&post_password=s3");
        assertThat(over).hasSize(1001).doesNotContainKey("post_password").containsKey("v1000");
        assertThat(PhpUrls.parsePost(pairs(1002, "v#=1") + "&post_password=s3"))
                .hasSize(1001)
                .doesNotContainKey("post_password");
        Map<String, Object> appended = PhpUrls.parsePost(pairs(1001, "a[]=1") + "&post_password=s3");
        assertThat(appended).containsOnlyKeys("a");
        assertThat((Map<?, ?>) appended.get("a")).hasSize(1001);
        assertThat(PhpUrls.parsePost(pairs(1000, "a[]=1") + "&post_password=s3"))
                .containsEntry("post_password", "s3");
        // in a form every pair counts, an empty one too (999 variables, five empty pairs: the password is the 1005th)
        assertThat(PhpUrls.parsePost(pairs(999, "v#=1") + "&&&&&&post_password=s3"))
                .hasSize(999)
                .doesNotContainKey("post_password");
        assertThat(PhpUrls.parsePost(pairs(1000, "=x") + "&post_password=s3&z=1")
                        .toString())
                .isEqualTo("{post_password=s3}");
        assertThat(PhpUrls.parsePost("post_password=s3&" + pairs(5000, "v#=1")))
                .hasSize(1001)
                .containsEntry("post_password", "s3")
                .containsKey("v999")
                .doesNotContainKey("v1000");
        // a trailing & is no pair, a leading one is an empty pair
        assertThat(PhpUrls.parsePost(pairs(1000, "v#=1") + "&post_password=s3&"))
                .containsEntry("post_password", "s3");
        assertThat(PhpUrls.parsePost("&" + pairs(1000, "v#=1") + "&post_password=s3"))
                .hasSize(1000)
                .doesNotContainKey("post_password");
    }

    @Test
    void aHundredThousandAppendsAreParsedQuickly() {
        String body = "a[]=&".repeat(100_000);
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            for (int round = 0; round < 10; round++) {
                assertThat((Map<?, ?>) PhpUrls.parseStr(body).get("a")).hasSize(1000);
                assertThat((Map<?, ?>) PhpUrls.parsePost(body).get("a")).hasSize(1001);
            }
        });
    }

    @Test
    void anAppendTakesTheArraysNextFreeIndex() {
        assertThat(PhpUrls.parseStr("a[5]=x&a[]=y").toString()).isEqualTo("{a={5=x, 6=y}}");
        assertThat(PhpUrls.parseStr("a[05]=x&a[]=y").toString()).isEqualTo("{a={05=x, 0=y}}");
        assertThat(PhpUrls.parseStr("a[-5]=x&a[]=y").toString()).isEqualTo("{a={-5=x, -4=y}}");
        assertThat(PhpUrls.parseStr("a[9223372036854775807]=x&a[]=y").toString())
                .isEqualTo("{a={9223372036854775807=x}}");
        assertThat(PhpUrls.parseStr("a[x]=1&a[]=2&a[3]=3&a[]=4").toString()).isEqualTo("{a={x=1, 0=2, 3=3, 4=4}}");
        assertThat(PhpUrls.parseStr("a[0]=1&a[]=2&a[0]=3&a[]=4").toString()).isEqualTo("{a={0=3, 1=2, 2=4}}");
        assertThat(PhpUrls.parseStr("a[1e3]=1&a[]=2").toString()).isEqualTo("{a={1e3=1, 0=2}}");
        assertThat(PhpUrls.parseStr("a[ 7]=1&a[]=2").toString()).isEqualTo("{a={ 7=1, 0=2}}");
        assertThat(PhpUrls.parseStr("a[2]=1&a[2][]=x&a[2][]=y").toString()).isEqualTo("{a={2={0=x, 1=y}}}");
        assertThat(PhpUrls.parseStr("a[]=1&a=s&a[]=2").toString()).isEqualTo("{a={0=2}}");
    }
}
