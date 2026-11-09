package org.quarkpress.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * The file name of a raw-body upload as WordPress reads it from {@code Content-Disposition}: each header line on its
 * own, the last one that gives a name wins, the pieces split at every semicolon, only the exact name
 * {@code filename}, one pair of quotes removed and nothing unescaped.
 */
class ContentDispositionTest {

    @Test
    void theReferenceTable() {
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\"photo.jpg\"")))
                .contains("photo.jpg");
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=photo.jpg")))
                .contains("photo.jpg");
        assertThat(ContentDisposition.fileName(List.of("inline; filename=\"a.jpg\"")))
                .contains("a.jpg");
        assertThat(ContentDisposition.fileName(List.of("attachment"))).isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; FILENAME=\"a.jpg\"")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename*=UTF-8''%C3%A9t%C3%A9.jpg")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\"a;b.jpg\"")))
                .contains("\"a");
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\"\"")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a.png", "inline; filename=b.png")))
                .contains("b.png");
    }

    @Test
    void theDispositionTypeIsNotChecked() {
        assertThat(ContentDisposition.fileName(List.of("form-data; filename=x.png")))
                .contains("x.png");
        assertThat(ContentDisposition.fileName(List.of("; filename=x.png"))).contains("x.png");
        assertThat(ContentDisposition.fileName(List.of("anything at all; filename=x.png")))
                .contains("x.png");
        // without a semicolon there is nothing to read, even a name
        assertThat(ContentDisposition.fileName(List.of("filename=x.png"))).isEmpty();
    }

    @Test
    void namesAndValuesAreTrimmedAsPhpTrims() {
        assertThat(ContentDisposition.fileName(List.of("attachment;   filename  =  \"x.png\"  ")))
                .contains("x.png");
        assertThat(ContentDisposition.fileName(List.of("attachment;\tfilename\t=\tx.png\r\n")))
                .contains("x.png");
        // only the characters PHP's trim removes: a form feed is kept
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\fx.png")))
                .contains("\fx.png");
        // a value of blanks alone is empty once trimmed
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=   ")))
                .isEmpty();
    }

    @Test
    void eachPieceIsSplitAtItsFirstEqualsSign() {
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a=b.png")))
                .contains("a=b.png");
        // a piece without '=' is skipped
        assertThat(ContentDisposition.fileName(List.of("attachment; filename; size=3")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; size; filename=x.png")))
                .contains("x.png");
    }

    @Test
    void quotesAreRemovedOncePairedAndNeverUnescaped() {
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\"\"x.png\"\"")))
                .contains("\"x.png\"");
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\"x.png")))
                .contains("\"x.png");
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=x.png\"")))
                .contains("x.png\"");
        // a single quote character is not a pair
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\"")))
                .contains("\"");
        assertThat(ContentDisposition.fileName(List.of("attachment; filename='x.png'")))
                .contains("'x.png'");
    }

    @Test
    void theLastFilenamePieceDecidesEvenWhenEmpty() {
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a.png; filename=b.png")))
                .contains("b.png");
        // a later empty piece, in the same line or a later one, leaves no name (WordPress, e05)
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a.png; filename=")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a.png; filename=\"\"")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a.png", "inline; filename=\"\"")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a.png", "attachment; filename=")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a.png", "attachment; filename=0")))
                .isEmpty();
        // a later line without a filename piece leaves the earlier line's name
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a.png", "attachment")))
                .contains("a.png");
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=a.png", "inline; size=3")))
                .contains("a.png");
        assertThat(ContentDisposition.fileName(List.of())).isEmpty();
    }

    @Test
    void aNameEmptyInPhpsSenseIsNoName() {
        // "0" is empty to PHP (WordPress, e05), quoted or not; "00" and " 0" (trimmed to "0") as PHP reads them
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=0")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\"0\"")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename= 0 ")))
                .isEmpty();
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=00")))
                .contains("00");
    }

    @Test
    void hostileValuesStayBounded() {
        String many = "x" + "; a=b".repeat(100_000) + "; filename=z.png";
        // warmed up once: the bound tells linear from quadratic work, not the JIT's first pass
        ContentDisposition.fileName(List.of(many));
        long start = System.nanoTime();
        assertThat(ContentDisposition.fileName(List.of(many))).contains("z.png");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);

        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            lines.add("attachment; filename=f" + i + ".png");
        }
        start = System.nanoTime();
        assertThat(ContentDisposition.fileName(lines)).contains("f999.png");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);

        String semicolons = "attachment" + ";".repeat(200_000) + "filename=s.png";
        start = System.nanoTime();
        assertThat(ContentDisposition.fileName(List.of(semicolons))).contains("s.png");
        assertThat((System.nanoTime() - start) / 1_000_000).isLessThan(1_000);

        // a NUL inside the name and an escaped quote are kept as they are
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\"a\u0000b.png\"")))
                .contains("a\u0000b.png");
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\"a\\\"b\"")))
                .contains("a\\\"b");
        // PHP's trim removes a NUL at either end
        assertThat(ContentDisposition.fileName(List.of("attachment; filename=\u0000x.png\u0000")))
                .contains("x.png");
    }
}
