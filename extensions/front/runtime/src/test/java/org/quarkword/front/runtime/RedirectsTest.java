package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * The pieces WordPress builds its redirects from, each with WordPress 7.1.3's answer (redirects.out in the slice
 * folder): the {@code Location} sanitising, PHP's query-string handling, and the end-of-URL normalisations of the
 * canonical redirect.
 */
class RedirectsTest {

    @Test
    void locationSanitising() {
        assertThat(RedirectUrls.sanitize("http://wp.test/a b")).isEqualTo("http://wp.test/a%20b");
        assertThat(RedirectUrls.sanitize("http://wp.test/a\r\nb")).isEqualTo("http://wp.test/ab");
        assertThat(RedirectUrls.sanitize("http://wp.test/a%0d%0aLocation:x")).isEqualTo("http://wp.test/aLocation:x");
        assertThat(RedirectUrls.sanitize("http://wp.test/\"<x>")).isEqualTo("http://wp.test/x");
        assertThat(RedirectUrls.sanitize("http://wp.test/café/")).isEqualTo("http://wp.test/caf%C3%A9/");
        assertThat(Redirect.permanent("http://wp.test/a b")).isEqualTo(new Redirect(301, "http://wp.test/a%20b"));
    }

    @Test
    void parseStr() {
        assertThat(PhpUrls.parseStr("p=1&utm_source=x"))
                .containsExactly(Map.entry("p", "1"), Map.entry("utm_source", "x"));
        assertThat(PhpUrls.parseStr("x=a+b&y=&z"))
                .containsExactly(Map.entry("x", "a b"), Map.entry("y", ""), Map.entry("z", ""));
        assertThat(PhpUrls.parseStr("a%5B%5D=1&a%5B%5D=2&b.c=3").toString()).isEqualTo("{a={0=1, 1=2}, b_c=3}");
        assertThat(PhpUrls.parseStr("a[x]=1&a[y][z]=2").toString()).isEqualTo("{a={x=1, y={z=2}}}");
        assertThat(PhpUrls.parseStr(" lead=1&a b=2").toString()).isEqualTo("{lead=1, a_b=2}");
        assertThat(PhpUrls.parseStr("a[=1").toString()).isEqualTo("{a_=1}");
        assertThat(PhpUrls.parseStr("a]=1").toString()).isEqualTo("{a]=1}");
        assertThat(PhpUrls.parseStr("=x&k").toString()).isEqualTo("{k=}");
        assertThat(PhpUrls.parseStr("p=1&p=2").toString()).isEqualTo("{p=2}");
        assertThat(PhpUrls.parseStr("a=1&a[]=2").toString()).isEqualTo("{a={0=2}}");
        assertThat(PhpUrls.parseStr("q=%zz").toString()).isEqualTo("{q=%zz}");
        assertThat(PhpUrls.text((String) PhpUrls.parseStr("n=%C3%A9").get("n"))).isEqualTo("é");
    }

    @Test
    void parseStrNestingLimit() {
        // PHP's max_input_nesting_level (64): a deeper name drops the whole variable, values set before it included,
        // and later pairs go on (PHP 8 parse_str)
        assertThat(depth(PhpUrls.parseStr("a" + "[x]".repeat(64) + "=1&b=2").get("a")))
                .isEqualTo(64);
        assertThat(PhpUrls.parseStr("a" + "[x]".repeat(65) + "=1&b=2").toString())
                .isEqualTo("{b=2}");
        assertThat(PhpUrls.parseStr("a" + "[x]".repeat(70) + "=1&b=2").toString())
                .isEqualTo("{b=2}");
        assertThat(PhpUrls.parseStr("a" + "[]".repeat(65) + "=1&b=2").toString())
                .isEqualTo("{b=2}");
        assertThat(PhpUrls.parseStr("a=1&a" + "[x]".repeat(65) + "=1&b=2").toString())
                .isEqualTo("{b=2}");
        assertThat(PhpUrls.parseStr("a[x]=1&a" + "[x]".repeat(65) + "=1").toString())
                .isEqualTo("{}");
        assertThat(PhpUrls.parseStr("a" + "[x]".repeat(65) + "=1&a[y]=2").toString())
                .isEqualTo("{a={y=2}}");
        assertThat(PhpUrls.parseStr("a b" + "[x]".repeat(65) + "=1&a_b=3&c=1").toString())
                .isEqualTo("{a_b=3, c=1}");
        // an unclosed bracket is a level too; what follows a closing bracket is ignored
        assertThat(PhpUrls.parseStr("a" + "[x]".repeat(64) + "[=1").toString()).isEqualTo("{}");
        assertThat(PhpUrls.parseStr("a" + "[x]".repeat(64) + "[x]z=1").toString())
                .isEqualTo("{}");
        assertThat(depth(PhpUrls.parseStr("a" + "[x]".repeat(64) + "]=1").get("a")))
                .isEqualTo(64);
        assertThat(depth(PhpUrls.parseStr("a" + "[x]".repeat(64) + "z[x]=1").get("a")))
                .isEqualTo(64);
    }

    private static int depth(Object value) {
        int depth = 0;
        while (value instanceof Map<?, ?> map) {
            depth++;
            value = map.get("x");
        }
        return depth;
    }

    @Test
    void queryArguments() {
        assertThat(PhpUrls.removeQueryArgs("p=1&utm_source=x&x=a b", "p")).isEqualTo("utm_source=x&x=a+b");
        assertThat(PhpUrls.removeQueryArgs("p=1&x=a+b&y=&z", "p")).isEqualTo("x=a+b&y&z");
        assertThat(PhpUrls.removeQueryArgs("foo", "p")).isEqualTo("foo");
        assertThat(PhpUrls.removeQueryArgs("a=b?c&p=1", "p")).isEqualTo("a=b?c");
        assertThat(PhpUrls.removeQueryArgs("p=1&post_type=book&a%5B%5D=1", "p", "post_type"))
                .isEqualTo("a%5B0%5D=1");
        assertThat(PhpUrls.removeQueryArgs("p=1&preview=true", "preview")).isEqualTo("p=1");
        assertThat(PhpUrls.removeQueryArgs("", "x")).isEqualTo("");
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("utm_source", "nl");
        args.put("x", "a%20b");
        assertThat(PhpUrls.addQueryArgs(args, "http://wp.test/2025/11/20/hello-world/"))
                .isEqualTo("http://wp.test/2025/11/20/hello-world/?utm_source=nl&x=a%20b");
        assertThat(PhpUrls.addQueryArgs(Map.of("x", "1"), "http://wp.test/?p=16"))
                .isEqualTo("http://wp.test/?p=16&x=1");
        assertThat(PhpUrls.addQueryArgs(Map.of("x", "1"), "http://wp.test/a/#top"))
                .isEqualTo("http://wp.test/a/?x=1#top");
        assertThat(PhpUrls.addQueryArgs(Map.of("y", ""), "http://wp.test/a/")).isEqualTo("http://wp.test/a/?y");
        assertThat(PhpUrls.removeQueryArgsNotIn("p=16&utm=1", "http://wp.test/?p=16", "p", "name"))
                .isEqualTo("p=16&utm=1");
        assertThat(PhpUrls.removeQueryArgsNotIn("p=16&utm=1", "http://wp.test/x/", "p", "name"))
                .isEqualTo("utm=1");
    }

    @Test
    void reattachedArguments() {
        String permalink = "http://wp.test/2025/11/20/hello-world/";
        assertThat(reattached("utm_source=nl&x=a%20b", permalink)).isEqualTo(permalink + "?utm_source=nl&x=a%20b");
        assertThat(reattached("x=a+b&y=&z", permalink)).isEqualTo(permalink + "?x=a%20b&y&z");
        assertThat(reattached("a%5B%5D=1&a%5B%5D=2&b_c=3", permalink))
                .isEqualTo(permalink + "?a%5B0%5D=1&a%5B1%5D=2&b_c=3");
        assertThat(reattached("name=foo&utm=1", permalink)).isEqualTo(permalink + "?name=foo&utm=1");
        assertThat(reattached("k=%C3%A9&s=a/b", permalink)).isEqualTo(permalink + "?k=%C3%A9&s=a%2Fb");
    }

    private static String reattached(String query, String url) {
        return PhpUrls.addQueryArgs(PhpUrls.rawurlencodeDeep(PhpUrls.parseStr(query)), url);
    }

    @Test
    void endNormalisations() {
        assertThat(CanonicalRedirects.withoutTrailingPunctuation("/2025/11/20/hello-world/."))
                .isEqualTo("/2025/11/20/hello-world/");
        assertThat(CanonicalRedirects.withoutTrailingPunctuation("/a/b.%2E%20")).isEqualTo("/a/b");
        assertThat(CanonicalRedirects.withoutTrailingPunctuation("/a/%2e")).isEqualTo("/a/%2e");
        assertThat(CanonicalRedirects.withoutTrailingPunctuation("/a/x%E2%80%9D"))
                .isEqualTo("/a/x");
        assertThat(CanonicalRedirects.withoutTrailingPunctuation("/a/b!!")).isEqualTo("/a/b");
        assertThat(CanonicalRedirects.withoutTrailingPunctuation("/a.b/")).isEqualTo("/a.b/");
        assertThat(query("p=1.")).isEqualTo("p=1");
        assertThat(query("p=1.&utm=x")).isEqualTo("p=1.&utm=x");
        assertThat(query("utm=x&p=1%2E")).isEqualTo("utm=x&p=1");
        assertThat(query("p=&utm=x")).isEqualTo("utm=x");
        assertThat(query("p=&page_id=&x=1")).isEqualTo("page_id=&x=1");
        assertThat(query("x=1&cat=")).isEqualTo("x=1");
        assertThat(query("tag")).isEqualTo("");
        assertThat(query("p=1&feed=rss")).isEqualTo("p=1&feed=rss2");
        assertThat(query("feed=rss&feed=rss")).isEqualTo("feed=rss2&feed=rss");
        assertThat(query("&&p=&x")).isEqualTo("x");
        assertThat(CanonicalRedirects.withoutTrailingIndex("/index.php", true)).isEqualTo("/");
        assertThat(CanonicalRedirects.withoutTrailingIndex("/index.php//", true))
                .isEqualTo("/");
        assertThat(CanonicalRedirects.withoutTrailingIndex("/a/index.php/", true))
                .isEqualTo("/a/");
        assertThat(CanonicalRedirects.withoutTrailingIndex("/a/index.php/x", true))
                .isEqualTo("/a/index.php/x");
        assertThat(CanonicalRedirects.withoutPagingSuffixes("/page/2/")).isEqualTo("/");
        assertThat(CanonicalRedirects.withoutPagingSuffixes("/category/news/page/3/"))
                .isEqualTo("/category/news/");
        assertThat(CanonicalRedirects.withoutPagingSuffixes("/page/2/page/3/")).isEqualTo("/");
        assertThat(CanonicalRedirects.withoutPagingSuffixes("/page2")).isEqualTo("/");
        assertThat(CanonicalRedirects.withoutPagingSuffixes("/a/feed/")).isEqualTo("/a/");
        assertThat(CanonicalRedirects.withoutPagingSuffixes("/a/comments/feed/"))
                .isEqualTo("/a/");
        assertThat(CanonicalRedirects.withoutPagingSuffixes("/a/feed/x/feed")).isEqualTo("/a/x/");
        assertThat(CanonicalRedirects.withoutPagingSuffixes("/a/comment-page-2/"))
                .isEqualTo("/a/");
        assertThat(CanonicalRedirects.withoutPagingSuffixes("/page/2")).isEqualTo("/");
        assertThat(CanonicalRedirects.rtrimMask("/2025/11/20/hello-world/15/", "15/"))
                .isEqualTo("/2025/11/20/hello-world");
        assertThat(CanonicalRedirects.withoutFragment("http://wp.test/a/#x")).isEqualTo("http://wp.test/a/");
        assertThat(CanonicalRedirects.withoutFragment("http://WP.test:8080/a?b=1#c"))
                .isEqualTo("http://WP.test:8080/a?b=1");
        assertThat(CanonicalRedirects.withoutFragment("/rel#x")).isEqualTo("/rel#x");
        assertThat(CanonicalRedirects.withoutFragment("https://wp.test")).isEqualTo("https://wp.test");
        assertThat(CanonicalRedirects.lowerOctets("http://wp.test/%c3%a9?q=%2F"))
                .isEqualTo("http://wp.test/%c3%a9?q=%2f");
        assertThat(CanonicalRedirects.checkdate(2, 30, 2025)).isFalse();
        assertThat(CanonicalRedirects.checkdate(2, 29, 2024)).isTrue();
    }

    private static String query(String query) {
        String result = CanonicalRedirects.withoutQueryPunctuation(query);
        result = PhpUrls.trim(CanonicalRedirects.withoutEmptyArgs(result), '&');
        result = CanonicalRedirects.withRss2(result);
        return CanonicalRedirects.withoutLeadingQuestionMark(result);
    }

    @Test
    void parseUrl() {
        PhpUrls.Url ported = PhpUrls.parseUrl("http://localhost:8080/x").orElseThrow();
        assertThat(ported.host).isEqualTo("localhost");
        assertThat(ported.port).isEqualTo(8080);
        assertThat(ported.path).isEqualTo("/x");
        assertThat(PhpUrls.parseUrl("http://wp.test").orElseThrow().path).isNull();
        assertThat(PhpUrls.parseUrl("http://wp.test//2025//").orElseThrow().path)
                .isEqualTo("//2025//");
        PhpUrls.Url hostPort = PhpUrls.parseUrl("localhost:80/x").orElseThrow();
        assertThat(hostPort.scheme).isNull();
        assertThat(hostPort.host).isEqualTo("localhost");
        assertThat(hostPort.port).isEqualTo(80);
        assertThat(PhpUrls.parseUrl("http:///x")).isEmpty();
        assertThat(PhpUrls.parseUrl("http://:80/x")).isEmpty();
        PhpUrls.Url schemePath = PhpUrls.parseUrl("a:b").orElseThrow();
        assertThat(schemePath.scheme).isEqualTo("a");
        assertThat(schemePath.path).isEqualTo("b");
        PhpUrls.Url relative = PhpUrls.parseUrl("//h/p?q#f").orElseThrow();
        assertThat(relative.host).isEqualTo("h");
        assertThat(relative.query).isEqualTo("q");
        assertThat(relative.fragment).isEqualTo("f");
    }
}
