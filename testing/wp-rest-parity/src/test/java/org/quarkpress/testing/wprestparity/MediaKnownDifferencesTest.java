package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The known differences a media parity run can show, from the editor's capabilities and the skipped scenarios. */
class MediaKnownDifferencesTest {

    static final List<String> LINES = List.of(
            "# a comment",
            "",
            "m01 step1:outcome D1 always",
            "m07 step11:outcome D16 WebP accepted (needs gd_webp)",
            "m07 step12:outcome D16 AVIF accepted (needs gd_avif)",
            "m12 step2:files D9 an editor scenario");

    @Test
    void aFullEditorKeepsEveryLine() {
        MediaKnownDifferences.Effective all = MediaKnownDifferences.effective(LINES, List.of(), true, true);
        assertThat(all.text()).isEqualTo(String.join("\n", LINES) + "\n");
        assertThat(all.leftOutForFormats()).isZero();
    }

    @Test
    void aMissingFormatLeavesItsLinesOutAndCountsThem() {
        MediaKnownDifferences.Effective noAvif = MediaKnownDifferences.effective(LINES, List.of(), true, false);
        assertThat(noAvif.text().lines())
                .contains("m07 step11:outcome D16 WebP accepted (needs gd_webp)")
                .contains("# left out of this run: m07 step12:outcome D16 AVIF accepted (needs gd_avif)");
        assertThat(noAvif.leftOutForFormats()).isEqualTo(1);
        MediaKnownDifferences.Effective neither = MediaKnownDifferences.effective(LINES, List.of(), false, false);
        assertThat(neither.leftOutForFormats()).isEqualTo(2);
        assertThat(neither.text().lines())
                .contains("m01 step1:outcome D1 always", "m12 step2:files D9 an editor scenario");
    }

    @Test
    void aSkippedScenariosLinesAreNotCountedForAMissingFormat() {
        MediaKnownDifferences.Effective skipped = MediaKnownDifferences.effective(LINES, List.of("m07"), false, false);
        assertThat(skipped.leftOutForFormats()).isZero();
        assertThat(skipped.text().lines())
                .contains("# left out of this run: m07 step11:outcome D16 WebP accepted (needs gd_webp)");
    }

    @Test
    void skippedScenariosLeaveTheirLinesOut() {
        MediaKnownDifferences.Effective run = MediaKnownDifferences.effective(LINES, List.of("m12"), true, true);
        assertThat(run.text().lines())
                .contains(
                        "# left out of this run: m12 step2:files D9 an editor scenario", "m01 step1:outcome D1 always");
        assertThat(run.leftOutForFormats()).isZero();
    }
}
