package org.quarkpress.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class StoredKeyTest {

    @ParameterizedTest
    @ValueSource(strings = {"a", "photo.jpg", "sites/2/2026/11/photo-150x150.jpg", ".hidden", "a/..b/c.", "a b/c"})
    void validKeysAreReturnedAsTheyAre(String key) {
        assertThat(StoredKey.require(key)).isSameAs(key);
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "",
                "/a",
                "a/",
                "a//b",
                ".",
                "..",
                "a/./b",
                "a/../b",
                "a/..",
                "a\\b",
                "a\u0000b",
                "a\nb",
                "a\u001fb",
                "\ud800",
                "a\udc00"
            })
    void invalidKeysAreRefused(String key) {
        assertThatThrownBy(() -> StoredKey.require(key)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNullKeyIsRefused() {
        assertThatThrownBy(() -> StoredKey.require(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void keysAreAtMost1024BytesInUtf8() {
        assertThat(StoredKey.require("a".repeat(1024))).hasSize(1024);
        assertThatThrownBy(() -> StoredKey.require("a".repeat(1025))).isInstanceOf(IllegalArgumentException.class);
        String e = new String(Character.toChars(0xE9));
        assertThat(StoredKey.require(e.repeat(512))).hasSize(512);
        assertThatThrownBy(() -> StoredKey.require(e.repeat(512) + "a")).isInstanceOf(IllegalArgumentException.class);
        String euro = new String(Character.toChars(0x20AC));
        assertThatThrownBy(() -> StoredKey.require(euro.repeat(342))).isInstanceOf(IllegalArgumentException.class);
        String emoji = new String(Character.toChars(0x1F600));
        assertThat(StoredKey.require(emoji.repeat(256))).isNotNull();
        assertThatThrownBy(() -> StoredKey.require(emoji.repeat(256) + "a"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
