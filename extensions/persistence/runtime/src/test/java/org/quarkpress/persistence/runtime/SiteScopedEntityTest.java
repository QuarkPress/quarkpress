package org.quarkpress.persistence.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.quarkpress.core.SiteId;

class SiteScopedEntityTest {

    @Test
    void aNewRowWithoutASiteSaysSo() {
        assertThatThrownBy(() -> new OptionEntity().site())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no site yet");
    }

    @Test
    void anAssignedSiteIsReported() {
        OptionEntity option = new OptionEntity();
        option.assignSite(SiteId.of(7));
        assertThat(option.site()).isEqualTo(SiteId.of(7));
    }

    @Test
    void aStoredRowKeepsItsSite() {
        OptionEntity option = new OptionEntity();
        option.assignSite(SiteId.of(7));
        option.rememberStoredSite();
        assertThatThrownBy(() -> option.assignSite(SiteId.of(8)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cannot change");
        assertThat(option.site()).isEqualTo(SiteId.of(7));
    }

    @Test
    void unpairedSurrogatesAreRefusedAndPairsAccepted() {
        String pair = new String(Character.toChars(0x1F600));
        assertThat(QuarkPressJsonFormatMapper.requireWellFormed("\"" + pair + "\""))
                .contains(pair);
        assertThatThrownBy(() -> QuarkPressJsonFormatMapper.requireWellFormed("\"" + (char) 0xDC00 + "\""))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogate");
        assertThatThrownBy(() -> QuarkPressJsonFormatMapper.requireWellFormed("\"" + (char) 0xD800))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unpaired surrogate");
    }
}
