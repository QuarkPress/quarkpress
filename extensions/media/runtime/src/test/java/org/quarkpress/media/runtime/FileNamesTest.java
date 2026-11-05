package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.quarkpress.media.runtime.DefaultMimeTypes.PATTERNS;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.wporacle.GeneratedCases;

/** WordPress's file-name sanitizing, with the values WordPress 7.1.3 gave for each example. */
class FileNamesTest {

    static final AllowedTypes AUTHOR = AllowedTypes.of(PATTERNS, false, List.of(), Optional.empty());
    static final AllowedTypes ADMIN = AllowedTypes.of(PATTERNS, true, List.of(), Optional.empty());

    private final AllowedTypes author = AUTHOR;
    private final AllowedTypes admin = ADMIN;

    static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                // the reference's table, for an author
                "my photo & co.png|my-photo-co.png",
                "'  --.hidden.png..'|hidden.png",
                ".jpg|unnamed-file.jpg",
                "JPG|unnamed-file.JPG",
                "README|README",
                "shell.php.jpg|shell.php_.jpg",
                "archive.tar.gz|archive.tar.gz",
                "report.final.v2.pdf|report.final_.v2.pdf",
                "page.html.jpg|page.html_.jpg",
                "my%20file.png|my20file.png",
                "photo x.jpg|photo-x.jpg",
                "a+b.png|ab.png",
                "'._-x.png-_.'|x.png",
                "....jpg|unnamed-file.jpg",
                // further rows WordPress gave
                // WordPress's function gives x-.jpg (the dash run before the dot is kept); the facts summary's x.jpg
                // is not among the recorded calls
                "'x .jpg'|x-.jpg",
                "jpg|unnamed-file.jpg",
                "html|unnamed-file.html",
                "x.jpg.php|x.jpg.php",
                "My.Photo.JPEG|My.Photo_.JPEG",
                "a.b1.c.jpg|a.b1.c.jpg",
                "a.abcdef.jpg|a.abcdef.jpg",
                "a.ab12.jpg|a.ab12.jpg",
                "'a--b  c.png'|a-b-c.png",
                "a..b.jpg|a.b.jpg"
            })
    void wordPressExamples(String name, String expected) {
        assertThat(FileNames.sanitize(name, author)).isEqualTo(expected);
    }

    @Test
    void accentsAndOtherScripts() {
        assertThat(FileNames.sanitize(cp('E', 0x301) + "t" + cp(0xE9) + " " + cp(0xE0) + " Paris (2).JPG", author))
                .isEqualTo("Ete-a-Paris-2.JPG");
        assertThat(FileNames.sanitize(cp(0x424, 0x43E, 0x442, 0x43E) + " 1.jpg", author))
                .isEqualTo(cp(0x424, 0x43E, 0x442, 0x43E) + "-1.jpg");
        assertThat(FileNames.sanitize("a_b" + cp(0x2014) + "c.png", author)).isEqualTo("a_b" + cp(0x2014) + "c.png");
        assertThat(FileNames.sanitize("a" + cp(0xA0) + "b" + cp(0x3000) + "c.png", author))
                .isEqualTo("a-b-c.png");
        assertThat(FileNames.sanitize("x" + cp(0x1F600) + ".png", author)).isEqualTo("x" + cp(0x1F600) + ".png");
        // WordPress's German rules give Gruesse.jpg; QuarkPress uses the default locale's rules everywhere
        assertThat(FileNames.sanitize("Gr" + cp(0xFC) + cp(0xDF) + "e.jpg", author))
                .isEqualTo("Gruse.jpg");
        assertThat(FileNames.sanitize(cp(0xC6) + "ble " + cp(0xA3) + "5 " + cp(0x20AC) + ".png", author))
                .isEqualTo("AEble-5-E.png");
        assertThat(FileNames.sanitize("a".repeat(300) + ".jpg", author)).hasSize(304);
        assertThat(FileNames.sanitize(
                        cp(0x2019) + "q" + cp(0xAB) + cp(0xBB) + cp(0x201C) + cp(0x201D) + ".png", author))
                .isEqualTo("q.png");
    }

    @Test
    void everyRemovedCharacterGoes() {
        String removed = "?[]=<>:;,\"$#*|~\\`!{}" + cp(0x2019, 0xAB, 0xBB, 0x201C, 0x201D);
        assertThat(FileNames.sanitize("a" + removed + "b.png", author)).isEqualTo("ab.png");
    }

    @Test
    void everyUnicodeSpaceBecomesADash() {
        for (int space : new int[] {0x1680, 0x200A, 0x202F, 0x205F}) {
            assertThat(FileNames.sanitize("a" + cp(space) + "b.png", author))
                    .as("U+%04X", space)
                    .isEqualTo("a-b.png");
        }
    }

    @Test
    void anAdministratorKeepsAnHtmlMiddleExtension() {
        assertThat(FileNames.sanitize("page.html.jpg", admin)).isEqualTo("page.html.jpg");
    }

    @Test
    void pathCharactersNeverSurvive() {
        assertThat(FileNames.sanitize("../../etc/passwd.jpg", author)).isEqualTo("etcpasswd.jpg");
        assertThat(FileNames.sanitize("a\\b.jpg", author)).isEqualTo("ab.jpg");
        assertThat(FileNames.sanitize("x" + cp(0) + ".jpg", author)).isEqualTo("x.jpg");
        assertThat(FileNames.sanitize("%2e%2e%2fx.jpg", author)).isEqualTo("2e2e2fx.jpg");
        assertThat(FileNames.sanitize("a\r\n\tb.jpg", author)).isEqualTo("a-b.jpg");
        for (String name : GeneratedCases.sample(Gens.strings(Gens.unicodeChars(), 0, 40), "m2a-file-names", 2000)) {
            String clean = FileNames.sanitize(name, author);
            assertThat(clean)
                    .doesNotContain("/", "\\", "\u0000", "..")
                    .doesNotStartWith(".")
                    .doesNotStartWith("-");
        }
    }

    @Test
    void theNameWithoutItsExtension() {
        assertThat(FileNames.withoutExtension("photo.final.jpg")).isEqualTo("photo.final");
        assertThat(FileNames.withoutExtension("README")).isEqualTo("README");
        assertThat(FileNames.withoutExtension(".jpg")).isEmpty();
        assertThat(FileNames.withoutExtension("x.")).isEqualTo("x");
    }
}
