package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * wpautop on the input classes no single reader item covers. Each expected output is WordPress's answer, recorded
 * in the shared case file by a parity run; these tests look the case up by id so both tiers check the same value.
 */
class AutopTest {

    private static final List<RecordedCase> CASES = RecordedCases.load();

    private static void assertCase(String id, String input) {
        RecordedCase c = CASES.stream()
                .filter(x -> x.id().equals(id))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no case " + id));
        assertThat(c.fn()).as("fn of %s", id).isEqualTo("autop");
        assertThat(c.in()).as("input of %s", id).isEqualTo(input);
        assertThat(c.out()).as("recorded output of %s", id).isNotNull();
        assertThat(Formatting.autop(input)).as(id).isEqualTo(c.out().asText());
    }

    @Test
    void zeroIsText() {
        assertCase("t2-zero", "0");
    }

    // Review Focus 1: U+2028, NEL, FF and VT are not PHP's idea of a line or of trimmable space
    @Test
    void unicodeLineSeparatorsAreText() {
        assertCase("rf1-autop-1", "<p><li>a b</p>");
        assertCase("rf1-autop-2", "a\u0085\n\nb");
        assertCase("rf1-autop-3", "a\f\n\fb");
        assertCase("rf1-autop-4", "\u000b");
    }

    // Review Focus 3
    @Test
    void unterminatedAtEnd() {
        assertCase("rf3-autop-1", "x <");
        assertCase("rf3-autop-2", "x <!--");
        assertCase("rf3-autop-3", "<pre>x");
        assertCase("rf3-autop-4", "<script>x\n\ny");
        assertCase("rf3-autop-5", "x\n\n<![CDATA[");
    }

    // Review Focus 4 (A-G47, G62, G63 and G144 are in the case file)
    @Test
    void authoredMarkers() {
        assertCase("rf4-autop-1", "<pre>a<pre wp-pre-tag-0></pre>b</pre>");
        assertCase("rf4-autop-2", " <!-- wpnl --> <pre>x\n\ny</pre>");
        assertCase("rf4-autop-3", "<WPPreserveNewline /><script>\n</script>");
    }

    @Test
    void brDefaultsToTrue() {
        assertThat(Formatting.autop("a\nb")).isEqualTo(Formatting.autop("a\nb", true));
        assertThat(Formatting.autop("a\nb", false)).isEqualTo("<p>a\nb</p>\n");
    }

    @Test
    void preserveNewlinesReplacesEveryLineFeed() {
        assertThat(Autop.preserveNewlines("\na\r\n\n"))
                .isEqualTo("<WPPreserveNewline />a\r<WPPreserveNewline />" + "<WPPreserveNewline />");
        assertThat(Autop.preserveNewlines("")).isEmpty();
    }

    @Test
    void nullIsRejected() {
        assertThatNullPointerException().isThrownBy(() -> Formatting.autop(null));
        assertThatNullPointerException().isThrownBy(() -> Formatting.autop(null, false));
        assertThatNullPointerException().isThrownBy(() -> Formatting.autop(null, AutopOptions.defaults()));
        assertThatNullPointerException().isThrownBy(() -> Formatting.autop("a", null));
    }

    /** {@code k} pre blocks, each holding a typed placeholder for the next one. */
    private static String chain(int k) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < k; i++) {
            s.append("<pre>a<pre wp-pre-tag-").append(i + 1).append("></pre>");
        }
        return s.toString();
    }

    /** WordPress's output for {@link #chain}: block i gets every later block put inside it. */
    private static long chainOutputLength(int k) {
        long placeholder = ("<pre wp-pre-tag-" + k + "></pre>").length();
        return 3L * k * (k + 1) + k * (placeholder + 1);
    }

    @Test
    void optionsDefaultToWordPress() {
        AutopOptions defaults = AutopOptions.defaults();
        assertThat(defaults.lineBreaks()).isTrue();
        assertThat(defaults.maxOutputLength()).isEmpty();
        assertThat(Formatting.autop("a\nb", defaults)).isEqualTo(Formatting.autop("a\nb"));
        assertThat(Formatting.autop(
                        "a\nb", AutopOptions.builder().lineBreaks(false).build()))
                .isEqualTo(Formatting.autop("a\nb", false));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> AutopOptions.builder().maxOutputLength(-1));
    }

    // Task 2 fix round: WordPress's answers for chains of 3 and 300 (parity run of the reviewer's harness, php)
    @Test
    void placeholderChainsMatchWordPress() {
        assertThat(Formatting.autop(chain(3)))
                .isEqualTo("<pre>a<pre>a<pre>a<pre wp-pre-tag-3></pre>\n<pre>a<pre>a<pre wp-pre-tag-3></pre>\n"
                        + "<pre>a<pre wp-pre-tag-3></pre>\n");
        String out = Formatting.autop(chain(300));
        assertThat(out).hasSize(279_000);
        assertThat((long) out.length()).isEqualTo(chainOutputLength(300));
        assertThat(md5(out)).isEqualTo("d599cfdb0957e8c772455498885cedaf");
    }

    @Test
    void defaultCapIsSixteenMebibytesOfOutput() {
        assertThat(chainOutputLength(2_000)).isEqualTo(12_062_000L);
        assertThat(Formatting.autop(chain(2_000))).hasSize(12_062_000);

        assertThatThrownBy(() -> Formatting.autop(chain(3_000)))
                .isInstanceOfSatisfying(FormattingLimitException.class, e -> {
                    assertThat(e.requiredLength()).isEqualTo(chainOutputLength(3_000));
                    assertThat(e.allowedLength()).isEqualTo(16L * 1024 * 1024);
                });
    }

    @Test
    void defaultCapGrowsWithTheInput() {
        String padding = "x".repeat(300_000) + "\n\n";
        String input = padding + chain(4_000);
        assertThat(64L * input.length()).isGreaterThan(16L * 1024 * 1024);
        assertThatThrownBy(() -> Formatting.autop(input)).isInstanceOfSatisfying(FormattingLimitException.class, e -> {
            assertThat(e.allowedLength()).isEqualTo(64L * input.length());
            assertThat(e.requiredLength())
                    .isEqualTo(chainOutputLength(4_000) + "<p>".length() + padding.length() - 2 + "</p>\n".length());
        });
    }

    @Test
    void customCapReplacesTheDefault() {
        AutopOptions exact = AutopOptions.builder().maxOutputLength(111).build();
        assertThat(Formatting.autop(chain(3), exact)).hasSize(111);

        AutopOptions tight = AutopOptions.builder().maxOutputLength(110).build();
        assertThatThrownBy(() -> Formatting.autop(chain(3), tight))
                .isInstanceOf(FormattingLimitException.class)
                .hasMessage("wpautop would build 111 characters, more than the limit of 110")
                .isInstanceOfSatisfying(FormattingLimitException.class, e -> {
                    assertThat(e.requiredLength()).isEqualTo(111);
                    assertThat(e.allowedLength()).isEqualTo(110);
                });

        AutopOptions roomy = AutopOptions.builder().maxOutputLength(30_000_000).build();
        assertThat((long) Formatting.autop(chain(3_000), roomy).length()).isEqualTo(chainOutputLength(3_000));
        // text without pre blocks grows only linearly, so it is checked once built
        assertThat(Formatting.autop(
                        "abc", AutopOptions.builder().maxOutputLength(11).build()))
                .isEqualTo("<p>abc</p>\n");
        assertThatThrownBy(() -> Formatting.autop(
                        "abc", AutopOptions.builder().maxOutputLength(10).build()))
                .isInstanceOf(FormattingLimitException.class)
                .hasMessage("wpautop would build 11 characters, more than the limit of 10");
    }

    private static String md5(String text) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
