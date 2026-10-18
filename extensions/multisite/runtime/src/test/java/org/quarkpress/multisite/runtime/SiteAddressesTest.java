package org.quarkpress.multisite.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.quarkpress.multisite.runtime.SiteAddresses.candidateDomains;
import static org.quarkpress.multisite.runtime.SiteAddresses.candidatePaths;
import static org.quarkpress.multisite.runtime.SiteAddresses.classify;
import static org.quarkpress.multisite.runtime.SiteAddresses.lookupPath;
import static org.quarkpress.multisite.runtime.SiteAddresses.lookupSegments;
import static org.quarkpress.multisite.runtime.SiteAddresses.normalizeDomain;
import static org.quarkpress.multisite.runtime.SiteAddresses.normalizeHost;
import static org.quarkpress.multisite.runtime.SiteAddresses.normalizePath;
import static org.quarkpress.multisite.runtime.SiteAddresses.reservedSegment;
import static org.quarkpress.multisite.runtime.SiteAddresses.segmentCount;
import static org.quarkpress.multisite.runtime.SiteAddresses.wwwForms;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.gen.Properties;

class SiteAddressesTest {

    @Test
    void candidatePathsFollowWordPress() {
        assertThat(candidatePaths("/news/2026/x", 1)).containsExactly("/news/", "/");
        assertThat(candidatePaths("/news/2026/x", 2)).containsExactly("/news/2026/", "/news/", "/");
        assertThat(candidatePaths("/news", 1)).containsExactly("/news/", "/");
        assertThat(candidatePaths("/", 1)).containsExactly("/");
        assertThat(candidatePaths("", 1)).containsExactly("/");
        assertThat(candidatePaths("//news//x", 2)).containsExactly("/news/x/", "/news/", "/");
        assertThat(candidatePaths("/0/news/", 1)).containsExactly("/news/", "/");
        assertThat(candidatePaths("/00/news/", 1)).containsExactly("/00/", "/");
        assertThat(candidatePaths("/NEWS/Today", 1)).containsExactly("/news/", "/");
        assertThat(candidatePaths("/a/b/c", Integer.MAX_VALUE)).containsExactly("/a/b/c/", "/a/b/", "/a/", "/");
    }

    @Test
    void hostsAreLowercasedAndWwwIsOptional() {
        assertThat(normalizeHost("Blog.Example")).isEqualTo("blog.example");
        assertThat(normalizeHost("[::1]")).isEqualTo("[::1]");
        assertThat(candidateDomains("www.blog.example")).containsExactly("www.blog.example", "blog.example");
        assertThat(candidateDomains("blog.example")).containsExactly("blog.example");
        // WordPress also looks up the empty domain here; no site has it, so the answer is the same
        assertThat(candidateDomains("www.")).containsExactly("www.");
        assertThat(candidateDomains("wwwexample.com")).containsExactly("wwwexample.com");
    }

    @Test
    void lookupPathsArePreparedLikeTheBootstrap() {
        assertThat(lookupPath("/blog/Hello%20World/")).isEqualTo("/blog/Hello%20World/");
        assertThat(lookupPath("/blog/x?p=1")).isEqualTo("/blog/x");
        assertThat(lookupPath("/shop/wp-admin/options.php")).isEqualTo("/shop/");
        assertThat(lookupPath("/a/wp-admin/b/wp-admin/c")).isEqualTo("/a/wp-admin/b/");
        assertThat(lookupPath("/wp-admin")).isEqualTo("/wp-admin");
        assertThat(lookupPath("/foo" + (char) 0x5C + "bar/")).isEqualTo("/foobar/");
        assertThat(lookupPath("/a" + (char) 0x5C + (char) 0x5C + "b/")).isEqualTo("/a" + (char) 0x5C + "b/");
        assertThat(lookupPath("/a" + (char) 0x5C)).isEqualTo("/a");
        assertThat(lookupPath("/a/wp-admin/b\nc")).isEqualTo("/a/\nc");
        assertThat(lookupPath("/x\n/y/wp-admin/z\n/wp-admin/")).isEqualTo("/x\n/y/\n/");
        assertThat(lookupPath("/wp-admin/" + "\n".repeat(20_000))).isEqualTo("/" + "\n".repeat(20_000));
        assertThat(lookupPath("/wp-admin/\n".repeat(3))).isEqualTo("/\n/\n/\n");
        assertThat(lookupPath("")).isEmpty();
    }

    @Test
    void segmentCountsFollowTheNetworkConstantsRule() {
        assertThat(lookupSegments("example.com", "/a/b/", "example.com", "/", 1))
                .isEqualTo(1);
        assertThat(lookupSegments("example.com", "/", "example.com", "/", 1)).isEqualTo(Integer.MAX_VALUE);
        assertThat(lookupSegments("example.com", "/wp/shop/x/", "example.com", "/wp/", 1))
                .isEqualTo(2);
        assertThat(lookupSegments("example.com", "/WP/", "example.com", "/wp/", 1))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(lookupSegments("example.com", "/wp", "example.com", "/wp/", 1))
                .isEqualTo(1);
        assertThat(lookupSegments("example.com", "/other/x", "example.com", "/wp/", 1))
                .isEqualTo(1);
        assertThat(lookupSegments("www.example.com", "/wp/shop/", "example.com", "/wp/", 1))
                .isEqualTo(1);
        assertThat(lookupSegments("foo.example.com", "/bar/baz/", "example.com", "/", 1))
                .isEqualTo(1);
        assertThat(lookupSegments("example.com", "/wp/shop/", "example.com", "/wp/", 2))
                .isEqualTo(3);
        assertThat(lookupSegments("example.com", "/a/b/", "example.com", "/", 2))
                .isEqualTo(2);
    }

    @Test
    void dotSegmentsAreRecognized() {
        assertThat(SiteAddresses.hasDotSegment("/x/../y")).isTrue();
        assertThat(SiteAddresses.hasDotSegment("/x/./y")).isTrue();
        assertThat(SiteAddresses.hasDotSegment("/x/%2E%2e/y")).isTrue();
        assertThat(SiteAddresses.hasDotSegment("/x/.%2e")).isTrue();
        assertThat(SiteAddresses.hasDotSegment("/..")).isTrue();
        assertThat(SiteAddresses.hasDotSegment("/x/.hidden/")).isFalse();
        assertThat(SiteAddresses.hasDotSegment("/x/a..b/")).isFalse();
        assertThat(SiteAddresses.hasDotSegment("/")).isFalse();
        assertThat(SiteAddresses.hasDotSegment("/a/wp-admin/../../b/x")).isTrue();
        assertThat(SiteAddresses.hasDotSegment("/a/wp-admin/%2e%2E/b")).isTrue();
        assertThat(SiteAddresses.hasDotSegment("/a/wp-admin/x?/../")).isFalse();
    }

    @Test
    void wwwFormsCoverBothDirections() {
        assertThat(wwwForms("x.example")).containsExactly("x.example", "www.x.example");
        assertThat(wwwForms("www.x.example")).containsExactly("www.x.example", "x.example", "www.www.x.example");
        assertThat(wwwForms("www.")).containsExactly("www.", "www.www.");
    }

    @Test
    void domainsAreNormalizedLikeWordPress() {
        assertThat(normalizeDomain("Blog.Example.COM")).isEqualTo("blog.example.com");
        assertThat(normalizeDomain(" blog example.com/ ")).isEqualTo("blogexample.com");
        assertThat(normalizeDomain("bl" + (char) 0xE9 + "og.example")).isEqualTo("blog.example");
        assertThatThrownBy(() -> normalizeDomain("blog.example:8080"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site domain has no port: blog.example:8080");
        assertThatThrownBy(() -> normalizeDomain("/// "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site domain must not be empty");
        assertThatThrownBy(() -> normalizeDomain("a".repeat(201)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site domain has at most 200 characters");
    }

    @Test
    void pathsAreNormalizedLikeWordPress() {
        assertThat(normalizePath("news")).isEqualTo("/news/");
        assertThat(normalizePath("/news")).isEqualTo("/news/");
        assertThat(normalizePath("news/")).isEqualTo("/news/");
        assertThat(normalizePath("//News//")).isEqualTo("/news/");
        assertThat(normalizePath("")).isEqualTo("/");
        assertThat(normalizePath("/")).isEqualTo("/");
        assertThat(normalizePath("a\\")).isEqualTo("/a/");
        assertThat(normalizePath("a/b")).isEqualTo("/a/b/");
        assertThat(normalizePath("a//b")).isEqualTo("/a//b/");
        assertThatThrownBy(() -> normalizePath("my news"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site path holds only URL path characters (letters, digits and"
                        + " - . _ ~ ! $ & ' ( ) * + , ; = : @ % /): /my news/");
        assertThatThrownBy(() -> normalizePath("a".repeat(99)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site path has at most 100 characters");
        assertThat(normalizePath("/caf%C3%A9/")).isEqualTo("/caf%c3%a9/");
        assertThatThrownBy(() -> normalizePath("/a%2/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site path escapes characters as %XX: /a%2/");
        assertThatThrownBy(() -> normalizePath("/a%zz/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site path escapes characters as %XX: /a%zz/");
    }

    @Test
    void reservedFirstSegmentsApplyToSubdirectorySitesOfTheNetwork() {
        assertThat(SiteAddresses.RESERVED_FIRST_SEGMENTS)
                .containsExactly(
                        "page",
                        "comments",
                        "blog",
                        "files",
                        "feed",
                        "wp-admin",
                        "wp-content",
                        "wp-includes",
                        "wp-json",
                        "embed");
        assertThat(reservedSegment("localhost", "/wp-admin/", "localhost", "/")).contains("wp-admin");
        assertThat(reservedSegment("localhost", "/blog/x/", "localhost", "/")).contains("blog");
        assertThat(reservedSegment("localhost", "/blogs/", "localhost", "/")).isEmpty();
        assertThat(reservedSegment("other.example", "/blog/", "localhost", "/")).isEmpty();
        assertThat(reservedSegment("localhost", "/", "localhost", "/")).isEmpty();
        assertThat(reservedSegment("example.com", "/net/feed/", "example.com", "/net/"))
                .contains("feed");
        assertThat(reservedSegment("example.com", "/feed/", "example.com", "/net/"))
                .isEmpty();
    }

    @Test
    void hostsAreClassifiedAgainstTheNetworkDomain() {
        assertThat(classify("localhost", "localhost")).isEqualTo(HostClass.NETWORK);
        assertThat(classify("www.localhost", "localhost")).isEqualTo(HostClass.NETWORK);
        assertThat(classify("a.localhost", "localhost")).isEqualTo(HostClass.SUBDOMAIN);
        assertThat(classify("www.a.localhost", "localhost")).isEqualTo(HostClass.SUBDOMAIN);
        assertThat(classify("alocalhost", "localhost")).isEqualTo(HostClass.FOREIGN);
        assertThat(classify("blog.example", "localhost")).isEqualTo(HostClass.FOREIGN);
        assertThat(classify("example.com", "www.example.com")).isEqualTo(HostClass.NETWORK);
        assertThat(classify("a.example.com", "www.example.com")).isEqualTo(HostClass.SUBDOMAIN);
    }

    @Test
    void segmentCountIgnoresEmptySegments() {
        assertThat(segmentCount("/")).isZero();
        assertThat(segmentCount("/net/")).isEqualTo(1);
        assertThat(segmentCount("/a//b/")).isEqualTo(2);
    }

    @Test
    void candidatePathsAreSlashTerminatedPrefixesLongestFirst() {
        Properties.check(
                "candidate paths",
                500,
                Gens.strings("/abAB0-_.%", 0, 40)
                        .flatMap(path -> Gens.ints(1, 4).map(n -> Map.entry(path, n))),
                entry -> {
                    List<String> paths = candidatePaths(entry.getKey(), entry.getValue());
                    assertThat(paths).isNotEmpty().hasSizeLessThanOrEqualTo(entry.getValue() + 1);
                    assertThat(paths.get(paths.size() - 1)).isEqualTo("/");
                    for (int i = 0; i < paths.size(); i++) {
                        String path = paths.get(i);
                        assertThat(path).startsWith("/").endsWith("/").doesNotContain("//");
                        assertThat(path).isEqualTo(SiteAddresses.lowerAscii(path));
                        if (i > 0) {
                            assertThat(paths.get(i - 1)).startsWith(path).hasSizeGreaterThan(path.length());
                        }
                    }
                });
    }

    @Test
    void normalizedPathsAreStable() {
        Properties.check("normalized paths are stable", 500, Gens.strings("/abAB0-_.\\ ", 0, 40), path -> {
            String normalized;
            try {
                normalized = normalizePath(path);
            } catch (IllegalArgumentException refused) {
                assertThat(refused).hasMessageStartingWith("a site path holds only URL path characters");
                return;
            }
            assertThat(normalized).startsWith("/").endsWith("/");
            assertThat(normalizePath(normalized)).isEqualTo(normalized);
        });
    }

    @Test
    void preparedPathsKeepNoQueryAndEndAfterTheLastAdminCut() {
        Properties.check("prepared lookup paths", 500, Gens.strings("/ab?\\0-wp-admin.", 0, 40), raw -> {
            String prepared = lookupPath(raw);
            assertThat(prepared).doesNotContain("?");
            if (raw.indexOf('?') < 0 && raw.indexOf('\\') < 0 && !raw.contains("/wp-admin/")) {
                assertThat(prepared).isEqualTo(raw);
            }
            if (prepared.contains("/wp-admin/")) {
                assertThat(prepared).endsWith("/");
            }
        });
    }

    @Test
    void normalizedDomainsAreStableAndUseOnlyDomainCharacters() {
        Properties.check("normalized domains", 500, Gens.strings("aB1-. _/" + (char) 0xE9, 0, 40), domain -> {
            String normalized;
            try {
                normalized = normalizeDomain(domain);
            } catch (IllegalArgumentException refused) {
                assertThat(refused).hasMessage("a site domain must not be empty");
                return;
            }
            assertThat(normalized).isNotEmpty().isEqualTo(SiteAddresses.lowerAscii(normalized));
            for (int i = 0; i < normalized.length(); i++) {
                char c = normalized.charAt(i);
                assertThat(Character.isLowerCase(c) || Character.isDigit(c) || c == '-' || c == '.')
                        .isTrue();
            }
            assertThat(normalizeDomain(normalized)).isEqualTo(normalized);
        });
    }

    @Test
    void everyWwwFormMapsBackToTheDomainOrItsBase() {
        Properties.check("www forms", 500, Gens.strings("wx.", 1, 12), domain -> {
            List<String> forms = wwwForms(domain);
            assertThat(forms).startsWith(domain).doesNotHaveDuplicates().hasSizeLessThanOrEqualTo(3);
            assertThat(forms).contains("www." + domain);
            String base = domain.length() > 4 && domain.startsWith("www.") ? domain.substring(4) : domain;
            for (String form : forms) {
                // a request for any form looks up the domain or its base: the alias conflict premise
                assertThat(candidateDomains(form)).containsAnyOf(domain, base);
            }
        });
    }

    private record DotToken(String text, boolean dot) {}

    @Test
    void dotSegmentsAreRecognizedWhateverTheirSpellingOrSurroundings() {
        List<DotToken> tokens = List.of(
                new DotToken(".", true),
                new DotToken("..", true),
                new DotToken("%2e", true),
                new DotToken("%2E", true),
                new DotToken(".%2e", true),
                new DotToken("%2e%2E", true),
                new DotToken("\\.", true),
                new DotToken(".\\.", true),
                new DotToken("...", false),
                new DotToken("%2e%2e%2e", false),
                new DotToken("%2f", false),
                new DotToken("\\\\.", false),
                new DotToken("wp-admin", false),
                new DotToken("wp-admin/..", true),
                new DotToken("wp-admin/%2e%2E/x", true),
                new DotToken("a", false),
                new DotToken(".a", false),
                new DotToken("a.", false),
                new DotToken("", false));
        List<String> suffixes = List.of("", "?x", "?/../", "?%2e");
        Properties.check(
                "dot segments",
                1000,
                Gens.lists(Gens.elements(tokens), 0, 5)
                        .flatMap(list -> Gens.elements(suffixes).map(suffix -> Map.entry(list, suffix))),
                entry -> {
                    StringBuilder path = new StringBuilder();
                    boolean expected = false;
                    for (DotToken token : entry.getKey()) {
                        path.append('/').append(token.text());
                        expected |= token.dot();
                    }
                    assertThat(SiteAddresses.hasDotSegment(path + entry.getValue()))
                            .as(path + entry.getValue())
                            .isEqualTo(expected);
                });
    }

    @Test
    void theAdminCutComesBeforeTheQueryCutAsInWordPress() {
        // PHP: stripslashes, then preg_replace('#(.*)/wp-admin/.*#', '$1/'), then explode('?') (verified)
        assertThat(lookupPath("/shop/wp-admin/options.php?_wp_http_referer=/shop/wp-admin/x"))
                .isEqualTo("/shop/wp-admin/options.php");
        assertThat(lookupPath("/wp-admin/?r=/wp-admin/")).isEqualTo("/wp-admin/");
        assertThat(lookupSegments("example.com", lookupPath("/wp-admin/?r=/wp-admin/"), "example.com", "/", 1))
                .isEqualTo(1);
        assertThat(lookupPath("/a" + (char) 0x5C + "?b")).isEqualTo("/a");
    }

    @Test
    void emptyNetworkPathSegmentsAreCountedLikeWordPress() {
        // PHP: 1 + count(explode('/', trim('/a//b/', '/'))) = 4
        assertThat(lookupSegments("example.com", "/a//b/c/d", "example.com", "/a//b/", 1))
                .isEqualTo(4);
        assertThat(candidatePaths("/a//b/c/d", 4)).containsExactly("/a/b/c/d/", "/a/b/c/", "/a/b/", "/a/", "/");
    }

    @Test
    void segmentCountsSaturate() {
        assertThat(lookupSegments("example.com", "/wp/x/", "example.com", "/wp/", Integer.MAX_VALUE))
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void candidatePathsStayBoundedForHugePaths() {
        String path = "/a".repeat(100_000);
        List<String> paths = candidatePaths(path, Integer.MAX_VALUE);
        // "/" + k * "a/" fits in 100 characters for k <= 49; plus the root
        assertThat(paths).hasSize(50);
        assertThat(paths.get(0)).hasSize(99).isEqualTo("/" + "a/".repeat(49));
        assertThat(paths.get(49)).isEqualTo("/");
        String longSegment = "/" + "b".repeat(1_000_000) + "/c";
        assertThat(candidatePaths(longSegment, Integer.MAX_VALUE)).containsExactly("/");
    }

    @Test
    void reachablePathsAreThoseARequestCanCompareWith() {
        assertThat(SiteAddresses.reachablePath("/")).isTrue();
        assertThat(SiteAddresses.reachablePath("/news/")).isTrue();
        assertThat(SiteAddresses.reachablePath("/00/a.b/..c/")).isTrue();
        assertThat(SiteAddresses.reachablePath("/a/../")).isFalse();
        assertThat(SiteAddresses.reachablePath("/./")).isFalse();
        assertThat(SiteAddresses.reachablePath("/a/%2e%2E/")).isFalse();
        assertThat(SiteAddresses.reachablePath("/0/")).isFalse();
        assertThat(SiteAddresses.reachablePath("/a/0/b/")).isFalse();
        assertThat(SiteAddresses.reachablePath("/a//b/")).isFalse();
        // a stored path that is reachable is found by its own request
        Properties.check("reachable paths are candidates", 500, Gens.strings("/ab0.%2eE", 0, 14), raw -> {
            String stored;
            try {
                stored = normalizePath(raw);
            } catch (IllegalArgumentException refused) {
                return;
            }
            if (SiteAddresses.reachablePath(stored)) {
                assertThat(candidatePaths(stored, Integer.MAX_VALUE)).contains(stored);
                assertThat(SiteAddresses.hasDotSegment(stored)).isFalse();
            }
        });
    }

    @Test
    void errorsEchoAtMostSixtyFourCharactersOfTheInput() {
        assertThatThrownBy(() -> normalizePath("a b".repeat(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageEndingWith(": /" + "a b".repeat(30).substring(0, 63) + "...");
        assertThatThrownBy(() -> normalizePath("a ".repeat(1_000_000)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site path has at most 100 characters");
        assertThatThrownBy(() -> normalizeDomain("a:".repeat(30)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site domain has no port: " + "a:".repeat(30));
        assertThatThrownBy(() -> normalizeDomain("a:".repeat(101)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site domain has at most 200 characters");
    }

    @Test
    void aRequestHostLosesItsPortAndBackslashes() {
        assertThat(SiteAddresses.requestHost("LocalHost:8080")).isEqualTo("localhost");
        assertThat(SiteAddresses.requestHost("a" + (char) 0x5C + ".example:80:81"))
                .isEqualTo("a.example");
        assertThat(SiteAddresses.requestHost(":80")).isEmpty();
        assertThat(SiteAddresses.requestHost("loc" + (char) 0x5C + (char) 0x5C + "alhost"))
                .isEqualTo("localhost");
        assertThat(SiteAddresses.requestHost("")).isEmpty();
    }

    @Test
    void onlyStorableValuesCanMatchASite() {
        assertThat(SiteAddresses.storableDomain("a-b.example")).isTrue();
        assertThat(SiteAddresses.storableDomain("")).isFalse();
        assertThat(SiteAddresses.storableDomain("a" + (char) 0 + "b")).isFalse();
        assertThat(SiteAddresses.storableDomain("A.example")).isFalse();
        assertThat(SiteAddresses.storablePath("/a/%e9/")).isTrue();
        assertThat(SiteAddresses.storablePath("/a" + (char) 0 + "/")).isFalse();
        assertThat(SiteAddresses.storablePath("/" + new String(Character.toChars(0xE9)) + "/"))
                .isFalse();
    }
}
