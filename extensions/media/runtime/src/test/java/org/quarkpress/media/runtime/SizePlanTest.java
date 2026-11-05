package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.media.Crop;
import org.quarkpress.media.DecodedImage;
import org.quarkpress.media.ImageInfo;
import org.quarkpress.media.ImageProcessor;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.ImageSizeProvider;
import org.quarkpress.media.runtime.Dimensions.Size;

/** The sizes a site has, their order, the file names of the copies, and the big-image threshold. */
class SizePlanTest {

    @Test
    void generationOrderAndNames() {
        assertThat(SizePlan.generationOrder(
                        List.of("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048", "x")))
                .containsExactly("medium", "large", "thumbnail", "medium_large", "1536x1536", "2048x2048", "x");
        assertThat(SizePlan.generationOrder(List.of("x", "thumbnail", "large", "y")))
                .containsExactly("large", "thumbnail", "x", "y");
        assertThat(SizePlan.subSizeName("IMG_1234.jpg", 300, 225, "image/jpeg", "image/jpeg"))
                .isEqualTo("IMG_1234-300x225.jpg");
        assertThat(SizePlan.subSizeName("photo.JPEG", 300, 225, "image/jpeg", "image/jpeg"))
                .isEqualTo("photo-300x225.jpeg");
        assertThat(SizePlan.subSizeName("IMG_5.heic", 300, 225, "image/heic", "image/jpeg"))
                .isEqualTo("IMG_5-300x225.jpg");
        assertThat(SizePlan.subSizeName("a.b.PNG", 10, 20, "image/png", "image/webp"))
                .isEqualTo("a.b-10x20.webp");
        assertThat(SizePlan.scaledName("IMG_1234.JPG", "image/jpeg", "image/jpeg"))
                .isEqualTo("IMG_1234-scaled.jpg");
        assertThat(SizePlan.scaledName("IMG_5.heic", "image/heic", "image/jpeg"))
                .isEqualTo("IMG_5-scaled.jpg");
        assertThat(SizePlan.rotatedName("photo.jpg", "image/jpeg", "image/jpeg"))
                .isEqualTo("photo-rotated.jpg");
        assertThat(SizePlan.convertedName("IMG_5.heic", "image/jpeg")).isEqualTo("IMG_5.jpg");
    }

    @Test
    void sizesOfTheSameDimensionsShareTheirName() {
        // portrait 3:4: medium_large and large are both 768x1024, so both name the same file
        String mediumLarge = Dimensions.resize(3000, 4000, 768, 0, Crop.none())
                .map(r -> SizePlan.subSizeName("p.jpg", r.width(), r.height(), "image/jpeg", "image/jpeg"))
                .orElseThrow();
        String large = Dimensions.resize(3000, 4000, 1024, 1024, Crop.none())
                .map(r -> SizePlan.subSizeName("p.jpg", r.width(), r.height(), "image/jpeg", "image/jpeg"))
                .orElseThrow();
        assertThat(mediumLarge).isEqualTo(large).isEqualTo("p-768x1024.jpg");
    }

    @Test
    void theBigImageThreshold() {
        assertThat(SizePlan.scaled(4000, 3000, 2560)).contains(new Size(2560, 1920));
        assertThat(SizePlan.scaled(6000, 4000, 2560)).contains(new Size(2560, 1707));
        assertThat(SizePlan.scaled(5000, 100, 2560)).contains(new Size(2560, 51));
        assertThat(SizePlan.scaled(2600, 2600, 2560)).contains(new Size(2560, 2560));
        assertThat(SizePlan.scaled(1000, 3000, 2560)).contains(new Size(853, 2560));
        assertThat(SizePlan.scaled(2560, 1440, 2560)).isEmpty();
        assertThat(SizePlan.scaled(2560, 2560, 2560)).isEmpty();
        assertThat(SizePlan.scaled(2561, 2561, 2560)).isEmpty();
        assertThat(SizePlan.scaled(2561, 1000, 2560)).isEmpty();
        assertThat(SizePlan.scaled(4000, 3000, 0)).isEmpty();
    }

    @Test
    void theOutputType() {
        ImageProcessor jpegOnly = processor(Set.of("image/jpeg", "image/png"));
        Map<String, String> formats = Map.of("image/heic", "image/jpeg", "image/png", "image/webp");
        assertThat(SizePlan.outputType("image/heic", formats, jpegOnly)).isEqualTo("image/jpeg");
        assertThat(SizePlan.outputType("image/png", formats, jpegOnly)).isEqualTo("image/png");
        assertThat(SizePlan.outputType("image/jpeg", formats, jpegOnly)).isEqualTo("image/jpeg");
        assertThat(SizePlan.outputType("image/heic", formats, processor(Set.of())))
                .isEqualTo("image/heic");
    }

    @Test
    void cropFromAnOption() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        assertThat(Crop.fromOption(TextNode.valueOf("1"))).isEqualTo(Crop.hard());
        assertThat(Crop.fromOption(IntNode.valueOf(1))).isEqualTo(Crop.hard());
        assertThat(Crop.fromOption(BooleanNode.TRUE)).isEqualTo(Crop.hard());
        assertThat(Crop.fromOption(TextNode.valueOf("yes"))).isEqualTo(Crop.hard());
        assertThat(Crop.fromOption(TextNode.valueOf("0"))).isEqualTo(Crop.none());
        assertThat(Crop.fromOption(TextNode.valueOf(""))).isEqualTo(Crop.none());
        assertThat(Crop.fromOption(IntNode.valueOf(0))).isEqualTo(Crop.none());
        assertThat(Crop.fromOption(BooleanNode.FALSE)).isEqualTo(Crop.none());
        assertThat(Crop.fromOption(json.nullNode())).isEqualTo(Crop.none());
        assertThat(Crop.fromOption(null)).isEqualTo(Crop.none());
        assertThat(Crop.fromOption(json.arrayNode())).isEqualTo(Crop.none());
        assertThat(Crop.fromOption(json.arrayNode().add("left").add("top"))).isEqualTo(new Crop.At("left", "top"));
    }

    @Test
    void optionNumbersReadAsPhpCastsThemToInt() {
        JsonNodeFactory json = JsonNodeFactory.instance;
        assertThat(BuiltInImageSizes.phpInt(TextNode.valueOf("12px"))).isEqualTo(12);
        assertThat(BuiltInImageSizes.phpInt(TextNode.valueOf(" 7"))).isEqualTo(7);
        assertThat(BuiltInImageSizes.phpInt(TextNode.valueOf("\n8"))).isEqualTo(8);
        assertThat(BuiltInImageSizes.phpInt(TextNode.valueOf("1e3"))).isEqualTo(1000);
        assertThat(BuiltInImageSizes.phpInt(TextNode.valueOf("12.9"))).isEqualTo(12);
        assertThat(BuiltInImageSizes.phpInt(TextNode.valueOf("-5"))).isEqualTo(-5);
        assertThat(BuiltInImageSizes.phpInt(TextNode.valueOf("abc"))).isEqualTo(0);
        assertThat(BuiltInImageSizes.phpInt(TextNode.valueOf(""))).isEqualTo(0);
        assertThat(BuiltInImageSizes.phpInt(json.numberNode(300.7))).isEqualTo(300);
        assertThat(BuiltInImageSizes.phpInt(BooleanNode.TRUE)).isEqualTo(1);
        assertThat(BuiltInImageSizes.phpInt(BooleanNode.FALSE)).isEqualTo(0);
        assertThat(BuiltInImageSizes.phpInt(json.arrayNode())).isEqualTo(0);
        assertThat(BuiltInImageSizes.phpInt(json.arrayNode().add(1).add(2))).isEqualTo(1);
        assertThat(BuiltInImageSizes.phpInt(json.nullNode())).isEqualTo(0);
    }

    @Test
    void registeredSizesFromOptions() {
        Map<String, ImageSizeDefinition> defaults =
                BuiltInImageSizes.resolve(BuiltInImageSizes.registrations(List.of()), name -> Optional.empty());
        assertThat(defaults.keySet())
                .containsExactly("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048");
        assertThat(defaults.values())
                .containsExactly(
                        new ImageSizeDefinition("thumbnail", 150, 150, Crop.hard()),
                        new ImageSizeDefinition("medium", 300, 300, Crop.none()),
                        new ImageSizeDefinition("medium_large", 768, 0, Crop.none()),
                        new ImageSizeDefinition("large", 1024, 1024, Crop.none()),
                        new ImageSizeDefinition("1536x1536", 1536, 1536, Crop.none()),
                        new ImageSizeDefinition("2048x2048", 2048, 2048, Crop.none()));

        Map<String, JsonNode> options = new HashMap<>();
        options.put("medium_size_w", IntNode.valueOf(0));
        options.put("medium_size_h", TextNode.valueOf("0"));
        options.put("thumbnail_crop", TextNode.valueOf("0"));
        options.put("large_size_w", TextNode.valueOf("2000"));
        ImageSizeProvider x = () -> List.of(new ImageSizeDefinition("x", 100, 100, new Crop.At("left", "top")));
        ImageSizeProvider y = () -> List.of(new ImageSizeDefinition("y", -50, 80, Crop.none()));
        Map<String, ImageSizeDefinition> sizes = BuiltInImageSizes.resolve(
                BuiltInImageSizes.registrations(List.of(x, y)), name -> Optional.ofNullable(options.get(name)));
        assertThat(sizes.keySet())
                .containsExactly("thumbnail", "medium_large", "large", "1536x1536", "2048x2048", "x", "y");
        assertThat(sizes.get("thumbnail").crop()).isEqualTo(Crop.none());
        assertThat(sizes.get("large")).isEqualTo(new ImageSizeDefinition("large", 2000, 1024, Crop.none()));
        assertThat(sizes.get("x")).isEqualTo(new ImageSizeDefinition("x", 100, 100, new Crop.At("left", "top")));
        assertThat(sizes.get("y")).isEqualTo(new ImageSizeDefinition("y", 50, 80, Crop.none()));
    }

    @Test
    void aProviderReplacesASizeInItsPlace() {
        ImageSizeProvider first = () -> List.of(
                new ImageSizeDefinition("a", 10, 10, Crop.none()),
                new ImageSizeDefinition("thumbnail", 99, 99, Crop.none()),
                new ImageSizeDefinition("b", 20, 20, Crop.none()));
        ImageSizeProvider second = () -> List.of(
                new ImageSizeDefinition("a", 11, 0, Crop.hard()),
                new ImageSizeDefinition("1536x1536", 1500, 1500, Crop.none()),
                new ImageSizeDefinition("zero", 0, 0, Crop.none()));
        Map<String, ImageSizeDefinition> sizes = BuiltInImageSizes.resolve(
                BuiltInImageSizes.registrations(List.of(first, second)), name -> Optional.empty());
        assertThat(sizes.keySet())
                .containsExactly("thumbnail", "medium", "medium_large", "large", "1536x1536", "2048x2048", "a", "b");
        assertThat(sizes.get("thumbnail")).isEqualTo(new ImageSizeDefinition("thumbnail", 99, 99, Crop.none()));
        assertThat(sizes.get("a")).isEqualTo(new ImageSizeDefinition("a", 11, 0, Crop.hard()));
        assertThat(sizes.get("1536x1536").width()).isEqualTo(1500);
    }

    private static ImageProcessor processor(Set<String> types) {
        return new ImageProcessor() {
            @Override
            public boolean supports(String mimeType) {
                return types.contains(mimeType);
            }

            @Override
            public Optional<ImageInfo> read(Path file) {
                return Optional.empty();
            }

            @Override
            public DecodedImage decode(Path file, String mimeType) {
                throw new UnsupportedOperationException();
            }
        };
    }
}
