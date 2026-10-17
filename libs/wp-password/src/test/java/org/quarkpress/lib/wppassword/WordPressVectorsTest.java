package org.quarkpress.lib.wppassword;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** The behaviour reference's vectors (section 12), each produced by running WordPress 7.1.3. */
class WordPressVectorsTest {

    static final Path FILE = Path.of("src/test/resources/wp-password-vectors.txt");
    static final String EMPTY = "<empty>";

    /** The cost-12 rows that are checked; the other cost-12 rows keep the CPU budget (at most two cost-12 runs). */
    static final Set<String> COST_12_CHECKED = Set.of("12.1#12", "12.2#16");

    /**
     * Rows where QuarkPress deliberately answers check differently from WordPress; each is a documented difference in
     * the README section "Differences from WordPress", never a silent skip. The expected value is QuarkPress's.
     */
    static final Map<String, Boolean> QUARKPRESS_CHECK = Map.of(
            "12.2#12", false, // README difference: $2x$ hashes are refused (WordPress accepts them)
            "12.6#1", false, // README difference: crypt formats are not verified, $1$
            "12.6#2", false, // README difference: crypt formats are not verified, $5$
            "12.6#3", false, // README difference: crypt formats are not verified, $6$
            "12.6#6", false, // README difference: argon2 is not verified, $argon2id$
            "12.4#4", false); // README difference: the $wp_hasher global is not modeled; without it $H$ is rejected

    record Vector(
            String id, String function, String context, byte[] password, String hash, String check, String rehash) {}

    static List<Vector> load() throws IOException {
        List<Vector> vectors = new ArrayList<>();
        for (String line : Files.readAllLines(FILE, UTF_8)) {
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split(" ", -1);
            if (f.length != 7) {
                throw new IllegalStateException("expected 7 fields: " + line);
            }
            vectors.add(new Vector(f[0], f[1], f[2], password(f[3]), EMPTY.equals(f[4]) ? "" : f[4], f[5], f[6]));
        }
        return vectors;
    }

    /** Hex bytes, {@code <hex byte>*<count>}, or {@code <empty>}. */
    static byte[] password(String field) {
        if (EMPTY.equals(field)) {
            return new byte[0];
        }
        int star = field.indexOf('*');
        if (star < 0) {
            return HexFormat.of().parseHex(field);
        }
        byte[] out = new byte[Integer.parseInt(field.substring(star + 1))];
        Arrays.fill(out, HexFormat.of().parseHex(field.substring(0, star))[0]);
        return out;
    }

    private static boolean skippedForCpu(Vector v) {
        return v.hash().contains("$2y$12$") && !COST_12_CHECKED.contains(v.id());
    }

    private static Vector row(List<Vector> vectors, String id) {
        return vectors.stream().filter(x -> x.id().equals(id)).findFirst().orElseThrow();
    }

    @Test
    void fileHoldsEveryRowOnceAndEveryKind() throws IOException {
        List<Vector> vectors = load();
        assertThat(vectors).isNotEmpty();
        assertThat(vectors.stream().map(Vector::id).distinct()).hasSameSizeAs(vectors);
        assertThat(vectors.stream().map(Vector::function).collect(java.util.stream.Collectors.toSet()))
                .containsExactlyInAnyOrder("wp_check_password", "app_check_password", "wp_hash_password");
        assertThat(vectors.stream().filter(v -> !v.rehash().equals("-"))).isNotEmpty();
        assertThat(vectors.stream().filter(v -> v.context().equals("wp_hasher")))
                .isNotEmpty();
    }

    @Test
    void checkMatchesWordPress() throws IOException {
        List<Vector> vectors = load();
        List<Vector> rows = vectors.stream()
                .filter(v -> v.function().equals("wp_check_password"))
                .toList();
        assertThat(rows).isNotEmpty();
        int checked = 0;
        int skipped = 0;
        for (Vector v : rows) {
            if (skippedForCpu(v)) {
                skipped++;
                continue;
            }
            boolean expected = QUARKPRESS_CHECK.getOrDefault(v.id(), Boolean.parseBoolean(v.check()));
            assertThat(WpPasswords.check(v.password(), v.hash())).as(v.id()).isEqualTo(expected);
            checked++;
        }
        // every row is checked except the cost-12 rows kept out for the CPU budget
        assertThat(checked).isPositive().isEqualTo(rows.size() - skipped);
        assertThat(skipped)
                .isEqualTo(rows.stream()
                        .filter(WordPressVectorsTest::skippedForCpu)
                        .count());
        assertThat(QUARKPRESS_CHECK.keySet()).allMatch(id -> rows.stream().anyMatch(r -> r.id().equals(id)));
        // the deliberate differences really differ from WordPress, so none is stale
        QUARKPRESS_CHECK.forEach((id, quarkpress) -> assertThat(
                        Boolean.parseBoolean(row(vectors, id).check()))
                .as(id)
                .isNotEqualTo(quarkpress));
    }

    @Test
    void applicationPasswordsMatchWordPress() throws IOException {
        List<Vector> rows = load().stream()
                .filter(v -> v.function().equals("app_check_password"))
                .toList();
        assertThat(rows).isNotEmpty();
        int checked = 0;
        for (Vector v : rows) {
            String secret = new String(v.password(), UTF_8);
            assertThat(WpPasswords.checkFastHash(secret, v.hash()))
                    .as(v.id())
                    .isEqualTo(Boolean.parseBoolean(v.check()));
            checked++;
        }
        assertThat(checked).isEqualTo(rows.size());
    }

    @Test
    void rehashMatchesWordPress() throws IOException {
        List<Vector> rows = load().stream().filter(v -> !v.rehash().equals("-")).toList();
        assertThat(rows).isNotEmpty();
        int checked = 0;
        for (Vector v : rows) {
            // With $wp_hasher set WordPress never asks for a rehash; QuarkPress does not model it (README difference).
            boolean expected = v.context().equals("wp_hasher") || Boolean.parseBoolean(v.rehash());
            assertThat(WpPasswords.needsRehash(v.hash())).as(v.id()).isEqualTo(expected);
            checked++;
        }
        assertThat(checked).isEqualTo(rows.size());
    }

    @Test
    void wordPressRefusesToHashOverlongPasswords() throws IOException {
        // 12.8#1: wp_hash_password of 4097 bytes returns "*", which never verifies
        Vector v = row(load(), "12.8#1");
        assertThat(v.function()).isEqualTo("wp_hash_password");
        assertThat(v.hash()).isEqualTo("*");
        assertThat(WpPasswords.check(v.password(), v.hash())).isFalse();
    }

    @Test
    void everyRowIsCoveredByOneKindOfCheck() throws IOException {
        // wp_check_password and app_check_password rows are checked above, wp_hash_password rows in the test above
        Set<String> covered = new LinkedHashSet<>();
        for (Vector v : load()) {
            switch (v.function()) {
                case "wp_check_password", "app_check_password", "wp_hash_password" -> covered.add(v.id());
                default -> throw new IllegalStateException("row without a check: " + v.id());
            }
        }
        assertThat(covered).hasSameSizeAs(load());
    }
}
