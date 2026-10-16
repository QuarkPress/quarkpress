package org.quarkpress.lib.phpserialize;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class PhpParserCompoundTest {

    @Test
    void parsesListArray() {
        assertThat(PhpSerialize.parse("a:2:{i:0;s:1:\"a\";i:1;s:1:\"b\";}"))
                .isEqualTo(PhpArray.of(PhpString.of("a"), PhpString.of("b")));
    }

    @Test
    void parsesEmptyArray() {
        assertThat(PhpSerialize.parse("a:0:{}")).isEqualTo(PhpArray.empty());
    }

    @Test
    void parsesAssociativeNestedArray() {
        PhpArray v =
                (PhpArray) PhpSerialize.parse("a:1:{s:9:\"sidebar-1\";a:2:{i:0;s:7:\"block-2\";i:1;s:7:\"block-3\";}}");
        assertThat(((PhpArray) v.get("sidebar-1")).values())
                .containsExactly(PhpString.of("block-2"), PhpString.of("block-3"));
    }

    @Test
    void arrayCountMismatchThrows() {
        assertThatThrownBy(() -> PhpSerialize.parse("a:2:{i:0;i:1;}")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("a:1:{i:0;i:1;i:1;i:2;}"))
                .isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("a:-1:{}")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void arrayKeyMustBeIntOrString() {
        assertThatThrownBy(() -> PhpSerialize.parse("a:1:{b:1;i:1;}")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("a:1:{d:1;i:1;}")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void parsesObjectWithVisibilityMangledNames() {
        PhpObject o = (PhpObject) PhpSerialize.parse("O:3:\"Foo\":2:{s:3:\"pub\";i:1;s:8:\"\0Foo\0prv\";i:2;}");
        assertThat(o.className()).isEqualTo("Foo");
        assertThat(o.properties().get("pub")).isEqualTo(new PhpInt(1));
        assertThat(o.properties().get("\0Foo\0prv")).isEqualTo(new PhpInt(2));
    }

    @Test
    void parsesEmptyStdClass() {
        assertThat(PhpSerialize.parse("O:8:\"stdClass\":0:{}")).isEqualTo(new PhpObject("stdClass", PhpArray.empty()));
    }

    @Test
    void parsesCustomObject() {
        PhpCustomObject c = (PhpCustomObject) PhpSerialize.parse("C:3:\"Foo\":5:{hello}");
        assertThat(c.className()).isEqualTo("Foo");
        assertThat(new String(c.payload(), UTF_8)).isEqualTo("hello");
        assertThat(PhpSerialize.parse("C:3:\"Foo\":0:{}")).isEqualTo(new PhpCustomObject("Foo", new byte[0]));
    }

    @Test
    void customObjectPayloadMayContainBraces() {
        PhpCustomObject c = (PhpCustomObject) PhpSerialize.parse("C:3:\"Foo\":6:{a:0:{}}");
        assertThat(new String(c.payload(), UTF_8)).isEqualTo("a:0:{}");
    }

    @Test
    void parsesReferences() {
        PhpArray a = (PhpArray) PhpSerialize.parse("a:2:{i:0;O:8:\"stdClass\":0:{}i:1;r:2;}");
        assertThat(a.get(1)).isEqualTo(new PhpReference(2, PhpReference.Kind.OBJECT));
        assertThat(PhpSerialize.parse("a:2:{i:0;i:5;i:1;R:2;}"))
                .isEqualTo(PhpArray.of(new PhpInt(5), new PhpReference(2, PhpReference.Kind.REFERENCE)));
    }

    @Test
    void parsesEnum() {
        assertThat(PhpSerialize.parse("E:11:\"Suit:Hearts\";")).isEqualTo(new PhpEnum("Suit", "Hearts"));
        assertThat(PhpSerialize.parse("E:15:\"App\\Suit:Hearts\";")).isEqualTo(new PhpEnum("App\\Suit", "Hearts"));
    }

    @Test
    void enumWithoutColonThrows() {
        assertThatThrownBy(() -> PhpSerialize.parse("E:4:\"Suit\";")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void nestingBeyondMaxDepthThrows() {
        String deep = "a:1:{i:0;".repeat(600) + "i:1;" + "}".repeat(600);
        assertThatThrownBy(() -> PhpSerialize.parse(deep))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("depth");
        String ok = "a:1:{i:0;".repeat(250) + "i:1;" + "}".repeat(250);
        assertThat(PhpSerialize.parse(ok)).isInstanceOf(PhpArray.class);
    }

    @Test
    void declaredCountBeyondLimitFailsBeforeAllocation() {
        assertThatThrownBy(() -> PhpSerialize.parse("a:2147483647:{"))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("element");
        assertThatThrownBy(() -> PhpSerialize.parse("O:3:\"Foo\":99999999999:{"))
                .isInstanceOf(PhpSerializeException.class)
                .hasMessageContaining("element");
    }

    @Test
    void elementLimitCountsAcrossNesting() {
        String many = "a:3:{i:0;a:2:{i:0;i:1;i:1;i:2;}i:1;i:1;i:2;i:2;}";
        assertThatThrownBy(() -> PhpSerialize.parse(many, ParseOptions.DEFAULTS.withMaxElements(4)))
                .isInstanceOf(PhpSerializeException.class);
        assertThat(PhpSerialize.parse(many, ParseOptions.DEFAULTS.withMaxElements(5)))
                .isInstanceOf(PhpArray.class);
    }

    @Test
    void truncatedInputThrowsWithOffset() {
        assertThatThrownBy(() -> PhpSerialize.parse("a:2:{i:0;s:1:\"a\";"))
                .isInstanceOfSatisfying(
                        PhpSerializeException.class, e -> assertThat(e.offset()).isEqualTo(17));
        assertThatThrownBy(() -> PhpSerialize.parse("O:3:\"Foo\":1:{")).isInstanceOf(PhpSerializeException.class);
        assertThatThrownBy(() -> PhpSerialize.parse("C:3:\"Foo\":5:{hel")).isInstanceOf(PhpSerializeException.class);
    }

    @Test
    void maxDepthIsCapped() {
        assertThatThrownBy(() -> ParseOptions.DEFAULTS.withMaxDepth(100_000))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ParseOptions.DEFAULTS
                        .withMaxDepth(ParseOptions.MAX_DEPTH_LIMIT)
                        .maxDepth())
                .isEqualTo(ParseOptions.MAX_DEPTH_LIMIT);
    }

    @Test
    void deepNestingOnSmallStackStillThrowsPhpSerializeException() throws Exception {
        int depth = ParseOptions.MAX_DEPTH_LIMIT;
        String deep = "a:1:{i:0;".repeat(depth) + "i:1;" + "}".repeat(depth);
        ParseOptions opts = ParseOptions.DEFAULTS.withMaxDepth(depth);
        Throwable[] caught = new Throwable[1];
        Thread t = new Thread(
                null,
                () -> {
                    try {
                        PhpSerialize.parse(deep, opts);
                    } catch (Throwable e) {
                        caught[0] = e;
                    }
                },
                "tiny-stack",
                128 * 1024);
        t.start();
        t.join();
        // Either the parse succeeds (big enough stack) or it fails with our exception; nothing else may escape.
        if (caught[0] != null) {
            assertThat(caught[0]).isInstanceOf(PhpSerializeException.class);
        }
    }

    @Test
    void integerParsingDoesNotUseRegex() {
        // 20 000 small ints must parse quickly; a per-integer Pattern.compile made this visibly slow.
        StringBuilder sb = new StringBuilder("a:20000:{");
        for (int i = 0; i < 20000; i++) {
            sb.append("i:").append(i).append(";i:").append(i).append(';');
        }
        sb.append('}');
        long start = System.nanoTime();
        assertThat(((PhpArray) PhpSerialize.parse(sb.toString())).size()).isEqualTo(20000);
        assertThat(System.nanoTime() - start).isLessThan(2_000_000_000L);
    }

    private static String rt(String in) {
        return PhpSerialize.serializeToString(PhpSerialize.parse(in));
    }

    @Test
    void integerTextKeysParseAsIntegerKeysLikePhp() { // #10, #12, #13
        assertThat(rt("a:1:{s:1:\"5\";i:1;}")).isEqualTo("a:1:{i:5;i:1;}");
        assertThat(rt("a:1:{s:2:\"-5\";i:1;}")).isEqualTo("a:1:{i:-5;i:1;}");
        assertThat(rt("a:1:{s:2:\"05\";i:1;}")).isEqualTo("a:1:{s:2:\"05\";i:1;}");
        assertThat(rt("a:1:{s:2:\"-0\";i:1;}")).isEqualTo("a:1:{s:2:\"-0\";i:1;}");
        assertThat(rt("a:1:{s:19:\"9223372036854775808\";i:1;}")).isEqualTo("a:1:{s:19:\"9223372036854775808\";i:1;}");
        assertThat(rt("a:1:{s:20:\"-9223372036854775808\";i:1;}")).isEqualTo("a:1:{i:-9223372036854775808;i:1;}");
        assertThat(rt("a:2:{s:1:\"5\";i:1;i:5;i:2;}")).isEqualTo("a:1:{i:5;i:2;}");
        assertThat(rt("a:2:{i:0;i:1;i:0;i:2;}")).isEqualTo("a:1:{i:0;i:2;}");
    }

    @Test
    void objectPropertyNamesAreWrittenAsStringsLikePhp() {
        assertThat(rt("O:8:\"stdClass\":1:{i:5;i:1;}")).isEqualTo("O:8:\"stdClass\":1:{s:1:\"5\";i:1;}");
        assertThat(rt("O:8:\"stdClass\":1:{s:1:\"5\";i:1;}")).isEqualTo("O:8:\"stdClass\":1:{s:1:\"5\";i:1;}");
        assertThat(rt("O:3:\"Foo\":2:{s:1:\"5\";i:1;i:5;i:2;}")).isEqualTo("O:3:\"Foo\":1:{s:1:\"5\";i:2;}");
    }
}
