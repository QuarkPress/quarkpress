package org.quarkpress.lib.wxr;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.quarkpress.lib.phpserialize.MaybeUnserializeResult;
import org.quarkpress.lib.phpserialize.PhpArray;
import org.quarkpress.lib.phpserialize.PhpBool;
import org.quarkpress.lib.phpserialize.PhpInt;
import org.quarkpress.lib.phpserialize.PhpObject;
import org.quarkpress.lib.phpserialize.PhpString;

/**
 * Expected values come from the importer's own maybe_unserialize method, called on PHP 8.4 through a subclass of
 * WP_Import with core 7.1.3 is_serialized/maybe_unserialize, 2026-10-17.
 */
class WxrMetaTest {

    @Test
    void serializedArrayParses() {
        assertThat(new WxrMeta("colors", "a:1:{i:0;s:3:\"red\";}").parsed()).contains(PhpArray.of(PhpString.of("red")));
    }

    @Test
    void objectHeadersBecomeStdClass() {
        PhpObject expected = new PhpObject(
                "stdClass", PhpArray.builder().put("a", new PhpInt(1)).build());
        assertThat(new WxrMeta("o", "O:3:\"Foo\":1:{s:1:\"a\";i:1;}").parsed()).contains(expected);
    }

    @Test
    void theRewriteAlsoHitsHeadersInsideStrings() {
        // Unrewritten this parses (s:13 is right); the importer's rewrite makes the string 18 bytes, so it fails.
        WxrMeta meta = new WxrMeta("k", "a:1:{i:0;s:13:\"O:3:\"Foo\":0:{\";}");
        assertThat(meta.unserialized()).isInstanceOf(MaybeUnserializeResult.Failed.class);
        assertThat(meta.parsed()).isEmpty();
    }

    @Test
    void wrongLengthFailsAndPlainTextIsNotSerialized() {
        assertThat(new WxrMeta("k", "s:5:\"abc\";").unserialized()).isInstanceOf(MaybeUnserializeResult.Failed.class);
        MaybeUnserializeResult plain = new WxrMeta("k", "x").unserialized();
        assertThat(plain).isEqualTo(new MaybeUnserializeResult.NotSerialized(PhpString.of("x")));
    }

    @Test
    void surroundingWhitespaceIsAllowedAndTheRawValueIsKept() {
        WxrMeta meta = new WxrMeta("k", " b:1; ");
        assertThat(meta.parsed()).contains(PhpBool.of(true));
        assertThat(meta.value()).isEqualTo(" b:1; ");
    }

    @Test
    void aMissingValueReadsAsEmptyText() {
        assertThat(new WxrMeta("k", null).unserialized())
                .isEqualTo(new MaybeUnserializeResult.NotSerialized(PhpString.of("")));
    }

    @Test
    void headerRewriteMatchesTheImporterPattern() {
        assertThat(ObjectHeaders.toStdClass("O:3:\"Foo\":0:{}")).isEqualTo("O:8:\"stdClass\":0:{}");
        assertThat(ObjectHeaders.toStdClass("x O:12:\"A\\B\":1:{} O:1:\"C\":0:{}"))
                .isEqualTo("x O:8:\"stdClass\":1:{} O:8:\"stdClass\":0:{}");
        assertThat(ObjectHeaders.toStdClass("O::\"Foo\":")).isEqualTo("O::\"Foo\":");
        assertThat(ObjectHeaders.toStdClass("O:3:\"\":")).isEqualTo("O:3:\"\":");
        assertThat(ObjectHeaders.toStdClass("O:3:\"Foo\"")).isEqualTo("O:3:\"Foo\"");
        assertThat(ObjectHeaders.toStdClass("no headers")).isEqualTo("no headers");
    }
}
