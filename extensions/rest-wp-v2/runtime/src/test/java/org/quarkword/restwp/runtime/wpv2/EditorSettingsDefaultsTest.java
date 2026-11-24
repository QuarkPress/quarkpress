package org.quarkword.restwp.runtime.wpv2;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

/** The recorded editor defaults are checked when the application starts: every key the settings read must be there. */
class EditorSettingsDefaultsTest {

    private static ObjectNode shipped() throws IOException {
        try (InputStream in = EditorSettings.class.getResourceAsStream(EditorSettings.DEFAULTS)) {
            return (ObjectNode) new ObjectMapper().readTree(in);
        }
    }

    @Test
    void theShippedResourceHoldsEveryKey() throws IOException {
        ObjectNode defaults = shipped();
        assertThat(EditorSettings.checked(defaults)).isSameAs(defaults);
        assertThat(defaults.size()).isEqualTo(EditorSettings.FLAGS.size() + EditorSettings.RECORDED.size());
    }

    @Test
    void aMissingKeyOrANonBooleanFlagIsRefused() throws IOException {
        ObjectNode withoutColors = shipped();
        withoutColors.remove("colors");
        assertThatThrownBy(() -> EditorSettings.checked(withoutColors))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("colors");
        ObjectNode textFlag = shipped();
        textFlag.put("disableLayoutStyles", "false");
        assertThatThrownBy(() -> EditorSettings.checked(textFlag))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("disableLayoutStyles");
    }
}
