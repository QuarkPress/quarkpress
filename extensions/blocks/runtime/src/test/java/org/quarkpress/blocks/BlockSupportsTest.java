package org.quarkpress.blocks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The attributes a block type answers with: its declared ones, {@code lock} and {@code metadata}, then those its
 * supports add, as WordPress 7.1.3 answered them for the recorded types.
 */
class BlockSupportsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode object(String text) {
        try {
            return (ObjectNode) JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static String attributes(BlockType type) {
        return BlockSupports.attributes(type).toString();
    }

    @Test
    void aTitleOnlyTypeGetsLockMetadataClassNameAndStyle() {
        BlockType notice = BlockType.builder("acme/notice").title("Notice").build();

        assertThat(attributes(notice))
                .isEqualTo("{\"lock\":{\"type\":\"object\"},\"metadata\":{\"type\":\"object\"},"
                        + "\"className\":{\"type\":\"string\"},\"style\":{\"type\":\"object\"}}");
    }

    @Test
    void everySupportAddsWordPresssAttributes() {
        // the harness's acme/supports: border, spacing, dimensions, shadow, background, position and filter.duotone
        // add no attribute of their own
        BlockType supports = BlockType.builder("acme/supports")
                .title("Supports")
                .supports(object("""
                        {"align":true,"anchor":true,"ariaLabel":true,
                         "color":{"background":true,"text":true,"gradients":true,"link":true},
                         "typography":{"fontSize":true,"lineHeight":true},
                         "border":{"color":true,"radius":true,"style":true,"width":true},
                         "spacing":{"margin":true,"padding":true},"dimensions":{"minHeight":true},"shadow":true,
                         "background":{"backgroundImage":true},"layout":true,"position":{"sticky":true},
                         "filter":{"duotone":true}}
                        """))
                .build();

        assertThat(attributes(supports))
                .isEqualTo("{\"lock\":{\"type\":\"object\"},\"metadata\":{\"type\":\"object\"},"
                        + "\"align\":{\"type\":\"string\",\"enum\":[\"left\",\"center\",\"right\",\"wide\",\"full\",\"\"]},"
                        + "\"className\":{\"type\":\"string\"},\"style\":{\"type\":\"object\"},"
                        + "\"backgroundColor\":{\"type\":\"string\"},\"textColor\":{\"type\":\"string\"},"
                        + "\"gradient\":{\"type\":\"string\"},\"fontSize\":{\"type\":\"string\"},"
                        + "\"layout\":{\"type\":\"object\"},\"ariaLabel\":{\"type\":\"string\"},"
                        + "\"anchor\":{\"type\":\"string\"}}");
    }

    @Test
    void experimentalBorderAndFontFamily() {
        // core/paragraph's supports as WordPress answered them: colour without background and text keys (both on),
        // an align list, the experimental font family and border colour; its "className": false is not the
        // customClassName support, so className stays
        BlockType paragraph =
                BlockType.builder("core/paragraph").supports(object("""
                        {"align":["wide","full"],"splitting":true,"anchor":true,"className":false,
                         "__experimentalBorder":{"color":true,"radius":true,"style":true,"width":true},
                         "color":{"gradients":true,"link":true,
                                  "__experimentalDefaultControls":{"background":true,"text":true}},
                         "spacing":{"margin":true,"padding":true,
                                    "__experimentalDefaultControls":{"margin":false,"padding":false}},
                         "typography":{"fontSize":true,"lineHeight":true,"textAlign":true,"textColumns":true,
                                       "textIndent":true,"__experimentalFontFamily":true,
                                       "__experimentalTextDecoration":true,"__experimentalFontStyle":true,
                                       "__experimentalFontWeight":true,"__experimentalLetterSpacing":true,
                                       "__experimentalTextTransform":true,"__experimentalWritingMode":true,
                                       "fitText":true,"__experimentalDefaultControls":{"fontSize":true}},
                         "__experimentalSelector":"p","__unstablePasteTextInline":true,
                         "interactivity":{"clientNavigation":true}}
                        """)).build();

        assertThat(BlockSupports.attributes(paragraph).properties())
                .extracting(java.util.Map.Entry::getKey)
                .containsExactly(
                        "lock",
                        "metadata",
                        "align",
                        "className",
                        "style",
                        "backgroundColor",
                        "textColor",
                        "gradient",
                        "fontSize",
                        "fontFamily",
                        "borderColor",
                        "anchor");
    }

    @Test
    void declaredAttributesComeFirstAsDeclared() {
        // core/archives as WordPress answered it, declared attributes and supports
        BlockType archives = BlockType.builder("core/archives")
                .attributes(object("""
                        {"displayAsDropdown":{"type":"boolean","default":false},
                         "showLabel":{"type":"boolean","default":true},
                         "showPostCounts":{"type":"boolean","default":false},
                         "type":{"type":"string","default":"monthly"}}
                        """))
                .supports(object("""
                        {"anchor":true,"align":true,
                         "__experimentalBorder":{"radius":true,"color":true,"width":true,"style":true},"html":false,
                         "spacing":{"margin":true,"padding":true},
                         "typography":{"fontSize":true,"lineHeight":true,"__experimentalFontFamily":true},
                         "color":{"gradients":true,"link":true}}
                        """))
                .build();

        assertThat(attributes(archives))
                .isEqualTo("{\"displayAsDropdown\":{\"type\":\"boolean\",\"default\":false},"
                        + "\"showLabel\":{\"type\":\"boolean\",\"default\":true},"
                        + "\"showPostCounts\":{\"type\":\"boolean\",\"default\":false},"
                        + "\"type\":{\"type\":\"string\",\"default\":\"monthly\"},"
                        + "\"lock\":{\"type\":\"object\"},\"metadata\":{\"type\":\"object\"},"
                        + "\"align\":{\"type\":\"string\",\"enum\":[\"left\",\"center\",\"right\",\"wide\",\"full\",\"\"]},"
                        + "\"className\":{\"type\":\"string\"},\"style\":{\"type\":\"object\"},"
                        + "\"backgroundColor\":{\"type\":\"string\"},\"textColor\":{\"type\":\"string\"},"
                        + "\"gradient\":{\"type\":\"string\"},\"fontSize\":{\"type\":\"string\"},"
                        + "\"fontFamily\":{\"type\":\"string\"},\"borderColor\":{\"type\":\"string\"},"
                        + "\"anchor\":{\"type\":\"string\"}}");
    }

    @Test
    void optingOutDropsClassNameAndStyle() {
        BlockType plain = BlockType.builder("acme/plain")
                .supports(object("{\"customClassName\":false,\"customCSS\":false}"))
                .build();

        assertThat(attributes(plain)).isEqualTo("{\"lock\":{\"type\":\"object\"},\"metadata\":{\"type\":\"object\"}}");
    }

    @Test
    void aDeclaredAttributeKeepsItsPlaceAndShape() {
        BlockType declared = BlockType.builder("acme/declared")
                .attributes(object("{\"style\":{\"type\":\"object\",\"default\":{\"x\":1}},"
                        + "\"lock\":{\"type\":\"object\",\"default\":{\"move\":true}}}"))
                .build();

        assertThat(attributes(declared))
                .isEqualTo("{\"style\":{\"type\":\"object\",\"default\":{\"x\":1}},"
                        + "\"lock\":{\"type\":\"object\",\"default\":{\"move\":true}},"
                        + "\"metadata\":{\"type\":\"object\"},\"className\":{\"type\":\"string\"}}");
    }

    @Test
    void autoRegisteredTypesMarkTheirSimpleAttributes() {
        BlockType auto = BlockType.builder("acme/auto")
                .attributes(object("""
                        {"label":{"type":"string"},"count":{"type":"integer"},"ratio":{"type":"number"},
                         "on":{"type":"boolean"},"items":{"type":"array"},
                         "text":{"type":"string","source":"html","selector":"p"},
                         "local":{"type":"string","role":"local"}}
                        """))
                .supports(object("{\"autoRegister\":true}"))
                .build();

        ObjectNode attributes = BlockSupports.attributes(auto);

        assertThat(attributes.at("/label/autoGenerateControl").asBoolean()).isTrue();
        assertThat(attributes.at("/count/autoGenerateControl").asBoolean()).isTrue();
        assertThat(attributes.at("/ratio/autoGenerateControl").asBoolean()).isTrue();
        assertThat(attributes.at("/on/autoGenerateControl").asBoolean()).isTrue();
        assertThat(attributes.get("items").has("autoGenerateControl")).isFalse();
        assertThat(attributes.get("text").has("autoGenerateControl")).isFalse();
        assertThat(attributes.get("local").has("autoGenerateControl")).isFalse();
        assertThat(attributes.get("className").has("autoGenerateControl")).isFalse();
    }

    @Test
    void theAnswerIsACopy() {
        BlockType notice = BlockType.builder("acme/notice").build();
        ObjectNode first = BlockSupports.attributes(notice);
        first.remove("lock");
        ((ObjectNode) first.get("className")).put("changed", true);
        ((ObjectNode) first.get("style")).put("changed", true);

        ObjectNode second = BlockSupports.attributes(notice);
        assertThat(second.has("lock")).isTrue();
        assertThat(second.get("className").has("changed")).isFalse();
        assertThat(second.get("style").has("changed")).isFalse();
    }

    private static java.util.List<String> keys(String supports) {
        java.util.List<String> keys = new java.util.ArrayList<>();
        BlockSupports.attributes(BlockType.builder("acme/probe")
                        .supports(object(supports))
                        .build())
                .fieldNames()
                .forEachRemaining(keys::add);
        return keys;
    }

    @Test
    void styleFollowsTheFirstSupportThatWantsIt() {
        // nothing before customCSS wants it: style comes last
        assertThat(keys("{\"anchor\":true}")).containsExactly("lock", "metadata", "className", "anchor", "style");
        // core/query's shape: align, layout and anchor want no style
        assertThat(keys("{\"align\":[\"wide\",\"full\"],\"layout\":true,\"anchor\":true}"))
                .containsExactly("lock", "metadata", "align", "className", "layout", "anchor", "style");
        // typography wants it before its own attributes
        assertThat(keys("{\"typography\":{\"lineHeight\":true},\"anchor\":true}"))
                .containsExactly("lock", "metadata", "className", "style", "anchor");
        // spacing wants it after layout
        assertThat(keys("{\"layout\":true,\"spacing\":{\"padding\":true},\"anchor\":true}"))
                .containsExactly("lock", "metadata", "className", "layout", "style", "anchor");
        // colour with only a link colour still wants it, and adds neither background nor text colour
        assertThat(keys("{\"color\":{\"text\":false,\"background\":false,\"link\":true}}"))
                .containsExactly("lock", "metadata", "className", "style");
        // colour turned on whole: background and text colours, no gradient
        assertThat(keys("{\"color\":true,\"anchor\":true}"))
                .containsExactly("lock", "metadata", "className", "style", "backgroundColor", "textColor", "anchor");
    }

    @Test
    void customCssOffKeepsAStyleOtherSupportsWant() {
        assertThat(keys("{\"customCSS\":false,\"spacing\":{\"padding\":true}}"))
                .containsExactly("lock", "metadata", "className", "style");
        assertThat(keys("{\"customCSS\":false,\"anchor\":true}"))
                .containsExactly("lock", "metadata", "className", "anchor");
    }

    @Test
    void experimentalBorderTrueAndExperimentalLayout() {
        assertThat(keys("{\"__experimentalBorder\":true}"))
                .containsExactly("lock", "metadata", "className", "style", "borderColor");
        assertThat(keys("{\"__experimentalLayout\":true}"))
                .containsExactly("lock", "metadata", "className", "layout", "style");
        assertThat(keys("{\"layout\":true,\"__experimentalLayout\":true}"))
                .containsExactly("lock", "metadata", "className", "layout", "style");
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"color\":{\"text\":false,\"background\":false,\"link\":true}",
                "\"color\":{\"text\":false,\"background\":false,\"button\":true}",
                "\"color\":{\"text\":false,\"background\":false,\"heading\":true}",
                "\"color\":{\"text\":false,\"background\":false,\"gradients\":true}",
                "\"color\":{\"background\":false}",
                "\"color\":{\"text\":false}",
                "\"typography\":{\"fontSize\":true}",
                "\"typography\":{\"lineHeight\":true}",
                "\"typography\":{\"textAlign\":true}",
                "\"typography\":{\"textColumns\":true}",
                "\"typography\":{\"textIndent\":true}",
                "\"typography\":{\"__experimentalFontFamily\":true}",
                "\"typography\":{\"__experimentalFontStyle\":true}",
                "\"typography\":{\"__experimentalFontWeight\":true}",
                "\"typography\":{\"__experimentalLetterSpacing\":true}",
                "\"typography\":{\"__experimentalTextDecoration\":true}",
                "\"typography\":{\"__experimentalTextTransform\":true}",
                "\"typography\":{\"__experimentalWritingMode\":true}",
                "\"__experimentalBorder\":{\"radius\":true}",
                "\"position\":{\"sticky\":true}",
                "\"spacing\":{\"margin\":true}",
                "\"dimensions\":{\"minHeight\":true}",
                "\"filter\":{\"duotone\":true}",
                "\"background\":{\"backgroundImage\":true}"
            })
    void everyStyleTriggerPutsStyleBeforeAnchor(String trigger) {
        java.util.List<String> keys = keys("{" + trigger + ",\"anchor\":true}");

        assertThat(keys).contains("style", "anchor");
        assertThat(keys.indexOf("style")).isLessThan(keys.indexOf("anchor"));
    }

    @ParameterizedTest
    @ValueSource(
            strings = {
                "\"typography\":{\"fitText\":true}",
                "\"typography\":true",
                "\"color\":{\"text\":false,\"background\":false}",
                "\"border\":{\"color\":true}",
                "\"shadow\":true",
                "\"filter\":{\"other\":true}"
            })
    void otherSupportsLeaveStyleLast(String support) {
        java.util.List<String> keys = keys("{" + support + ",\"anchor\":true}");

        assertThat(keys.get(keys.size() - 1)).isEqualTo("style");
        assertThat(keys.indexOf("anchor")).isEqualTo(keys.size() - 2);
    }
}
