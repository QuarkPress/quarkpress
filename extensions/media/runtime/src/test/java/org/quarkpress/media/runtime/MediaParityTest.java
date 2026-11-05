package org.quarkpress.media.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
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
 * Compares the media module's pure rules with WordPress's functions (testing/wp-oracle): the size arithmetic,
 * {@code wp_constrain_dimensions} and {@code image_resize_dimensions}, and file-name sanitizing, {@code
 * sanitize_file_name}, and the EXIF numbers, {@code wp_exif_frac2dec}, {@code wp_exif_date2ts} and {@code round}, over
 * the reference's examples and generated cases. WordPress's {@code media.php} is not among the files the oracle pins
 * one by one, so the functions are taken
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
            "wp-includes/media.php",
            "wp-includes/formatting.php",
            "wp-includes/compat.php",
            "wp-admin/includes/image.php");

    static final int GENERATED = 10_000;

    /** Two comparisons (fit and resize) per generated case; the examples add more. */
    static final int MIN_COMPARISONS = 2 * GENERATED;

    static final int NAMES_GENERATED = 3000;

    /** About half of what the file-name run compares (6,074 at the time of writing). */
    static final int MIN_NAME_COMPARISONS = 3_000;

    static final Path NAME_FAILURES = Path.of("target/wp-parity-media-names-failures.json");

    static final int FRACTIONS_GENERATED = 2000;

    static final int DATES_GENERATED = 500;

    /** Three comparisons (type, text, rounding) per generated fraction and one per generated date, a few less. */
    static final int MIN_EXIF_COMPARISONS = 3 * FRACTIONS_GENERATED + DATES_GENERATED - 100;

    static final Path EXIF_FAILURES = Path.of("target/wp-parity-media-exif-failures.json");

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
        ParityRun run = new ParityRun("media", skips("dims-"), FAILURES);

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

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    /** Every input of the module's file-name unit tests. */
    static List<String> nameExamples() {
        List<String> names = new ArrayList<>(List.of(
                "my photo & co.png",
                "  --.hidden.png..",
                ".jpg",
                "JPG",
                "README",
                "shell.php.jpg",
                "archive.tar.gz",
                "report.final.v2.pdf",
                "page.html.jpg",
                "my%20file.png",
                "photo x.jpg",
                "a+b.png",
                "._-x.png-_.",
                "....jpg",
                "x .jpg",
                "jpg",
                "html",
                "x.jpg.php",
                "My.Photo.JPEG",
                "a.b1.c.jpg",
                "a.abcdef.jpg",
                "a.ab12.jpg",
                "a--b  c.png",
                "../../etc/passwd.jpg",
                "a\\b.jpg",
                "x" + cp(0) + ".jpg",
                "%2e%2e%2fx.jpg",
                "a\r\n\tb.jpg",
                "a".repeat(300) + ".jpg"));
        names.add(cp('E', 0x301) + "t" + cp(0xE9) + " " + cp(0xE0) + " Paris (2).JPG");
        names.add(cp(0x424, 0x43E, 0x442, 0x43E) + " 1.jpg");
        names.add("a_b" + cp(0x2014) + "c.png");
        names.add("a" + cp(0xA0) + "b" + cp(0x3000) + "c.png");
        names.add("x" + cp(0x1F600) + ".png");
        names.add("Gr" + cp(0xFC) + cp(0xDF) + "e.jpg");
        names.add(cp(0xC6) + "ble " + cp(0xA3) + "5 " + cp(0x20AC) + ".png");
        names.add(cp(0x2019) + "q" + cp(0xAB) + cp(0xBB) + cp(0x201C) + cp(0x201D) + ".png");
        return names;
    }

    /** Characters the file-name rules treat specially, with letters, digits and a few other scripts. */
    static final String NAME_ALPHABET = "aBz09 .-_%+&'\"?[]/\\=<>:;,$#*()|~`!{}@^\t\r\n\u000b\u0000"
            + cp(
                    0xA0, 0x2003, 0x3000, 0x2019, 0xAB, 0xBB, 0x201C, 0x201D, 0xE9, 0xDF, 0xC6, 0x20AC, 0xA3, 0x2014,
                    0x424, 0x212A, 0x301, 0x2028);

    static final List<String> EXTENSIONS = List.of(
            "jpg", "JPG", "png", "tar", "gz", "html", "htm", "js", "php", "exe", "swf", "pdf", "x-wav", "mp4", "ab1",
            "abcdef", "final", "v2", "Jpeg", "phtml");

    /** Random text, or names built from extensions and the special characters, with or without dot parts. */
    static Gen<String> fileNames() {
        Gen<String> text = Gens.strings(Gens.unicodeChars(), 0, 40);
        Gen<String> special = Gens.strings(NAME_ALPHABET, 0, 12);
        Gen<String> extension = Gens.elements(EXTENSIONS);
        Gen<String> built = random -> {
            StringBuilder name = new StringBuilder(special.next(random));
            int parts = random.nextInt(4);
            for (int i = 0; i < parts; i++) {
                name.append(random.nextInt(4) == 0 ? ".." : ".").append(extension.next(random));
                if (random.nextInt(3) == 0) {
                    name.append(special.next(random));
                }
            }
            return name.toString();
        };
        return Gens.oneOf(text, built, built, extension);
    }

    @Test
    void sanitizeFileName() throws Exception {
        PhpOracle oracle = PhpOracle.create(wordpress());
        ParityRun run = new ParityRun("media-names", skips("names-"), NAME_FAILURES);

        List<String> names = nameExamples();
        int examples = names.size();
        names.addAll(GeneratedCases.sample(fileNames(), "m2a-sanitize", NAMES_GENERATED));
        List<Map.Entry<String, AllowedTypes>> users =
                List.of(Map.entry("author", FileNamesTest.AUTHOR), Map.entry("administrator", FileNamesTest.ADMIN));
        List<PhpOracle.Case> cases = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            for (Map.Entry<String, AllowedTypes> user : users) {
                String id = "names-" + i + "-" + user.getKey();
                cases.add(new PhpOracle.Case(id, nameOp(names.get(i), user.getKey())));
            }
        }
        // WordPress's German accent rules; QuarkPress keeps the default locale's
        String german = "Gr" + cp(0xFC) + cp(0xDF) + "e.jpg";
        cases.add(new PhpOracle.Case("names-de_DE", nameOp(german, "author").put("locale", "de_DE")));
        Map<String, JsonNode> out = oracle.run(DRIVER, cases);
        for (int i = 0; i < names.size(); i++) {
            for (Map.Entry<String, AllowedTypes> user : users) {
                String id = "names-" + i + "-" + user.getKey();
                if (i >= examples) {
                    run.generated(id, OracleJson.MAPPER.writeValueAsString(names.get(i)));
                }
                run.compare(
                        id,
                        "sanitize_file_name",
                        decoded(out.get(id)),
                        FileNames.sanitize(names.get(i), user.getValue()));
            }
        }
        run.compare(
                "names-de_DE",
                "sanitize_file_name",
                decoded(out.get("names-de_DE")),
                FileNames.sanitize(german, FileNamesTest.AUTHOR));

        System.out.println("media file-name parity: " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_NAME_COMPARISONS);
    }

    /** The reference's fraction examples, the unit tests' cases and the shapes cameras write. */
    static final List<String> FRACTION_EXAMPLES = List.of(
            "28/10",
            "8/1",
            "50/1",
            "1/60",
            "1/3",
            "1/250",
            "1/100000",
            "1/0",
            "0/0",
            "2/1/3",
            "",
            "abc",
            "-1/2",
            "5",
            "2.5",
            "1775/1000",
            "2675/1000",
            "1005/1000",
            "285/1000",
            "1115/1000",
            "5015/1000",
            "4/2",
            "0/5",
            "1.0/1",
            "1e2/1",
            " 1 / 2 ",
            "1/0.0",
            "/",
            "1/",
            "0x1A",
            "9223372036854775807/1",
            "-9223372036854775808/-1",
            "4294967295/1",
            "1/4294967295",
            "18/10",
            "35/10",
            "56/10",
            "11/1");

    /** The reference's date examples and the unit tests' cases. */
    static final List<String> DATE_EXAMPLES = List.of(
            "2023:10:15 14:30:00",
            "2023:10:15",
            "2023-10-15 14:30:00",
            "garbage",
            "",
            "2023:02:31",
            "2023:00:15",
            "2023:10:00",
            "0000:00:00 00:00:00",
            "2023:10:15 24:30:00",
            "2023:10:15 23:59:60",
            "2023:10:15 14:30",
            "2023:1:5 1:2:3",
            "2023:10:32",
            "2023:13:01 00:00:00",
            "2023:10:15 25:00:00",
            "2023:10:15 12:60:00",
            "2023:10:15 14",
            "2023:10:15 14:30:",
            "    :  :     :  :  ",
            "1970:01:01 00:00:00",
            "2038:01:19 03:14:08");

    /** Rounding to two places of the reference's edges, and to none of its integer edges. */
    static final double[][] ROUND_EXAMPLES = {
        {1.775, 2},
        {2.675, 2},
        {1.005, 2},
        {0.285, 2},
        {1.115, 2},
        {5.015, 2},
        {2.5, 0},
        {-2.5, 0},
        {0.49999999999999994, 0},
        {1.4999999999999998, 0},
        {-1.775, 2},
        {0.0, 2}
    };

    /** Camera-like fractions, signed ones, decimals and other texts. */
    static Gen<String> fractions() {
        Gen<Integer> small = Gens.ints(0, 100);
        Gen<Integer> large = Gens.ints(0, 100_000);
        Gen<String> camera = random -> large.next(random) + "/" + (random.nextInt(5) == 0 ? small : large).next(random);
        Gen<String> exact = random -> {
            int denominator = 1 + small.next(random);
            return denominator * small.next(random) + "/" + denominator;
        };
        Gen<String> signed = random -> (random.nextBoolean() ? "-" : "") + small.next(random) + "/"
                + (random.nextBoolean() ? "-" : "") + small.next(random);
        Gen<String> decimal = random -> small.next(random) + "." + small.next(random)
                + (random.nextBoolean() ? "/" + (1 + small.next(random)) : "");
        Gen<String> text = Gens.strings("0123456789/.-+ eE", 0, 8);
        return Gens.oneOf(camera, camera, exact, signed, decimal, text);
    }

    /** EXIF-form dates with values in and just outside their ranges, with or without a time. */
    static Gen<String> exifDates() {
        return random -> {
            String date = String.format(
                    Locale.ROOT, "%04d:%02d:%02d", 1900 + random.nextInt(200), random.nextInt(14), random.nextInt(33));
            return switch (random.nextInt(4)) {
                case 0 -> date;
                case 1 -> date + String.format(Locale.ROOT, " %02d:%02d", random.nextInt(26), random.nextInt(61));
                default ->
                    date
                            + String.format(
                                    Locale.ROOT,
                                    " %02d:%02d:%02d",
                                    random.nextInt(26),
                                    random.nextInt(61),
                                    random.nextInt(62));
            };
        };
    }

    @Test
    void exif() throws Exception {
        PhpOracle oracle = PhpOracle.create(wordpress());
        ParityRun run = new ParityRun("media-exif", skips("exif-"), EXIF_FAILURES);

        List<String> fractions = new ArrayList<>(FRACTION_EXAMPLES);
        fractions.addAll(GeneratedCases.sample(fractions(), "m2a-exif", FRACTIONS_GENERATED));
        List<String> dates = new ArrayList<>(DATE_EXAMPLES);
        dates.addAll(GeneratedCases.sample(exifDates(), "m2a-exif-dates", DATES_GENERATED));
        List<PhpOracle.Case> cases = new ArrayList<>();
        List<double[]> rounds = new ArrayList<>();
        for (double[] example : ROUND_EXAMPLES) {
            rounds.add(example);
        }
        for (int i = 0; i < fractions.size(); i++) {
            cases.add(new PhpOracle.Case("frac-" + i, textOp("frac2dec", fractions.get(i))));
            double number = ((Number) ExifNumbers.frac2dec(fractions.get(i))).doubleValue();
            // JSON carries no infinity, so an overflowing quotient is compared by frac2dec alone
            if (Double.isFinite(number)) {
                rounds.add(new double[] {number, 2});
            }
        }
        for (int i = 0; i < dates.size(); i++) {
            cases.add(new PhpOracle.Case("date-" + i, textOp("date2ts", dates.get(i))));
        }
        for (int i = 0; i < rounds.size(); i++) {
            cases.add(new PhpOracle.Case(
                    "round-" + i,
                    OracleJson.MAPPER
                            .createObjectNode()
                            .put("op", "round")
                            .put("value", rounds.get(i)[0])
                            .put("places", (int) rounds.get(i)[1])));
        }
        Map<String, JsonNode> out = oracle.run(DRIVER, cases);
        for (int i = 0; i < fractions.size(); i++) {
            String id = "exif-frac-" + i;
            if (i >= FRACTION_EXAMPLES.size()) {
                run.generated(id, OracleJson.MAPPER.writeValueAsString(fractions.get(i)));
            }
            Object ours = ExifNumbers.frac2dec(fractions.get(i));
            JsonNode theirs = out.get("frac-" + i);
            run.compare(
                    id,
                    "wp_exif_frac2dec type",
                    theirs.get("type").asText(),
                    ours instanceof Long ? "integer" : "double");
            run.compare(id, "wp_exif_frac2dec text", theirs.get("text").asText(), ExifNumbers.text(ours));
        }
        for (int i = 0; i < dates.size(); i++) {
            String id = "exif-date-" + i;
            if (i >= DATE_EXAMPLES.size()) {
                run.generated(id, OracleJson.MAPPER.writeValueAsString(dates.get(i)));
            }
            JsonNode ts = out.get("date-" + i).get("ts");
            OptionalLong ours = ExifNumbers.date2ts(dates.get(i));
            run.compare(
                    id,
                    "wp_exif_date2ts",
                    ts.isBoolean() ? "false" : ts.asText(),
                    ours.isPresent() ? Long.toString(ours.getAsLong()) : "false");
        }
        for (int i = 0; i < rounds.size(); i++) {
            String id = "exif-round-" + i;
            double value = rounds.get(i)[0];
            int places = (int) rounds.get(i)[1];
            String bits =
                    String.format(Locale.ROOT, "%016x", Double.doubleToRawLongBits(Dimensions.round(value, places)));
            run.compare(
                    id,
                    "round(" + value + ", " + places + ")",
                    out.get("round-" + i).get("bits").asText(),
                    bits);
        }

        System.out.println("media exif parity: " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        run.assertParity(MIN_EXIF_COMPARISONS);
    }

    private static ObjectNode textOp(String op, String text) {
        return OracleJson.MAPPER.createObjectNode().put("op", op).put("text", text);
    }

    private static ObjectNode nameOp(String name, String user) {
        return OracleJson.MAPPER
                .createObjectNode()
                .put("op", "sanitize_file_name")
                .put("name", name)
                .put("unfiltered_html", user.equals("administrator"));
    }

    private static String decoded(JsonNode result) {
        return new String(Base64.getDecoder().decode(result.get("name").asText()), StandardCharsets.UTF_8);
    }

    /**
     * The skip list's lines for one test's cases (those whose case pattern starts with {@code prefix}), as a list of
     * its own: the oracle's run refuses an entry that matches none of its comparisons, and the tests share one file.
     */
    static ParitySkipList skips(String prefix) throws IOException {
        List<String> kept = new ArrayList<>();
        for (String line : Files.readAllLines(SKIPS, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith(prefix)) {
                kept.add(line);
            }
        }
        Path file = Path.of("target", "wp-parity-media-skip-" + prefix + "txt");
        Files.createDirectories(file.getParent());
        Files.write(file, kept, StandardCharsets.UTF_8);
        return ParitySkipList.load(file);
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
