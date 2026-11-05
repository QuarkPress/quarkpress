package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.quarkpress.media.Crop;
import org.quarkpress.media.ImageSizeDefinition;
import org.quarkpress.media.runtime.Dimensions.Region;
import org.quarkpress.media.runtime.Dimensions.Size;

/** WordPress's fit and crop arithmetic, with the values WordPress 7.1.3 gave for each example. */
class DimensionsTest {

    @Test
    void fitExamples() {
        assertThat(Dimensions.constrain(4000, 3000, 300, 300)).isEqualTo(new Size(300, 225));
        assertThat(Dimensions.constrain(4000, 3000, 768, 0)).isEqualTo(new Size(768, 576));
        assertThat(Dimensions.constrain(3000, 4000, 1024, 1024)).isEqualTo(new Size(768, 1024));
        assertThat(Dimensions.constrain(1200, 800, 1024, 1024)).isEqualTo(new Size(1024, 683));
        assertThat(Dimensions.constrain(597, 600, 300, 300)).isEqualTo(new Size(300, 300));
        assertThat(Dimensions.constrain(1600, 400, 1536, 1536)).isEqualTo(new Size(1536, 384));
        assertThat(Dimensions.constrain(10000, 10, 300, 300)).isEqualTo(new Size(300, 1));
    }

    @Test
    void fitWithoutABoxKeepsTheSize() {
        assertThat(Dimensions.constrain(4000, 3000, 0, 0)).isEqualTo(new Size(4000, 3000));
        assertThat(Dimensions.constrain(3000, 4000, 768, 0)).isEqualTo(new Size(768, 1024));
    }

    @Test
    void roundingIsPhp84s() {
        assertThat(Dimensions.round(2.5)).isEqualTo(3);
        assertThat(Dimensions.round(-2.5)).isEqualTo(-3);
        assertThat(Dimensions.round(0.49999999999999994)).isEqualTo(0);
        assertThat(Dimensions.round(1.4999999999999998)).isEqualTo(1);
        assertThat(Dimensions.round(1.775, 2)).isEqualTo(1.78);
        assertThat(Dimensions.round(2.675, 2)).isEqualTo(2.68);
        assertThat(Dimensions.round(1.005, 2)).isEqualTo(1.01);
        assertThat(Dimensions.round(0.285, 2)).isEqualTo(0.29);
        assertThat(Dimensions.round(1.115, 2)).isEqualTo(1.12);
        assertThat(Dimensions.round(5.015, 2)).isEqualTo(5.02);
    }

    @Test
    void cropExamples() {
        assertThat(Dimensions.resize(4000, 3000, 150, 150, Crop.hard()))
                .contains(new Region(500, 0, 3000, 3000, 150, 150));
        assertThat(Dimensions.resize(4000, 3000, 150, 150, new Crop.At("right", "bottom")))
                .contains(new Region(1000, 0, 3000, 3000, 150, 150));
        assertThat(Dimensions.resize(4000, 3000, 150, 150, new Crop.At("left", "top")))
                .contains(new Region(0, 0, 3000, 3000, 150, 150));
        assertThat(Dimensions.resize(600, 300, 400, 400, Crop.hard())).contains(new Region(100, 0, 400, 300, 400, 300));
        assertThat(Dimensions.resize(200, 100, 150, 150, Crop.hard())).contains(new Region(25, 0, 150, 100, 150, 100));
        assertThat(Dimensions.resize(1000, 667, 300, 200, Crop.hard())).contains(new Region(0, 0, 1000, 667, 300, 200));
        assertThat(Dimensions.resize(100, 80, 150, 150, Crop.hard())).isEmpty();
        assertThat(Dimensions.resize(150, 150, 150, 150, Crop.hard())).isEmpty();
        assertThat(Dimensions.resize(1024, 700, 1024, 0, Crop.none())).isEmpty();
        assertThat(Dimensions.resize(1024, 700, 1024, 1024, Crop.none())).isEmpty();
    }

    @Test
    void theCentreIsFlooredAndTheHeightIsBumped() {
        // (201 - 150) / 2 = 25.5, floored
        assertThat(Dimensions.resize(201, 100, 150, 150, Crop.hard())).contains(new Region(25, 0, 150, 100, 150, 100));
        // height round(298.5) = 299, one pixel short of the limit it was scaled to
        assertThat(Dimensions.constrain(600, 597, 300, 300)).isEqualTo(new Size(300, 300));
    }

    @Test
    void cropWithAnUnboundedSide() {
        assertThat(Dimensions.resize(4000, 3000, 300, 0, Crop.hard())).contains(new Region(0, 0, 4000, 3000, 300, 225));
        assertThat(Dimensions.resize(4000, 3000, 0, 300, Crop.hard())).contains(new Region(0, 0, 4000, 3000, 400, 300));
    }

    @Test
    void unknownGravityMeansCentre() {
        assertThat(Dimensions.resize(4000, 3000, 150, 150, new Crop.At("middle", "top")))
                .contains(new Region(500, 0, 3000, 3000, 150, 150));
        assertThat(Dimensions.resize(3000, 4000, 150, 150, new Crop.At("left", "bottom")))
                .contains(new Region(0, 1000, 3000, 3000, 150, 150));
        assertThat(Dimensions.resize(3000, 4000, 150, 150, new Crop.At("left", "up")))
                .contains(new Region(0, 500, 3000, 3000, 150, 150));
    }

    @Test
    void theGuards() {
        assertThat(Dimensions.resize(0, 300, 150, 150, Crop.none())).isEmpty();
        assertThat(Dimensions.resize(300, 0, 150, 150, Crop.none())).isEmpty();
        assertThat(Dimensions.resize(300, 300, 0, 0, Crop.none())).isEmpty();
        // a width-only box needs the full width; equal is not smaller
        assertThat(Dimensions.resize(767, 5000, 768, 0, Crop.none())).isEmpty();
        assertThat(Dimensions.resize(768, 5000, 768, 0, Crop.none())).isEmpty(); // identical
        assertThat(Dimensions.resize(5000, 767, 0, 768, Crop.none())).isEmpty();
        // smaller in one dimension only: still made
        assertThat(Dimensions.resize(1000, 100, 300, 300, Crop.none())).contains(new Region(0, 0, 1000, 100, 300, 30));
    }

    @ParameterizedTest
    @CsvSource({
        "4000,3000,150x150,300x225,768x576,1024x768,1536x1152,2048x1536",
        "3000,4000,150x150,225x300,768x1024,768x1024,1152x1536,1536x2048",
        "1200,800,150x150,300x200,768x512,1024x683,-,-",
        "600,300,150x150,300x150,-,-,-,-",
        "301,200,150x150,-,-,-,-,-",
        "1024,1024,150x150,300x300,768x768,-,-,-",
        "120,90,-,-,-,-,-,-"
    })
    void theDefaultSizeTable(
            int w, int h, String thumb, String medium, String mediumLarge, String large, String s1536, String s2048) {
        Map<String, ImageSizeDefinition> sizes = BuiltInImageSizes.resolve(
                BuiltInImageSizes.registrations(java.util.List.of()), name -> Optional.empty());
        assertThat(cell(w, h, sizes.get("thumbnail"))).isEqualTo(thumb);
        assertThat(cell(w, h, sizes.get("medium"))).isEqualTo(medium);
        assertThat(cell(w, h, sizes.get("medium_large"))).isEqualTo(mediumLarge);
        assertThat(cell(w, h, sizes.get("large"))).isEqualTo(large);
        assertThat(cell(w, h, sizes.get("1536x1536"))).isEqualTo(s1536);
        assertThat(cell(w, h, sizes.get("2048x2048"))).isEqualTo(s2048);
    }

    private static String cell(int w, int h, ImageSizeDefinition size) {
        return Dimensions.resize(w, h, size.width(), size.height(), size.crop())
                .map(r -> r.width() + "x" + r.height())
                .orElse("-");
    }
}
