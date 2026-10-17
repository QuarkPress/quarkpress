package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class TokenizerTest {

    /** Token spans that WordPress's own split pattern finds (preg_match_all with it, PHP 8.4). */
    private static final Object[][] SPANS = {
        {"a<b>c</b>d", List.of("<b>", "</b>")},
        {"<!-- a\nb -->", List.of("<!-- a\nb -->")},
        {"<!-- a > \nb -->", List.of("<!-- a >", ">")},
        {"<!-- a\nb", List.of("<!-- a\nb")},
        {"<!-- a\n", List.of("<!-- a")},
        {"x<!-- a\nb <b>c</b>", List.of("<!-- a\nb <b>", "</b>")},
        {"</>>", List.of("</>>")},
        {"</ x>", List.of("</ x>")},
        {"</3", List.of("</3")},
        {"<!x", List.of("<!x")},
        {"<!-->", List.of("<!-->")},
        {"a > b", List.of(">")},
        {"<<b>>", List.of("<<b>", ">")},
        {"<b", List.of("<b")},
        {"<!doctype html>x", List.of("<!doctype html>")},
        {"<![CDATA[ <b>x</b> ]]>", List.of("<![CDATA[ <b>", "</b>", ">")},
        {"<!-- a --> b -->", List.of("<!-- a -->", ">")},
        {"<a title=\">\">gt</a>", List.of("<a title=\">", ">", "</a>")},
        {"x<\n", List.of("<\n")},
        {"<!--x-->\n", List.of("<!--x-->")}
    };

    @Test
    void findsTheSameTokensAsWordPress() {
        for (Object[] row : SPANS) {
            assertThat(tokens((String) row[0])).as("%s", row[0]).isEqualTo(row[1]);
        }
    }

    @Test
    void copiesTextBetweenTokens() {
        String in = "a<b>c</b>d > e <!-- f --> g";
        assertThat(Tokenizer.rewrite(in, (token, out) -> out.append(token))).isEqualTo(in);
        assertThat(Tokenizer.rewrite(
                        in, (token, out) -> out.append('[').append(token).append(']')))
                .isEqualTo("a[<b>]c[</b>]d [>] e [<!-- f -->] g");
    }

    @Test
    void unterminatedCommentsAndDeclarationsStayLinear() {
        String[] inputs = {
            "<!--x>".repeat(200_000) + "\nz",
            "</".repeat(500_000),
            "<!".repeat(500_000),
            "<".repeat(1_000_000),
            "<!--".repeat(250_000) + "\n\n"
        };
        for (String in : inputs) {
            List<String> found = assertTimeoutPreemptively(Duration.ofSeconds(10), () -> tokens(in));
            assertThat(String.join("", found).length()).isLessThanOrEqualTo(in.length());
            // Output property (ruling M3): copying every token back reproduces the input exactly.
            assertThat(Tokenizer.rewrite(in, (token, out) -> out.append(token))).isEqualTo(in);
        }
    }

    private static List<String> tokens(String s) {
        List<String> tokens = new ArrayList<>();
        Tokenizer.rewrite(s, (token, out) -> tokens.add(token));
        return tokens;
    }
}
