package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares this library with WordPress 7.1.3's wp_check_password, wp_password_needs_rehash,
 * WP_Application_Passwords::check_password, PasswordHash::crypt_private and wp_fast_hash (testing/wp-oracle). The
 * first oracle run lets WordPress make hashes; the second checks the same (password, hash) pairs on both sides.
 * Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class WpPasswordsParityTest {

    static final Path DRIVER = Path.of("src/test/php/password-driver.php");
    static final Path CASES = Path.of("src/test/resources/wp-parity-cases.json");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-failures.json");
    static final String[] WORDPRESS_FILES = {
        "wp-includes/functions.php",
        "wp-includes/pluggable.php",
        "wp-includes/class-phpass.php",
        "wp-includes/class-wp-application-passwords.php"
    };

    /**
     * The aspect that compares the driver's restatement of the verifier wp_check_password picks with {@code detect}.
     * Both sides come from the behaviour description, not from a WordPress function: a harness self-check, counted
     * apart and excluded from the floors.
     */
    static final String SELF_CHECK = "self-check:branch";

    static final int MIN_COMPARISONS = 5_800;

    /**
     * Floors per WordPress-oracle aspect (comparisons that are not skip-listed), about half of what a run makes, so
     * that no section can vanish behind the others. Observed on 2026-10-17: crypt_private 200, wp_fast_hash 300,
     * check 2444, rehash 2450, verify 1861, app_check 1861 (and 2450 self-checks, 11566 comparisons in all).
     */
    static final Map<String, Integer> MIN_PER_ASPECT = Map.of(
            "crypt_private", 100,
            "wp_fast_hash", 150,
            "check", 1_200,
            "rehash", 1_200,
            "verify", 900,
            "app_check", 900);

    static final int MAX_BCRYPT_CASES = 50;
    static final int BCRYPT_COST = 4;
    static final int GENERATED_BCRYPT_PASSWORDS = 2;
    static final int PHPASS_HASHES = 200;
    static final int FAST_HASHES = 300;
    static final int GARBAGE_HASHES = 500;
    /** Generated look-alikes that make PHP run bcrypt, at most; the rest are drawn again, so drift cannot add more. */
    static final int GARBAGE_BCRYPT_QUOTA = 5;

    static final int MAX_GENERATED_BCRYPT_COST = 5;

    private static final HexFormat HEX = HexFormat.of();

    /** One pair both sides check: any password bytes and a stored hash. */
    record CheckCase(String id, byte[] password, String hash, boolean generated) {}

    /** Wraps {@link ParityRun#compare} and counts, per aspect, the comparisons that are not skip-listed. */
    static final class Aspects {
        final ParityRun run;
        final Map<String, Integer> counts = new TreeMap<>();

        Aspects(ParityRun run) {
            this.run = run;
        }

        void compare(String caseId, String aspect, Object wordpress, Object quarkpress) {
            int before = run.comparisons();
            run.compare(caseId, aspect, wordpress, quarkpress);
            counts.merge(aspect, run.comparisons() - before, Integer::sum);
        }
    }

    @Test
    void matchesWordPress() throws Exception {
        Path wordpress = WordPressSources.ensure(WordPressRelease.fromSystemProperty(), WORDPRESS_FILES);
        PhpOracle oracle = PhpOracle.create(wordpress);
        ParityRun run = new ParityRun("lib-wp-password", ParitySkipList.load(SKIPS), FAILURES);
        Aspects aspects = new Aspects(run);

        // Run 1: WordPress makes hashes.
        List<byte[]> bcryptPasswords = new ArrayList<>(
                GeneratedCases.sample(PasswordGens.passwords(), "20261017-bcrypt", GENERATED_BCRYPT_PASSWORDS));
        bcryptPasswords.add("x".repeat(WpPasswords.MAX_PASSWORD_BYTES).getBytes(US_ASCII));
        bcryptPasswords.add("x".repeat(WpPasswords.MAX_PASSWORD_BYTES + 1).getBytes(US_ASCII));
        List<byte[]> phpassPasswords =
                GeneratedCases.sample(PasswordGens.passwords(), "20261017-phpass", PHPASS_HASHES);
        List<String> settings =
                GeneratedCases.sample(PasswordGens.phpassSettings(), "20261017-settings", PHPASS_HASHES);
        List<byte[]> secrets = GeneratedCases.sample(PasswordGens.secrets(), "20261017-secrets", FAST_HASHES);

        List<PhpOracle.Case> makes = new ArrayList<>();
        for (int i = 0; i < bcryptPasswords.size(); i++) {
            makes.add(new PhpOracle.Case(
                    "hash-" + i, input("hash", bcryptPasswords.get(i)).put("cost", BCRYPT_COST)));
        }
        for (int i = 0; i < PHPASS_HASHES; i++) {
            makes.add(new PhpOracle.Case(
                    "phpass-" + i, input("phpass", phpassPasswords.get(i)).put("setting", settings.get(i))));
        }
        for (int i = 0; i < FAST_HASHES; i++) {
            makes.add(new PhpOracle.Case("fast-" + i, input("fast", secrets.get(i))));
        }
        Map<String, JsonNode> made = oracle.run(DRIVER, makes);

        List<CheckCase> checks = new ArrayList<>();
        checks.addAll(handWrittenCases());
        checks.addAll(programmaticCases());
        for (int i = 0; i < bcryptPasswords.size(); i++) {
            String hash = made.get("hash-" + i).get("hash").asText();
            addWithMutations(checks, "gen-bcrypt-" + i, bcryptPasswords.get(i), hash, PasswordGens.BCRYPT_ALPHABET);
        }
        for (int i = 0; i < PHPASS_HASHES; i++) {
            byte[] password = phpassPasswords.get(i);
            String wordpressHex = made.get("phpass-" + i).get("hash").asText();
            byte[] ours = Phpass.compute(password, PhpBytes.utf8(settings.get(i)));
            aspects.compare(
                    "phpass-" + i,
                    "crypt_private",
                    wordpressHex,
                    HEX.formatHex(ours == null ? "*0".getBytes(US_ASCII) : ours));
            String hash = utf8OrNull(HEX.parseHex(wordpressHex));
            if (hash != null) {
                addWithMutations(checks, "gen-phpass-" + i, password, hash, Phpass.ALPHABET);
            }
        }
        for (int i = 0; i < FAST_HASHES; i++) {
            String wordpressHash = made.get("fast-" + i).get("hash").asText();
            aspects.compare(
                    "fast-" + i,
                    "wp_fast_hash",
                    wordpressHash,
                    new String(WpPasswords.fastHash(secrets.get(i)), US_ASCII));
            addWithMutations(checks, "gen-fast-" + i, secrets.get(i), wordpressHash, PasswordGens.BASE64URL_ALPHABET);
        }
        List<byte[]> garbagePasswords =
                GeneratedCases.sample(PasswordGens.passwords(), "20261017-garbage-passwords", GARBAGE_HASHES);
        List<String> garbageHashes = garbageHashes();
        for (int i = 0; i < GARBAGE_HASHES; i++) {
            checks.add(new CheckCase("gen-garbage-" + i, garbagePasswords.get(i), garbageHashes.get(i), true));
        }

        long bcryptCases = bcryptPasswords.size()
                + checks.stream().filter(c -> computesBcrypt(c.hash())).count();
        assertThat(bcryptCases).as("bcrypt cases in one parity run").isLessThanOrEqualTo(MAX_BCRYPT_CASES);
        assertThat(checks.stream()
                        .filter(c -> bcryptCost(c.hash()) > MAX_GENERATED_BCRYPT_COST)
                        .map(CheckCase::id))
                .as("pairs that make PHP run bcrypt above cost " + MAX_GENERATED_BCRYPT_COST)
                .containsExactly("bcrypt-default-cost");

        // Run 2: both sides check the same pairs.
        List<PhpOracle.Case> batch = new ArrayList<>();
        for (CheckCase c : checks) {
            batch.add(new PhpOracle.Case(c.id(), input("check", c.password()).put("hash", c.hash())));
        }
        Map<String, JsonNode> results = oracle.run(DRIVER, batch);
        for (CheckCase c : checks) {
            if (c.generated()) {
                run.generated(c.id(), "password " + HEX.formatHex(c.password()) + ", hash " + c.hash());
            }
            compare(aspects, c, results.get(c.id()));
        }

        Map<String, Integer> wordpressAspects = new TreeMap<>(aspects.counts);
        int selfChecks = wordpressAspects.remove(SELF_CHECK);
        System.out.println("lib-wp-password parity: " + makes.size() + " hashes made, " + checks.size()
                + " pairs checked (" + bcryptCases + " with bcrypt), " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        System.out.println("lib-wp-password parity, WordPress comparisons per aspect: " + wordpressAspects
                + "; harness self-check (driver's branch restatement vs detect, not WordPress): " + selfChecks);
        MIN_PER_ASPECT.forEach((aspect, minimum) -> assertThat(wordpressAspects.getOrDefault(aspect, 0))
                .as("comparisons of " + aspect)
                .isGreaterThanOrEqualTo(minimum));
        assertThat(wordpressAspects.keySet()).as("aspects with a floor").isEqualTo(MIN_PER_ASPECT.keySet());
        run.assertParity(MIN_COMPARISONS);
    }

    static void compare(Aspects aspects, CheckCase c, JsonNode wp) {
        boolean wordpressCheck = wp.get("check").booleanValue();
        aspects.compare(c.id(), "check", wordpressCheck, WpPasswords.check(c.password(), c.hash()));
        aspects.compare(c.id(), "rehash", wp.get("rehash").booleanValue(), WpPasswords.needsRehash(c.hash()));
        aspects.compare(c.id(), SELF_CHECK, wp.get("branch").asText(), branch(c.hash()));
        String text = utf8OrNull(c.password());
        if (text != null) {
            boolean wordpressVerify = wordpressCheck || wp.get("check_slashed").booleanValue();
            aspects.compare(c.id(), "verify", wordpressVerify, WpPasswords.verify(text, c.hash()));
            aspects.compare(
                    c.id(), "app_check", wp.get("app_check").booleanValue(), WpPasswords.checkFastHash(text, c.hash()));
        }
    }

    /** The verifier wp_check_password picks for {@code hash}, read from detect() (spec-derived, as in the driver). */
    static String branch(String hash) {
        return switch (WpPasswords.detect(hash)) {
            case MD5 -> "md5";
            case WP_BCRYPT -> "wp";
            case PHPASS -> "phpass";
            case PHPASS_PHPBB, BCRYPT, WP_FAST_HASH -> "password_verify";
            case UNKNOWN -> PhpBytes.utf8(hash).length <= WpPasswords.SHORT_HASH_BYTES ? "md5" : "password_verify";
        };
    }

    /** Whether PHP would run bcrypt for this stored hash; see {@link #bcryptCost}. */
    static boolean computesBcrypt(String hash) {
        return bcryptCost(hash) >= 0;
    }

    /**
     * The cost PHP would run bcrypt at for this stored hash (after an optional {@code $wp}), or -1 when it would not
     * run bcrypt: {@code $2a$}, {@code $2b$}, {@code $2x$} or {@code $2y$}, cost 04 to 31, {@code $}, then 22 salt
     * characters. PHP reads only that prefix before computing, so longer or shorter tails still cost a bcrypt run.
     */
    static int bcryptCost(String hash) {
        String rest = hash.startsWith("$wp") ? hash.substring(3) : hash;
        if (rest.length() < 29
                || !rest.startsWith("$2")
                || "abxy".indexOf(rest.charAt(2)) < 0
                || rest.charAt(3) != '$'
                || rest.charAt(6) != '$') {
            return -1;
        }
        char tens = rest.charAt(4);
        char units = rest.charAt(5);
        if (tens < '0' || tens > '3' || units < '0' || units > '9') {
            return -1;
        }
        int cost = (tens - '0') * 10 + (units - '0');
        if (cost < 4 || cost > 31) {
            return -1;
        }
        for (int i = 7; i < 29; i++) {
            if (PasswordGens.BCRYPT_ALPHABET.indexOf(rest.charAt(i)) < 0) {
                return -1;
            }
        }
        return cost;
    }

    /**
     * {@value #GARBAGE_HASHES} generated look-alike hashes, at most {@value #GARBAGE_BCRYPT_QUOTA} of them ones that
     * make PHP run bcrypt, and those at cost {@value #MAX_GENERATED_BCRYPT_COST} or less; other such draws are
     * skipped, so neither the bcrypt budget nor the run time depends on the seed.
     */
    static List<String> garbageHashes() {
        List<String> candidates =
                GeneratedCases.sample(PasswordGens.garbageHashes(), "20261017-garbage-hashes", 2 * GARBAGE_HASHES);
        List<String> chosen = new ArrayList<>();
        int bcrypt = 0;
        for (String hash : candidates) {
            if (chosen.size() == GARBAGE_HASHES) {
                break;
            }
            int cost = bcryptCost(hash);
            if (cost >= 0) {
                if (bcrypt == GARBAGE_BCRYPT_QUOTA || cost > MAX_GENERATED_BCRYPT_COST) {
                    continue;
                }
                bcrypt++;
            }
            chosen.add(hash);
        }
        assertThat(chosen).as("generated look-alike hashes").hasSize(GARBAGE_HASHES);
        return chosen;
    }

    /** The pair, one character of the hash changed within its alphabet, the hash cut by one, the prefix upper-cased. */
    private static void addWithMutations(
            List<CheckCase> checks, String id, byte[] password, String hash, String alphabet) {
        checks.add(new CheckCase(id, password, hash, true));
        if (hash.length() < 34) {
            return; // "*" for an overlong password: nothing to mutate
        }
        int at = hash.length() - 5;
        checks.add(new CheckCase(id + "-flip", password, PasswordGens.replaceChar(hash, at, alphabet, at), true));
        checks.add(new CheckCase(id + "-cut", password, hash.substring(0, hash.length() - 1), true));
        checks.add(new CheckCase(
                id + "-upper", password, hash.substring(0, 3).toUpperCase(Locale.ROOT) + hash.substring(3), true));
    }

    private static ObjectNode input(String op, byte[] password) {
        ObjectNode in = OracleJson.MAPPER.createObjectNode();
        in.put("op", op);
        in.put("pw", HEX.formatHex(password));
        return in;
    }

    /** The text of {@code bytes} if they are valid UTF-8, else {@code null} (such passwords skip the String API). */
    static String utf8OrNull(byte[] bytes) {
        try {
            return UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static List<CheckCase> handWrittenCases() throws IOException {
        List<CheckCase> cases = new ArrayList<>();
        for (JsonNode c : OracleJson.MAPPER.readTree(CASES.toFile())) {
            cases.add(new CheckCase(
                    c.get("id").asText(),
                    HEX.parseHex(c.get("pw").asText()),
                    c.get("hash").asText(),
                    false));
        }
        return cases;
    }

    /** Inputs too large for the case file; every expected value is WordPress's, recorded on 2026-10-17. */
    private static List<CheckCase> programmaticCases() {
        byte[] x5000 = "x".repeat(5000).getBytes(US_ASCII);
        byte[] x4096 = "x".repeat(4096).getBytes(US_ASCII);
        byte[] x4095e = PhpBytes.utf8("x".repeat(4095) + "é");
        byte[] password = "password".getBytes(US_ASCII);
        String longTail = "a".repeat(100_000);
        return List.of(
                new CheckCase("limit-md5-5000", x5000, "a6bb7bde3251ca2d810d32dadd9e8ae7", false),
                new CheckCase("limit-phpass-5000", x5000, "$P$BSALTsaltaYJVJw9N5kh9bDQjaay8o0", false),
                new CheckCase("limit-phpass-4096", x4096, "$P$BSALTsaltIAjI22gtTX3z/B1yBCV39/", false),
                new CheckCase("limit-phpass-4095-plus-e", x4095e, "$P$BSALTsaltlJ8gKkIiuEw8rl8xErfQc1", false),
                new CheckCase("limit-md5-4095-plus-e", x4095e, "a5da82bee327f9a427fa98caefb364f1", false),
                new CheckCase("long-hash-wp", password, "$wp$2y$04$" + longTail, false),
                new CheckCase("long-hash-phpass", password, "$P$B" + longTail, false),
                new CheckCase("long-hash-bcrypt", password, "$2y$04$" + longTail, false),
                new CheckCase("long-hash-generic", password, "$generic$" + longTail, false));
    }
}
