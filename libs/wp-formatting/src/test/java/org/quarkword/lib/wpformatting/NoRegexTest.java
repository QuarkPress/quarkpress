package org.quarkword.lib.wpformatting;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** No regular expressions in main code: every scanner is hand-written and linear. */
class NoRegexTest {

    private static final Path MAIN = Path.of("src/main/java");
    private static final List<String> FORBIDDEN = List.of(
            "java.util.regex",
            "java.util.Scanner",
            ".matches(",
            ".split(",
            ".splitWithDelimiters(",
            ".replaceAll(",
            ".replaceFirst(",
            ".asPredicate(",
            "::matches",
            "::split",
            "::replaceAll",
            "::replaceFirst",
            "Pattern.",
            "Pattern::");
    /** This library's own scanner, whose name contains ".split(". */
    private static final String OWN_SPLITTER = "HtmlSplitter.split(";

    @Test
    void mainCodeUsesNoRegularExpressions() throws IOException {
        List<String> found = new ArrayList<>();
        List<Path> files;
        try (Stream<Path> walk = Files.walk(MAIN)) {
            files = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        assertThat(files).isNotEmpty();
        for (Path file : files) {
            List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                for (String forbidden : offences(lines.get(i))) {
                    found.add(file + ":" + (i + 1) + ": " + forbidden);
                }
            }
        }
        assertThat(found).as("regular expressions in main code").isEmpty();
    }

    /** The forbidden tokens on one source line, with calls to this library's own splitter left out. */
    static List<String> offences(String line) {
        String checked = line.replace(OWN_SPLITTER, "");
        return FORBIDDEN.stream().filter(checked::contains).toList();
    }

    @Test
    void theLineCheckerCatchesEveryForm() {
        assertThat(offences("s.split(\",\")")).containsExactly(".split(");
        assertThat(offences("list.stream().map(String::split)")).contains("::split");
        assertThat(offences("s.splitWithDelimiters(x, 0)")).contains(".splitWithDelimiters(");
        assertThat(offences("new java.util.Scanner(s)")).containsExactly("java.util.Scanner");
        assertThat(offences("p.asPredicate()")).containsExactly(".asPredicate(");
        assertThat(offences("Pattern::compile")).containsExactly("Pattern::");
        assertThat(offences("HtmlSplitter.split(text)")).isEmpty();
        assertThat(offences("int n = parts.size();")).isEmpty();
    }
}
