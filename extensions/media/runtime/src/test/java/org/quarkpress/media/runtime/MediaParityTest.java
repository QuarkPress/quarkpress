package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.media.Crop;
import org.quarkpress.testing.gen.Gen;
import org.quarkpress.testing.gen.Gens;
import org.quarkpress.testing.wporacle.GeneratedCases;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wporacle.PhpOracle;
import org.quarkpress.testing.wporacle.PinnedDownload;
import org.quarkpress.testing.wporacle.WordPressRelease;
import org.quarkpress.testing.wporacle.WordPressSources;

/**
 * Compares the media module's pure rules with WordPress's functions (testing/wp-oracle): here the size arithmetic,
 * {@code wp_constrain_dimensions} and {@code image_resize_dimensions}, over the reference's examples and generated
 * cases. WordPress's {@code media.php} is not among the files the oracle pins one by one, so the functions are taken
 * from the pinned (SHA-256 verified) release archive. Active only with {@code -Pwp-parity}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class MediaParityTest {

    static final Path DRIVER = Path.of("src/test/php/media-driver.php");
    static final Path SKIPS = Path.of("src/test/resources/wp-parity-media-skip.txt");
    static final Path FAILURES = Path.of("target/wp-parity-media-failures.json");

    /** Files the driver and the oracle's bootstrap load, as the release archive holds them. */
    static final List<String> WORDPRESS_FILES = List.of(
            "wp-includes/plugin.php",
            "wp-includes/class-wp-hook.php",
            "wp-includes/class-wp-filter-sentinel.php",
            "wp-includes/functions.php",
            "wp-includes/media.php");

    static final int GENERATED = 10_000;

    /** Two comparisons (fit and resize) per generated case; the examples add more. */
    static final int MIN_COMPARISONS = 2 * GENERATED;

    static final List<String> GRAVITY_X = List.of("left", "center", "right");
    static final List<String> GRAVITY_Y = List.of("top", "center", "bottom");

    /** One case: an image, a box and a crop. */
    record Box(int w0, int h0, int width, int height, Crop crop) {}

    /** The reference's examples and the module's unit-test cases. */
    static final List<Box> EXAMPLES = examples();

    static List<Box> examples() {
        List<Box> boxes = new ArrayList<>();
        int[][] fits = {
            {4000, 3000, 300, 300},
            {4000, 3000, 768, 0},
            {3000, 4000, 1024, 1024},
            {3000, 4000, 768, 0},
            {1200, 800, 1024, 1024},
            {597, 600, 300, 300},
            {1600, 400, 1536, 1536},
            {10000, 10, 300, 300},
            {4000, 3000, 0, 0},
            {767, 5000, 768, 0},
            {768, 5000, 768, 0},
            {5000, 767, 0, 768},
            {1000, 100, 300, 300},
            {1024, 700, 1024, 0},
            {1024, 700, 1024, 1024},
            {0, 300, 150, 150},
            {300, 0, 150, 150},
            {300, 300, 0, 0}
        };
        for (int[] f : fits) {
            boxes.add(new Box(f[0], f[1], f[2], f[3], Crop.none()));
        }
        int[][] crops = {
            {4000, 3000, 150, 150}, {600, 300, 400, 400}, {200, 100, 150, 150}, {1000, 667, 300, 200},
            {100, 80, 150, 150}, {150, 150, 150, 150}, {4000, 3000, 300, 0}, {4000, 3000, 0, 300}
        };
        for (int[] c : crops) {
            boxes.add(new Box(c[0], c[1], c[2], c[3], Crop.hard()));
        }
        boxes.add(new Box(4000, 3000, 150, 150, new Crop.At("right", "bottom")));
        boxes.add(new Box(4000, 3000, 150, 150, new Crop.At("left", "top")));
        boxes.add(new Box(4000, 3000, 150, 150, new Crop.At("middle", "top")));
        boxes.add(new Box(3000, 4000, 150, 150, new Crop.At("left", "bottom")));
        boxes.add(new Box(3000, 4000, 150, 150, new Crop.At("left", "up")));
        // the default size table and the big-image threshold
        int[][] originals = {{4000, 3000}, {3000, 4000}, {1200, 800}, {600, 300}, {301, 200}, {1024, 1024}, {120, 90}};
        for (int[] o : originals) {
            boxes.add(new Box(o[0], o[1], 150, 150, Crop.hard()));
            boxes.add(new Box(o[0], o[1], 300, 300, Crop.none()));
            boxes.add(new Box(o[0], o[1], 768, 0, Crop.none()));
            boxes.add(new Box(o[0], o[1], 1024, 1024, Crop.none()));
            boxes.add(new Box(o[0], o[1], 1536, 1536, Crop.none()));
            boxes.add(new Box(o[0], o[1], 2048, 2048, Crop.none()));
        }
        int[][] big = {
            {4000, 3000},
            {6000, 4000},
            {5000, 100},
            {2600, 2600},
            {1000, 3000},
            {2560, 1440},
            {2560, 2560},
            {2561, 2561},
            {2561, 1000}
        };
        for (int[] b : big) {
            boxes.add(new Box(b[0], b[1], 2560, 2560, Crop.none()));
        }
        return List.copyOf(boxes);
    }

    /** Images 1..8000 on each side, boxes 0..3000, crop none, centre or one of the nine gravity pairs. */
    static Gen<Box> boxes() {
        Gen<Integer> side = Gens.ints(1, 8000);
        Gen<Integer> limit = Gens.ints(0, 3000);
        Gen<Crop> pair = random -> new Crop.At(GRAVITY_X.get(random.nextInt(3)), GRAVITY_Y.get(random.nextInt(3)));
        Gen<Crop> crop = Gens.oneOf(Gens.constant(Crop.none()), Gens.constant(Crop.hard()), pair);
        return random -> new Box(
                side.next(random), side.next(random), limit.next(random), limit.next(random), crop.next(random));
    }

    @Test
    void dimensions() throws Exception {
        PhpOracle oracle = PhpOracle.create(wordpress());
        ParityRun run = new ParityRun("media", ParitySkipList.load(SKIPS), FAILURES);

        List<Box> boxes = new ArrayList<>(EXAMPLES);
        boxes.addAll(GeneratedCases.sample(boxes(), "m2a-dims", GENERATED));
        List<PhpOracle.Case> cases = new ArrayList<>();
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            // the fit function is only reached with an image of some size
            if (box.w0() > 0 && box.h0() > 0) {
                cases.add(new PhpOracle.Case("constrain-" + i, op("constrain", box)));
            }
            cases.add(new PhpOracle.Case("resize-" + i, op("resize", box).set("crop", crop(box.crop()))));
        }
        Map<String, JsonNode> out = oracle.run(DRIVER, cases);
        for (int i = 0; i < boxes.size(); i++) {
            Box box = boxes.get(i);
            String id = "dims-" + i;
            if (i >= EXAMPLES.size()) {
                run.generated(id, box.toString());
            }
            if (box.w0() > 0 && box.h0() > 0) {
                Dimensions.Size size = Dimensions.constrain(box.w0(), box.h0(), box.width(), box.height());
                run.compare(
                        id,
                        "wp_constrain_dimensions",
                        out.get("constrain-" + i).get("size").toString(),
                        "[" + size.width() + "," + size.height() + "]");
            }
            String ours = Dimensions.resize(box.w0(), box.h0(), box.width(), box.height(), box.crop())
                    .map(r -> "[0,0," + r.srcX() + "," + r.srcY() + "," + r.width() + "," + r.height() + ","
                            + r.srcWidth() + "," + r.srcHeight() + "]")
                    .orElse("false");
            run.compare(
                    id,
                    "image_resize_dimensions",
                    out.get("resize-" + i).get("region").toString(),
                    ours);
        }

        System.out.println("media parity: " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_COMPARISONS);
        assertThat(run.comparisons()).isGreaterThanOrEqualTo(MIN_COMPARISONS);
    }

    private static ObjectNode op(String op, Box box) {
        return OracleJson.MAPPER
                .createObjectNode()
                .put("op", op)
                .put("w0", box.w0())
                .put("h0", box.h0())
                .put("width", box.width())
                .put("height", box.height());
    }

    private static JsonNode crop(Crop crop) {
        return switch (crop) {
            case Crop.None none -> OracleJson.MAPPER.getNodeFactory().booleanNode(false);
            case Crop.Hard hard -> OracleJson.MAPPER.getNodeFactory().booleanNode(true);
            case Crop.At at -> OracleJson.MAPPER.createArrayNode().add(at.x()).add(at.y());
        };
    }

    /**
     * A WordPress directory with {@link #WORDPRESS_FILES}, unpacked from the verified release archive into {@code
     * target/wp-media-oracle/<version>/}.
     */
    static Path wordpress() throws IOException, InterruptedException {
        String version = WordPressRelease.fromSystemProperty().version();
        Path archive = WordPressSources.ensure(PinnedDownload.load("wordpress-" + version + "-release"));
        Path dir = Path.of("target", "wp-media-oracle", version).toAbsolutePath();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            for (String file : WORDPRESS_FILES) {
                ZipEntry entry = zip.getEntry("wordpress/" + file);
                if (entry == null) {
                    throw new IllegalStateException("the release archive has no " + file);
                }
                Path target = dir.resolve(file);
                Files.createDirectories(target.getParent());
                try (InputStream in = zip.getInputStream(entry)) {
                    Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
        return dir;
    }
}
