package org.quarkpress.lib.wpkses;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NullFilterTest {

    @Test
    void matchesWordPress() {
        Examples.assertFile("no-null.json");
    }

    @Test
    void keepsTabLineFeedCarriageReturnAndDelete() {
        assertThat(NullFilter.clean("a\tb\nc\rd\177e", false)).isEqualTo("a\tb\nc\rd\177e");
    }

    @Test
    void removesEveryOtherC0Control() {
        StringBuilder all = new StringBuilder();
        for (char c = 0; c < 0x20; c++) {
            all.append(c);
        }
        assertThat(NullFilter.clean(all.toString(), true)).isEqualTo("\t\n\r");
    }

    @Test
    void slashZeroRunsAreRemovedOnlyWhenAsked() {
        assertThat(NullFilter.clean("x\\\\00y", false)).isEqualTo("xy");
        assertThat(NullFilter.clean("x\\\\00y", true)).isEqualTo("x\\\\00y");
        assertThat(NullFilter.clean("x\\y\\", false)).isEqualTo("x\\y\\");
    }

    @Test
    void returnsTheSameInstanceWhenNothingChanges() {
        String clean = "plain <b>text</b>";
        assertThat(NullFilter.clean(clean, false)).isSameAs(clean);
    }
}
