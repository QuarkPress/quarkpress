package org.quarkpress.lib.phpserialize;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class RepairLengthsTest {

    static final ParseOptions REPAIR = ParseOptions.DEFAULTS.withRepairLengths(true);

    @Test
    void repairsTooShortDeclaredLength() {
        assertThat(PhpSerialize.parse("s:5:\"hello world\";", REPAIR)).isEqualTo(PhpString.of("hello world"));
    }

    @Test
    void repairsTooLongDeclaredLength() {
        assertThat(PhpSerialize.parse("s:11:\"hello\";", REPAIR)).isEqualTo(PhpString.of("hello"));
    }

    @Test
    void repairsLengthLargerThanRemainingInput() {
        assertThat(PhpSerialize.parse("s:999:\"hello\";", REPAIR)).isEqualTo(PhpString.of("hello"));
    }

    @Test
    void repairsInsideArrayAfterSearchReplace() {
        // "http://old.example" (18 bytes) was replaced by a 23-byte URL without fixing the length.
        PhpArray a = (PhpArray) PhpSerialize.parse(
                "a:2:{s:4:\"home\";s:18:\"https://new.example.org\";s:4:\"name\";s:4:\"Blog\";}", REPAIR);
        assertThat(((PhpString) a.get("home")).text()).isEqualTo("https://new.example.org");
        assertThat(((PhpString) a.get("name")).text()).isEqualTo("Blog");
    }

    @Test
    void repairsStringKeyToo() {
        PhpArray a = (PhpArray) PhpSerialize.parse("a:1:{s:2:\"name\";i:1;}", REPAIR);
        assertThat(a.get("name")).isEqualTo(new PhpInt(1));
    }

    @Test
    void repairPrefersDeclaredLengthWhenItIsConsistent() {
        assertThat(PhpSerialize.parse("s:4:\"a\";b\";", REPAIR)).isEqualTo(PhpString.of("a\";b"));
    }

    @Test
    void repairedStringMayContainQuoteSemicolonFollowedByNonTag() {
        // '";x' is not a plausible continuation, so the scan keeps going to the real terminator.
        assertThat(PhpSerialize.parse("s:1:\"a\";x\";", REPAIR)).isEqualTo(PhpString.of("a\";x"));
    }

    @Test
    void strictModeStillRejects() {
        assertThatThrownBy(() -> PhpSerialize.parse("s:5:\"hello world\";")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void unrepairableStringThrows() {
        assertThatThrownBy(() -> PhpSerialize.parse("s:5:\"hello world", REPAIR))
                .isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void repairStillHonoursMaxStringBytes() {
        assertThatThrownBy(() -> PhpSerialize.parse("s:1:\"hello world\";", REPAIR.withMaxStringBytes(4)))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("length");
    }
}
