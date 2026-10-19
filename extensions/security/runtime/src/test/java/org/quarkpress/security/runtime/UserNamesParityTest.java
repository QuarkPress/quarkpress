package org.quarkpress.security.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Compares the user-name rules with WordPress 7.1.3's {@code sanitize_user} (strict), {@code remove_accents} (en_US)
 * and the nicename {@code wp_insert_user} derives from a login (testing/wp-oracle). Every code point of the Latin
 * ranges WordPress's accent table touches is compared alone; hand-written and generated names cover tags, entities,
 * percent octets and whitespace. Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class UserNamesParityTest {

    static final Path DRIVER = Path.of("src/test/php/security-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-names-failures.json");
    static final String[] WORDPRESS_FILES = {"wp-includes/formatting.php", "wp-includes/functions.php"};

    /** About half of what a run compares (5,106 at the time of writing). */
    static final int MIN_COMPARISONS = 2_550;

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void matchesWordPress() throws Exception {
        Path sources = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(sources);
        ParityRun run = new ParityRun("security-names", ParitySkipList.load(SKIPS), FAILURES);

        List<String> singles = new ArrayList<>();
        int[][] ranges = {{0x80, 0x24F}, {0x250, 0x2AF}, {0x1E00, 0x1EFF}, {0x20A0, 0x20CF}};
        for (int[] range : ranges) {
            for (int c = range[0]; c <= range[1]; c++) {
                if (Character.isDefined(c) && !Character.isISOControl(c)) {
                    singles.add(cp(c));
                }
            }
        }
        List<String> names = new ArrayList<>(List.of(
                cp(0xC9) + "milie Dubois",
                "  John    SMITH ",
                "jane.doe@example.com",
                "O'Brien",
                "<b>bob</b>",
                "<script>alert(1)</script>bob",
                "<style>x{}</style>ann",
                "AT&amp;T",
                "AT&T",
                "100%25off",
                cp(0x418, 0x432, 0x430, 0x43D),
                cp(0x674E, 0x5C0F, 0x9F99) + " lee",
                "Stra" + cp(0xDF) + "e",
                "Zo" + cp(0xEB) + " " + cp(0xDC) + "nal",
                "e" + cp(0x301) + "te",
                "a&b\nc;d",
                "&;;x",
                "x&;",
                "a&bc;d&e;f",
                "%%4141",
                "a\rb",
                "a <b",
                "a<b c>d",
                "<!-- c -->name",
                "0",
                "0.",
                "-0",
                ".0",
                "00",
                " 0 ",
                "0 0",
                "0-",
                "--",
                "...",
                "@@@",
                "a.-.b",
                "John_Smith",
                "Bob 2",
                "abcdefghij".repeat(6),
                "a".repeat(61),
                "abcdefghij".repeat(4) + "abcdefghi.x",
                "x".repeat(47) + ".yy",
                ""));
        int hand = names.size();
        String alphabet = "aZ09 _.-@<>/&;%#'\"!\n\r\t" + cp(0xC9, 0xDF, 0x142, 0x418, 0x674E, 0x1EA1, 0x20AC);
        names.addAll(GeneratedCases.sample(Gens.strings(alphabet, 0, 30), "20261018-user-names", 600));
        names.addAll(GeneratedCases.sample(Gens.strings("ab .-_@", 40, 70), "20261018-user-names-long", 100));

        List<PhpOracle.Case> cases = new ArrayList<>();
        for (int i = 0; i < singles.size(); i++) {
            cases.add(new PhpOracle.Case("accent-" + i, op("remove_accents", singles.get(i))));
            cases.add(new PhpOracle.Case("single-" + i, op("sanitize_user", singles.get(i))));
        }
        for (int i = 0; i < names.size(); i++) {
            cases.add(new PhpOracle.Case("user-" + i, op("sanitize_user", names.get(i))));
            cases.add(new PhpOracle.Case("nicename-" + i, op("nicename", names.get(i))));
            cases.add(new PhpOracle.Case("accents-" + i, op("remove_accents", names.get(i))));
        }
        cases.add(
                new PhpOracle.Case("intl", OracleJson.MAPPER.createObjectNode().put("op", "intl")));
        Map<String, JsonNode> out = oracle.run(DRIVER, cases);

        // WordPress composes decomposed text (NFD to NFC) in remove_accents only with PHP's intl extension. Without
        // it those inputs are skipped (an ASSUMPTION); in CI a PHP without intl fails the run.
        boolean intl = "yes".equals(text(out, "intl"));
        if (!intl && "true".equalsIgnoreCase(System.getenv("CI"))) {
            throw new AssertionError("CI requires a PHP with the intl extension: " + System.getProperty("wp.php"));
        }
        int skippedNoIntl = 0;

        for (int i = 0; i < singles.size(); i++) {
            String input = singles.get(i);
            if (!intl && decomposed(input)) {
                skippedNoIntl++;
                continue;
            }
            String label = "U+" + Integer.toHexString(input.codePointAt(0)).toUpperCase(java.util.Locale.ROOT);
            run.compare(
                    "accent-" + i + " " + label, "remove_accents", text(out, "accent-" + i), RemoveAccents.enUs(input));
            run.compare(
                    "single-" + i + " " + label,
                    "sanitize_user",
                    text(out, "single-" + i),
                    UserNames.sanitizeUserStrict(input));
        }
        for (int i = 0; i < names.size(); i++) {
            String input = names.get(i);
            if (!intl && decomposed(input)) {
                skippedNoIntl++;
                continue;
            }
            if (i >= hand) {
                run.generated("user-" + i, OracleJson.MAPPER.writeValueAsString(input));
            }
            run.compare("user-" + i, "sanitize_user", text(out, "user-" + i), UserNames.sanitizeUserStrict(input));
            run.compare(
                    "nicename-" + i,
                    "nicename",
                    text(out, "nicename-" + i),
                    UserNames.nicenameOfLogin(UserNames.login(input)));
            Optional<String> base = UserNames.nicenameBase(input);
            // D7: QuarkPress accepts logins over 60 characters, WordPress refuses them; own aspect for the skip list.
            String refusedAspect = UserNames.login(input).length() > 60 ? "refused-login-over-60" : "refused";
            run.compare(
                    "nicename-" + i,
                    refusedAspect,
                    out.get("nicename-" + i).get("refused").asBoolean(),
                    base.isEmpty());
            if (base.isPresent()) {
                run.compare("nicename-" + i, "nicename-base", text(out, "nicename-" + i), base.get());
            }
            run.compare("accents-" + i, "remove_accents", text(out, "accents-" + i), RemoveAccents.enUs(input));
        }

        if (!intl) {
            System.out.println("ASSUMPTION: PHP has no intl extension; skipped " + skippedNoIntl
                    + " cases with decomposed input (set CI to make this a failure)");
        }
        System.out.println("security names parity: " + cases.size() + " cases, " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
        assertThat(run.comparisons()).isGreaterThanOrEqualTo(MIN_COMPARISONS);
    }

    /** True when the text holds a non-spacing mark, so remove_accents' answer depends on intl. */
    private static boolean decomposed(String text) {
        return text.codePoints().anyMatch(c -> Character.getType(c) == Character.NON_SPACING_MARK);
    }

    private static JsonNode op(String name, String text) {
        return OracleJson.MAPPER.createObjectNode().put("op", name).put("text", text);
    }

    private static String text(Map<String, JsonNode> out, String id) {
        return out.get(id).get("value").asText();
    }
}
