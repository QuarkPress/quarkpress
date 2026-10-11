package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Fixtures shaped like real WordPress option values and post meta. They were written by hand from
 * the public structure of those options (clean-room), with byte lengths computed by a script.
 */
class WordPressCorpusTest {

    static byte[] fixture(String name) throws IOException {
        try (InputStream in = WordPressCorpusTest.class.getResourceAsStream("/wp/" + name)) {
            assertThat(in).as("fixture " + name).isNotNull();
            return in.readAllBytes();
        }
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "sidebars_widgets.ser",
                "widget_block.ser",
                "attachment_metadata.ser",
                "wp_user_roles.ser",
                "cron.ser",
                "theme_mods.ser"
            })
    void fixtureParsesAndRoundTripsByteIdentically(String name) throws IOException {
        byte[] bytes = fixture(name);
        assertThat(PhpSerialize.looksSerialized(new String(bytes, java.nio.charset.StandardCharsets.UTF_8)))
                .isTrue();
        PhpValue v = PhpSerialize.parse(bytes);
        assertThat(PhpSerialize.serialize(v)).isEqualTo(bytes);
    }

    @Test
    @SuppressWarnings("unchecked")
    void sidebarsWidgetsConvertsToMapOfLists() throws IOException {
        Map<String, Object> m = (Map<String, Object>)
                PhpSerialize.parse(fixture("sidebars_widgets.ser")).toJavaObject();
        assertThat(m).containsKeys("wp_inactive_widgets", "sidebar-1", "array_version");
        assertThat((List<Object>) m.get("sidebar-1")).containsExactly("block-2", "block-3", "block-4");
        assertThat(m.get("array_version")).isEqualTo(3L);
    }

    @Test
    @SuppressWarnings("unchecked")
    void widgetBlockKeepsBlockMarkupIntact() throws IOException {
        Map<String, Object> m = (Map<String, Object>)
                PhpSerialize.parse(fixture("widget_block.ser")).toJavaObject();
        assertThat(m).containsKeys("2", "3", "_multiwidget");
        Map<String, Object> w3 = (Map<String, Object>) m.get("3");
        assertThat(w3.get("content"))
                .isEqualTo("<!-- wp:latest-posts {\"postsToShow\":5,\"displayPostDate\":true} /-->");
    }

    @Test
    @SuppressWarnings("unchecked")
    void attachmentMetadataExposesSizesAndFloats() throws IOException {
        Map<String, Object> m = (Map<String, Object>)
                PhpSerialize.parse(fixture("attachment_metadata.ser")).toJavaObject();
        assertThat(m.get("width")).isEqualTo(2048L);
        Map<String, Object> sizes = (Map<String, Object>) m.get("sizes");
        assertThat(sizes).containsKeys("thumbnail", "medium", "medium_large", "large");
        Map<String, Object> thumb = (Map<String, Object>) sizes.get("thumbnail");
        assertThat(thumb.get("mime-type")).isEqualTo("image/jpeg");
        Map<String, Object> meta = (Map<String, Object>) m.get("image_meta");
        assertThat(meta.get("aperture")).isEqualTo("2.8");
        assertThat(meta.get("keywords")).isEqualTo(List.of());
    }

    @Test
    @SuppressWarnings("unchecked")
    void userRolesCapabilitiesAreBooleans() throws IOException {
        Map<String, Object> roles = (Map<String, Object>)
                PhpSerialize.parse(fixture("wp_user_roles.ser")).toJavaObject();
        Map<String, Object> admin = (Map<String, Object>) roles.get("administrator");
        assertThat(admin.get("name")).isEqualTo("Administrator");
        Map<String, Object> caps = (Map<String, Object>) admin.get("capabilities");
        assertThat(caps).containsEntry("manage_options", true).containsEntry("edit_posts", true);
        Map<String, Object> subscriber = (Map<String, Object>) roles.get("subscriber");
        assertThat((Map<String, Object>) subscriber.get("capabilities")).containsOnlyKeys("read");
    }

    @Test
    void corruptedSiteUrlFailsStrictlyAndRepairs() throws IOException {
        byte[] bytes = fixture("corrupted_siteurl.ser");
        assertThatThrownBy(() -> PhpSerialize.parse(bytes)).isInstanceOf(PhpSerializeException.class);
        PhpArray a = (PhpArray) PhpSerialize.parse(bytes, ParseOptions.DEFAULTS.withRepairLengths(true));
        assertThat(((PhpString) a.get("siteurl")).text()).isEqualTo("https://blog.example.org/wordpress");
        assertThat(((PhpString) a.get("home")).text()).isEqualTo("https://blog.example.org");
        assertThat(((PhpString) a.get("blogname")).text()).isEqualTo("Example Blog");
        // The repaired value re-serializes with correct lengths and then parses strictly.
        byte[] fixed = PhpSerialize.serialize(a);
        assertThat(PhpSerialize.parse(fixed)).isEqualTo(a);
        assertThat(fixed).isNotEqualTo(bytes);
    }
}
