package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.MissingNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class EventsTest {

    private static final Instant AT = Instant.parse("2026-10-17T12:00:00Z");

    @Test
    void optionChangedCannotBeChangedThroughItsValues() {
        ObjectNode after = JsonNodeFactory.instance.objectNode().put("a", 1);
        OptionChanged event = new OptionChanged(SiteId.of(1), AT, "x", MissingNode.getInstance(), after);
        after.put("a", 2);
        ((ObjectNode) event.after()).put("a", 3);
        assertThat(event.after().get("a").intValue()).isEqualTo(1);
        assertThat(event.before().isMissingNode()).isTrue();
    }

    @Test
    void eventsRejectMissingParts() {
        assertThatThrownBy(() -> new OptionChanged(null, AT, "x", MissingNode.getInstance(), MissingNode.getInstance()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new NetworkOptionChanged(AT, "x", null, MissingNode.getInstance()))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new SiteFeatureToggled(SiteId.of(1), null, "k", true))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void siteEventsExposeSiteAndTime() {
        SiteEvent event = new SiteFeatureToggled(SiteId.of(4), AT, "revisions", false);
        assertThat(event.site()).isEqualTo(SiteId.of(4));
        assertThat(event.at()).isEqualTo(AT);
    }
}
