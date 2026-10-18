package org.quarkpress.multisite.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares the site address functions with WordPress 7.1.3's {@code get_site_by_path} (its candidate paths),
 * {@code wp_normalize_site_data} and {@code get_subdirectory_reserved_names} (testing/wp-oracle), and the request path
 * preparation with the PHP built-ins WordPress's multisite bootstrap applies. Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class MultisiteParityTest {

    static final Path DRIVER = Path.of("src/test/php/multisite-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/formatting.php",
        "wp-includes/ms-load.php",
        "wp-includes/ms-settings.php",
        "wp-includes/ms-site.php",
        "wp-includes/ms-functions.php"
    };
    static final int GENERATED = 400;

    /** About half of what a run compares (3,500 at the time of writing): path, lookup-path and normalize cases. */
    static final int MIN_COMPARISONS = 2_500;

    static final String REFUSED = "(refused)";

    @Test
    void matchesWordPress() throws Exception {
        Path sources = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(sources);
        ParityRun run = new ParityRun("multisite", ParitySkipList.load(SKIPS), FAILURES);

        List<PhpOracle.Case> cases = new ArrayList<>();
        cases.add(new PhpOracle.Case(
                "reserved", OracleJson.MAPPER.createObjectNode().put("op", "reserved")));

        List<String> paths = new ArrayList<>(List.of(
                "",
                "/",
                "/news",
                "/news/",
                "//news//x",
                "/0/news/",
                "/00/x",
                "/a/b/c/d",
                "/NEWS/Today",
                "news",
                "/a/0/b/",
                "/%E2%82%AC/x",
                "/wp-admin/edit.php",
                "/a b/c",
                "///",
                "/a//b///c",
                "/0/0/0",
                "//0//a",
                "/" + "x".repeat(60) + "/" + "y".repeat(60),
                "/" + "x".repeat(98),
                "/" + "x".repeat(99) + "/a",
                "/" + "x".repeat(200),
                "/a/" + "y".repeat(97),
                "/a/" + "y".repeat(96)));
        int handPaths = paths.size();
        paths.addAll(GeneratedCases.sample(Gens.strings("/aZ09-_.%0 ", 0, 30), "20261017-ms-paths", GENERATED));
        paths.addAll(GeneratedCases.sample(Gens.strings("////abAB0 ", 0, 150), "20261017-ms-paths-long", 100));
        List<Integer> segmentChoices = Arrays.asList(null, 1, 2, 3);
        Map<String, Object[]> pathCases = new LinkedHashMap<>();
        for (int p = 0; p < paths.size(); p++) {
            for (Integer segments : segmentChoices) {
                String id = "paths-" + p + "-" + segments;
                ObjectNode in = OracleJson.MAPPER
                        .createObjectNode()
                        .put("op", "paths")
                        .put("domain", "example.com")
                        .put("path", paths.get(p));
                if (segments == null) {
                    in.putNull("segments");
                } else {
                    in.put("segments", segments);
                }
                cases.add(new PhpOracle.Case(id, in));
                pathCases.put(id, new Object[] {paths.get(p), segments, p >= handPaths});
            }
        }

        // Request paths as the bootstrap prepares them: backslashes, then /wp-admin/ (also in the query), then the
        // query.
        List<String> requests = new ArrayList<>(List.of(
                "/news/",
                "/news?x=1",
                "/news/?a=/wp-admin/b",
                "/wp-admin/",
                "/a/wp-admin/edit.php?x=1",
                "/a/wp-admin/x/wp-admin/y",
                "/a/b?c=/d/wp-admin/e",
                "/a\\/b",
                "\\\\/a",
                "a\\",
                "\\0abc",
                "/a\\?b",
                "/a/wp-admin/?x=\\/wp-admin/",
                "/a/wp-admin",
                "/wp-admin",
                "/WP-ADMIN/x",
                "/a?b?c",
                "?",
                "/a\\/wp-admin/b",
                "/a/\\wp-admin/b",
                "/\\/wp-admin/",
                "/a/wp-admin/b?c",
                "/x/wp-admin/y?z=/wp-admin/w",
                "/a\n/wp-admin/b",
                "/a/wp-admin/b\nc",
                "/a\r/wp-admin/"));
        int handRequests = requests.size();
        requests.addAll(
                GeneratedCases.sample(Gens.strings("/\\?wp-admin.ab\n\r", 0, 40), "20261017-ms-lookup", GENERATED));
        requests.addAll(GeneratedCases.sample(
                Gens.lists(Gens.elements("/wp-admin/", "/", "?", "\\", "a", "0", "b/", "wp-admin", "\n", "\r"), 0, 8)
                        .map(parts -> String.join("", parts)),
                "20261017-ms-lookup-pieces",
                200));
        for (int i = 0; i < requests.size(); i++) {
            cases.add(new PhpOracle.Case(
                    "lookup-" + i,
                    OracleJson.MAPPER
                            .createObjectNode()
                            .put("op", "lookup")
                            .put("path", requests.get(i))
                            .put("admin", true)));
        }

        String accented = new String(Character.toChars(0xE9));
        List<String> domains = new ArrayList<>(List.of(
                "Blog.Example.COM",
                " blog example.com/ ",
                "bl" + accented + "og.example",
                "blog.example:8080",
                "///",
                "",
                "a_b.example",
                "xn--bcher-kva.example",
                "a..b",
                "-a-.example",
                "UPPER",
                "1.2.3.4",
                "[::1]",
                "a@b.example",
                "a/b",
                "a.example."));
        List<String> sitePaths = new ArrayList<>(List.of(
                "news",
                "/news",
                "news/",
                "//News//",
                "",
                "/",
                "a\\",
                "a/b",
                "a//b",
                "my news",
                "a\\b",
                "/~user/",
                "/a%20b/",
                "x".repeat(99),
                "\\\\",
                "/A/B/C/"));
        int handNormalize = domains.size();
        domains.addAll(
                GeneratedCases.sample(Gens.strings("aZ09-.:_ /@" + accented, 0, 30), "20261017-ms-domains", GENERATED));
        sitePaths.addAll(
                GeneratedCases.sample(Gens.strings("/aZ09-_.\\ %~", 0, 30), "20261017-ms-site-paths", GENERATED));
        for (int i = 0; i < domains.size(); i++) {
            cases.add(new PhpOracle.Case(
                    "normalize-" + i,
                    OracleJson.MAPPER
                            .createObjectNode()
                            .put("op", "normalize")
                            .put("domain", domains.get(i))
                            .put("path", sitePaths.get(i))));
        }

        Map<String, JsonNode> out = oracle.run(DRIVER, cases);

        List<String> wordpressNames = new ArrayList<>();
        out.get("reserved").get("names").forEach(name -> wordpressNames.add(name.asText()));
        run.compare(
                "reserved",
                "reserved_names",
                String.join(",", wordpressNames),
                String.join(",", SiteAddresses.RESERVED_FIRST_SEGMENTS));

        for (Map.Entry<String, Object[]> entry : pathCases.entrySet()) {
            String path = (String) entry.getValue()[0];
            Integer segments = (Integer) entry.getValue()[1];
            if ((Boolean) entry.getValue()[2]) {
                run.generated(entry.getKey(), "path '" + path + "', segments " + segments);
            }
            List<String> wordpress = new ArrayList<>();
            out.get(entry.getKey()).get("paths").forEach(p -> {
                String lower = SiteAddresses.lowerAscii(p.asText());
                // D3: a candidate longer than the stored path limit can match no site, so QuarkPress does not list it.
                if (lower.length() <= SiteAddresses.MAX_PATH_LENGTH) {
                    wordpress.add(lower);
                }
            });
            List<String> ours = SiteAddresses.candidatePaths(path, segments == null ? Integer.MAX_VALUE : segments);
            run.compare(entry.getKey(), "candidate_paths", String.join("|", wordpress), String.join("|", ours));
        }

        for (int i = 0; i < requests.size(); i++) {
            String id = "lookup-" + i;
            if (i >= handRequests) {
                run.generated(id, "request path '" + requests.get(i) + "'");
            }
            run.compare(id, "lookup_path", out.get(id).get("path").asText(), SiteAddresses.lookupPath(requests.get(i)));
        }

        for (int i = 0; i < domains.size(); i++) {
            String id = "normalize-" + i;
            if (i >= handNormalize) {
                run.generated(id, "domain '" + domains.get(i) + "', path '" + sitePaths.get(i) + "'");
            }
            JsonNode wordpress = out.get(id);
            run.compare(id, "domain", expectedDomain(wordpress.get("domain").asText()), ourDomain(domains.get(i)));
            run.compare(id, "path", expectedPath(wordpress.get("path").asText()), ourPath(sitePaths.get(i)));
        }

        // The segment count per network configuration (the constants are defined once per PHP process).
        String[][] networks = {
            {"example.com", "/"},
            {"example.com", "/wp/"},
            {"example.com", "/a/b/"},
            {"example.com", "/A/"},
            {"other.example", "/n/"}
        };
        List<String> hosts = List.of("example.com", "other.example", "www.example.com", "blog.example.com");
        int segmentCases = 0;
        for (String[] network : networks) {
            List<String> requestPaths = new ArrayList<>(paths.subList(0, handPaths));
            requestPaths.addAll(paths.subList(handPaths, handPaths + 100));
            requestPaths.addAll(List.of(
                    network[1], network[1].toUpperCase(), network[1] + "x/y/", network[1] + "x", network[1] + "0/x"));
            PhpOracle networkOracle = oracle.withEnvironment("QP_NETWORK_DOMAIN", network[0])
                    .withEnvironment("QP_NETWORK_PATH", network[1]);
            List<PhpOracle.Case> segmentRun = new ArrayList<>();
            for (int h = 0; h < hosts.size(); h++) {
                for (int p = 0; p < requestPaths.size(); p++) {
                    segmentRun.add(new PhpOracle.Case(
                            "segments-" + h + "-" + p,
                            OracleJson.MAPPER
                                    .createObjectNode()
                                    .put("op", "segments")
                                    .put("host", hosts.get(h))
                                    .put("path", requestPaths.get(p))));
                }
            }
            Map<String, JsonNode> segmentOut = networkOracle.run(DRIVER, segmentRun);
            for (int h = 0; h < hosts.size(); h++) {
                for (int p = 0; p < requestPaths.size(); p++) {
                    String id = "segments-" + h + "-" + p;
                    assertThat(segmentOut.get(id).get("captured").asBoolean())
                            .as(id)
                            .isTrue();
                    JsonNode value = segmentOut.get(id).get("segments");
                    long wordpress = value.isNull() ? Integer.MAX_VALUE : value.asLong();
                    int ours = SiteAddresses.lookupSegments(
                            hosts.get(h), requestPaths.get(p), network[0], SiteAddresses.normalizePath(network[1]), 1);
                    run.compare(
                            network[0] + network[1] + "@" + id,
                            "segments",
                            String.valueOf(wordpress),
                            String.valueOf(ours));
                    segmentCases++;
                }
            }
        }

        System.out.println("multisite parity: " + (cases.size() + segmentCases) + " cases, " + run.comparisons()
                + " comparisons, " + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
        assertThat(run.comparisons()).isGreaterThanOrEqualTo(MIN_COMPARISONS);
    }

    /** WordPress's domain, lower-cased, or a refusal where it breaks QuarkPress's storage rules. */
    private static String expectedDomain(String wordpress) {
        String lower = SiteAddresses.lowerAscii(wordpress);
        if (lower.isEmpty() || lower.indexOf(':') >= 0 || lower.length() > SiteAddresses.MAX_DOMAIN_LENGTH) {
            return REFUSED;
        }
        return lower;
    }

    private static String ourDomain(String input) {
        try {
            return SiteAddresses.normalizeDomain(input);
        } catch (IllegalArgumentException refused) {
            return REFUSED;
        }
    }

    private static String expectedPath(String wordpress) {
        String lower = SiteAddresses.lowerAscii(wordpress);
        if (lower.length() > SiteAddresses.MAX_PATH_LENGTH || !SiteAddresses.percentEscapesValid(lower)) {
            return REFUSED;
        }
        for (int i = 0; i < lower.length(); i++) {
            if (!SiteAddresses.pathCharacter(lower.charAt(i))) {
                return REFUSED;
            }
        }
        return lower;
    }

    private static String ourPath(String input) {
        try {
            return SiteAddresses.normalizePath(input);
        } catch (IllegalArgumentException refused) {
            return REFUSED;
        }
    }
}
