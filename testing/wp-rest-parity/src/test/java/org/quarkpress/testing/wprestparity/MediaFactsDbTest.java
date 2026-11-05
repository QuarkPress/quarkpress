package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.DoubleNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressScenarioOracle;
import org.quarkpress.testing.wpscenario.WordPressTree;

/**
 * Records what WordPress 7.1.3 answers for the media references' verification lists: the uploads reference's Part G
 * ({@code u01}-{@code u15}) and the image-processing reference's Part G ({@code i01}-{@code i18}). Every item runs on a
 * subdirectory site of its own (a fresh uploads folder, emptied again afterwards), records every exchange with
 * {@link VerificationFacts#record} and every setup answer and black-box function probe with {@link
 * VerificationFacts#note}, and switches off in its own {@code finally} every {@code qp_parity_*} switch it turns on.
 * Only the few expectations the references state firmly are asserted; everything else is recorded. Items that need an
 * image editor run only when the server's PHP has GD (as the CI parity cell does) and are skipped with a message
 * otherwise, or fail instead under {@code -Dwp.media.require-editor=true}; Imagick items never run (GD is the
 * reference).
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
@TestMethodOrder(MethodOrderer.MethodName.class)
class MediaFactsDbTest {

    static final String GD_SKIP = "SKIPPED (needs an image editor): php8.4-gd is not installed on this host; the CI"
            + " parity cell installs it (ruling M17)";
    static final String IMAGICK_SKIP = "SKIPPED (Imagick): the parity reference is WordPress with GD (ruling M17)";

    static ScenarioDatabase database;
    static WordPressRestSite site;
    static RestClient client;
    static Extensions extensions;

    /** The PHP extensions of the server (rest-setup.php {@code extensions}). */
    record Extensions(
            boolean gd, boolean gdWebp, boolean gdAvif, boolean imagick, boolean dom, boolean exif, boolean fileinfo) {}

    /** The item the running test records under; null when it records nothing. */
    String item;

    /** The site paths ({@code /mu01/}) the running item created; their uploads are emptied after it. */
    final List<String> sites = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        database = ScenarioDatabase.start();
        // Harness rule (opt-in for this run only): the installing administrator is the current user while the
        // template network is created, as on the Network Setup screen, so upload_filetypes is written for that user.
        System.setProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER, "true");
        try {
            site = WordPressRestSite.start(
                    WordPressTree.ensure(System.getProperty("wp.version")),
                    database,
                    System.getProperty("wp.php", "php"));
        } finally {
            System.clearProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER);
        }
        client = new RestClient(site.root(), RestClient.applicationPasswords(site));
        JsonNode found = site.operation("extensions", args());
        extensions = new Extensions(
                found.path("gd").asBoolean(),
                found.path("gd_webp").asBoolean(),
                found.path("gd_avif").asBoolean(),
                found.path("imagick").asBoolean(),
                found.path("dom").asBoolean(),
                found.path("exif").asBoolean(),
                found.path("fileinfo").asBoolean());
        VerificationFacts.note("x-media-extensions", "extensions", found);
    }

    @AfterAll
    static void stop() {
        try {
            VerificationFacts.save();
        } finally {
            if (site != null) {
                site.close();
            }
            if (database != null) {
                database.close();
            }
        }
    }

    @BeforeEach
    void name(TestInfo info) {
        String method = info.getTestMethod().orElseThrow().getName();
        boolean itemName = method.length() > 3
                && (method.charAt(0) == 'u' || method.charAt(0) == 'i')
                && Character.isDigit(method.charAt(1))
                && Character.isDigit(method.charAt(2));
        item = itemName ? method.substring(0, 3) : null;
    }

    @AfterEach
    void clearUploads() throws Exception {
        // Harness rule: the shared WordPress tree keeps no files of an item.
        for (String path : sites) {
            site.operation("clear_uploads", args().put("site", path));
        }
        sites.clear();
    }

    // ---- uploads reference, Part G ----------------------------------------------------------------------------

    @Test
    void u01UnsupportedImagesWithoutAnEditor() throws Exception { // MU12
        String prefix = createSite("mu01");
        VerificationFacts.note(item, "extensions", OracleJson.MAPPER.valueToTree(extensions));
        Exchange jpeg = upload(prefix, "alice", "u01.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48), Map.of());
        if (!extensions.gd()) {
            assertThat(jpeg.status()).isEqualTo(400);
            assertThat(body(jpeg).get("code").asText()).isEqualTo("rest_upload_image_type_not_supported");
            assertThat(body(jpeg).get("message").asText())
                    .isEqualTo("The web server cannot generate responsive image"
                            + " sizes for this image. Convert it to JPEG or PNG before uploading.");
        }
        Exchange skipped = upload(prefix, "alice", "u01b.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48), noSubSizes());
        assertThat(skipped.status()).isEqualTo(201);
        noteAttachment(prefix, "u01b.jpg", skipped);
        // recorded only
        noteAttachment(
                prefix,
                "u01.heic",
                upload(prefix, "alice", "u01.heic", "image/heic", MediaFixtures.heif("heic"), Map.of()));
        noteAttachment(
                prefix,
                "u01.heics",
                upload(prefix, "alice", "u01.heics", "image/heic-sequence", MediaFixtures.heif("msf1"), Map.of()));
        upload(prefix, "alice", "u01-refused.svg", "image/svg+xml", svg(), Map.of());
        try {
            option(prefix, "qp_parity_allow_svg", "1");
            noteAttachment(prefix, "u01.svg", upload(prefix, "alice", "u01.svg", "image/svg+xml", svg(), Map.of()));
        } finally {
            option(prefix, "qp_parity_allow_svg", "0");
        }
        noteAttachment(
                prefix,
                "u01-octet.jpg",
                upload(
                        prefix,
                        "alice",
                        "u01-octet.jpg",
                        "application/octet-stream",
                        MediaFixtures.jpeg(64, 48),
                        Map.of()));
    }

    @Test
    void u02DeclaredTypesWithGd() throws Exception { // MU12 rule 7 [GD]
        requireEditor();
        String prefix = createSite("mu02");
        byte[] jpeg = MediaFixtures.jpeg(64, 48);
        upload(prefix, "alice", "u02-jpg.jpg", "image/jpg", jpeg, Map.of());
        upload(prefix, "alice", "u02-pjpeg.jpg", "image/pjpeg", jpeg, Map.of());
        upload(prefix, "alice", "u02.bmp", "image/bmp", MediaFixtures.bmp(64, 48), Map.of());
        upload(prefix, "alice", "u02.tif", "image/tiff", MediaFixtures.tiff(64, 48), Map.of());
        upload(prefix, "alice", "u02.ico", "image/x-icon", MediaFixtures.ico(), Map.of());
        noteAttachment(
                prefix,
                "u02-octet.jpg",
                upload(prefix, "alice", "u02-octet.jpg", "application/octet-stream", jpeg, Map.of()));
        noteAttachment(
                prefix,
                "u02-octet.bmp",
                upload(
                        prefix,
                        "alice",
                        "u02-octet.bmp",
                        "application/octet-stream",
                        MediaFixtures.bmp(64, 48),
                        Map.of()));
        noteAttachment(
                prefix,
                "u02-octet.tif",
                upload(
                        prefix,
                        "alice",
                        "u02-octet.tif",
                        "application/octet-stream",
                        MediaFixtures.tiff(64, 48),
                        Map.of()));
        noteAttachment(
                prefix,
                "u02-octet.ico",
                upload(prefix, "alice", "u02-octet.ico", "application/octet-stream", MediaFixtures.ico(), Map.of()));
    }

    @Test
    void u03ContentTypeChecks() throws Exception { // MU9, MU10 rule 4
        String prefix = createSite("mu03");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("u03.ico", MediaFixtures.ico());
        files.put("u03.psd", MediaFixtures.psd());
        files.put("u03.c", MediaFixtures.text("#include <stdio.h>\nint main(void) {\n    return 0;\n}\n"));
        files.put("u03.css", MediaFixtures.text("body {\n  color: red;\n}\n"));
        files.put("u03.srt", MediaFixtures.text("1\n00:00:01,000 --> 00:00:02,000\nHello\n\n"));
        files.put("u03.tsv", MediaFixtures.text("name\tvalue\none\t1\ntwo\t2\n"));
        files.put("u03-audio.m4a", MediaFixtures.m4a());
        files.put("u03-video.m4a", MediaFixtures.mp4(64, 48, 1));
        files.put("u03.docx", MediaFixtures.zipOoxml(DOCX));
        files.put("u03.csv", MediaFixtures.text("name,value\none,1\ntwo,2\n"));
        files.put("u03.gz", gzip(MediaFixtures.text("u03 gzip content\n")));
        files.put("u03.rar", rar());
        files.put("u03.ogv", MediaFixtures.ogv());
        files.put("u03.js", MediaFixtures.text("console.log('u03');\n"));
        files.put("u03.vtt", MediaFixtures.text("WEBVTT\n\n00:00.000 --> 00:01.000\nHello\n"));
        files.put("u03.rtf", MediaFixtures.text("{\\rtf1\\ansi\\deff0 {\\fonttbl {\\f0 Times;}}\\f0 Hello}\n"));
        files.put("u03.flac", flac());
        files.put("u03.wav", MediaFixtures.wav(1));
        files.put("u03.xls", MediaFixtures.xls());
        files.put("u03.odt", MediaFixtures.odt());
        files.put("u03.ogg", MediaFixtures.ogg());
        List<ObjectNode> checks = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            checks.add(call("wp_check_filetype_and_ext", fileArg("phpu03.tmp", file.getValue()), file.getKey()));
        }
        noteProbes("wp_check_filetype_and_ext/alice", checks, probes(settings(prefix, "alice"), checks));
        noteProbes("wp_check_filetype_and_ext/carol", checks, probes(settings(prefix, "carol"), checks));
        List<ObjectNode> mimes = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            mimes.add(call("wp_get_image_mime", fileArg("phpu03-mime.tmp", file.getValue())));
        }
        noteProbes("wp_get_image_mime", mimes, probes(settings(prefix, "alice"), mimes));
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            noteAttachment(
                    prefix,
                    file.getKey(),
                    upload(prefix, "alice", file.getKey(), "application/octet-stream", file.getValue(), Map.of()));
        }
        noteAttachment(
                prefix,
                "u03-carol.js",
                upload(prefix, "carol", "u03-carol.js", "application/octet-stream", files.get("u03.js"), Map.of()));
    }

    @Test
    void u04ExtensionRewrite() throws Exception { // MU10
        String prefix = createSite("mu04");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put("u04-png-as.jpg", MediaFixtures.png(32, 24));
        files.put("u04-jpeg-as.PNG", MediaFixtures.jpeg(32, 24));
        files.put("u04-png-as.webp", MediaFixtures.png(32, 24));
        files.put("u04-heif.heif", MediaFixtures.heif("heif"));
        files.put("u04-heif-upper.HEIF", MediaFixtures.heif("heif"));
        for (String brand : List.of("heif", "heic", "avif", "avis", "mif1", "msf1")) {
            files.put("u04-brand-" + brand + ".heif", MediaFixtures.heif(brand));
            files.put("u04-brand-" + brand + ".avif", MediaFixtures.heif(brand));
        }
        files.put("u04-pdf-as.jpg", MediaFixtures.pdf());
        List<ObjectNode> checks = new ArrayList<>();
        List<ObjectNode> mimes = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            checks.add(call("wp_check_filetype_and_ext", fileArg("phpu04.tmp", file.getValue()), file.getKey()));
            mimes.add(call("wp_get_image_mime", fileArg("phpu04-mime.tmp", file.getValue())));
        }
        noteProbes("wp_check_filetype_and_ext", checks, probes(settings(prefix, "alice"), checks));
        noteProbes("wp_get_image_mime", mimes, probes(settings(prefix, "alice"), mimes));
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            noteAttachment(
                    prefix,
                    file.getKey(),
                    upload(prefix, "alice", file.getKey(), "application/octet-stream", file.getValue(), Map.of()));
        }
    }

    @Test
    void u05SanitizeFileName() throws Exception { // MU13 table + extras
        assertThat(probe("sanitize_file_name", "my photo & co.png")).isEqualTo("my-photo-co.png");
        assertThat(probe("sanitize_file_name", "shell.php.jpg")).isEqualTo("shell.php_.jpg");
        assertThat(probe("sanitize_file_name", "JPG")).isEqualTo("unnamed-file.JPG");
        String prefix = createSite("mu05");
        List<String> names = new ArrayList<>(List.of(
                "\u00C9t\u00E9 \u00E0 Paris (2).JPG",
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
                "a_b\u2014c.png",
                cyrillicPhoto() + " 1.jpg",
                "photo x.jpg",
                "a b.png",
                "a+b.png",
                "x" + new String(Character.toChars(0x1F600)) + ".png",
                "._-x.png-_.",
                "%2e%2e%2fx.jpg",
                "../../etc/passwd.jpg",
                "a\\b.jpg",
                "x\u0000.jpg",
                "a\u00A0b.png",
                "a\u2003b.png",
                "a\u3000b.png",
                "\u00C6ble \u00A35 \u20AC.png",
                "Gr\u00FC\u00DFe.jpg",
                "a\tb\nc.png",
                "a--b  c.png",
                "x.jpg.php",
                "jpg",
                "html",
                "My.Photo.JPEG",
                "a.b1.c.jpg",
                "a.abcdef.jpg",
                "a.ab12.jpg"));
        names.add("a".repeat(296) + ".jpg");
        List<ObjectNode> calls = new ArrayList<>();
        for (String name : names) {
            calls.add(call("sanitize_file_name", name));
        }
        noteProbes("sanitize_file_name/alice", calls, probes(settings(prefix, "alice"), calls));
        noteProbes("sanitize_file_name/carol", calls, probes(settings(prefix, "carol"), calls));
        String gruesse = "Gr\u00FC\u00DFe.jpg";
        List<ObjectNode> german = List.of(
                call("sanitize_file_name", gruesse),
                call("sanitize_file_name", "\u00C4rger \u00F6l \u00FC.jpg"),
                call("get_locale"));
        noteProbes(
                "sanitize_file_name/switch_locale de_DE",
                german,
                probes(settings(prefix, "alice").put("locale", "de_DE"), german));
        // Harness rule: the locale filter answers de_DE (a stand-in for a German site without the language pack).
        noteProbes(
                "sanitize_file_name/locale filter de_DE",
                german,
                probes(settings(prefix, "alice").put("locale_filter", "de_DE"), german));
    }

    @Test
    void u06UniqueFileNames() throws Exception { // MU15-MU18
        String prefix = createSite("mu06");
        byte[] any = MediaFixtures.text("u06");
        record Case(String label, List<String> files, String name) {}
        List<Case> cases = List.of(
                new Case("c01", List.of(), "IMG_1234.JPG"),
                new Case("c02", List.of("IMG_1234.jpg"), "IMG_1234.JPG"),
                new Case("c03", List.of("IMG_1234.JPG"), "IMG_1234.JPG"),
                new Case("c04", List.of("Photo.jpg"), "photo.jpg"),
                new Case("c05", List.of(), "logo-150x150.png"),
                new Case("c06", List.of("logo-150x150-1.png"), "logo-150x150.png"),
                new Case("c07", List.of(), "holiday-scaled.jpg"),
                new Case("c08", List.of(), "holiday-rotated.JPG"),
                new Case("c09", List.of(), "logo-150X150.png"),
                new Case("c10", List.of("photo.jpg", "photo-300x200.jpg"), "photo.jpg"),
                new Case("c11", List.of("photo-300x200.jpg"), "photo.jpg"),
                new Case("c12", List.of("Photo-SCALED.JPG"), "photo.jpg"),
                new Case("c13", List.of("trip.heic"), "trip.jpg"),
                new Case("c14", List.of("trip.jpg"), "trip.heic"),
                new Case("c15", List.of("trip-1024x768.jpg"), "trip.heic"),
                new Case("c16", List.of("photo-150x150.png", "photo-1.png"), "photo.png"),
                new Case("c17", List.of("a.jpg"), "a.jpg.jpg"),
                new Case("c18", List.of("file1"), "file1"),
                new Case("c19", List.of("trip.heif"), "trip.jpg"),
                new Case("c20", List.of("photo.jpg", "photo-1.heic"), "photo.jpg"));
        List<ObjectNode> calls = new ArrayList<>();
        ObjectNode folders = OracleJson.MAPPER.createObjectNode();
        for (Case c : cases) {
            ObjectNode files = OracleJson.MAPPER.createObjectNode();
            c.files().forEach(name -> files.put(name, Base64.getEncoder().encodeToString(any)));
            ObjectNode input = args().put("site", prefix + "/").put("folder", "u06/" + c.label());
            input.set("files", files);
            folders.set(c.label(), site.operation("prepare_folder", input));
            calls.add(call("wp_unique_filename", "u06/" + c.label(), c.name()));
        }
        VerificationFacts.note(item, "folders", folders);
        noteProbes("wp_unique_filename", calls, probes(settings(prefix, "alice"), calls));
        noteProbes(
                "wp_unique_filename/convert_format=false",
                calls,
                probes(settings(prefix, "alice").put("no_convert", true), calls));
    }

    @Test
    void u07ListingCollisionOverwrite() throws Exception { // MU17 rule 3
        String prefix = createSite("mu07");
        ObjectNode files = OracleJson.MAPPER.createObjectNode();
        files.put("photo-150x150.png", Base64.getEncoder().encodeToString(MediaFixtures.png(150, 150)));
        files.put("photo-1.png", Base64.getEncoder().encodeToString(MediaFixtures.png(2, 2)));
        ObjectNode input = args().put("site", prefix + "/").put("current", true);
        input.set("files", files);
        JsonNode prepared = site.operation("prepare_folder", input);
        VerificationFacts.note(item, "before", prepared);
        Exchange uploaded = upload(prefix, "alice", "photo.png", "image/png", MediaFixtures.png(10, 10), noSubSizes());
        noteAttachment(prefix, "photo.png", uploaded);
        JsonNode after = site.operation(
                "uploads_listing",
                args().put("site", prefix + "/")
                        .put("folder", prepared.get("folder").asText()));
        VerificationFacts.note(item, "after", after);
        String before1 = md5Of(prepared.get("listing"), "photo-1.png");
        String after1 = md5Of(after.get("listing"), "photo-1.png");
        VerificationFacts.note(item, "photo-1.png bytes changed", BooleanNode.valueOf(!before1.equals(after1)));
    }

    @Test
    void u08EditorSideSizeLimit() {
        assumeTrue(
                false, "SKIPPED: the editor's own size check runs in the browser; the HAR capture profile covers it");
    }

    @Test
    void u09MultisiteLimits() throws Exception { // MU3, MU7a, MU8, MU19
        List<String> names =
                List.of("upload_space_check_disabled", "blog_upload_space", "fileupload_maxk", "upload_filetypes");
        Map<String, JsonNode> original = new LinkedHashMap<>();
        for (String name : names) {
            original.put(
                    name,
                    site.operation("network_option", args().put("name", name)).get("value"));
        }
        VerificationFacts.note(item, "network options after setup", OracleJson.MAPPER.valueToTree(original));
        String prefix = createSite("mu09");
        try {
            // The site's folder, URL and attached file (the reference's site-3 example), with the setup's options.
            Exchange placed =
                    upload(prefix, "alice", "u09-site.jpg", "image/jpeg", MediaFixtures.jpeg(16, 16), noSubSizes());
            noteAttachment(prefix, "u09-site.jpg", placed);
            setNetworkOption("upload_space_check_disabled", TextNode.valueOf("0"));
            setNetworkOption("blog_upload_space", TextNode.valueOf("1"));
            setNetworkOption("fileupload_maxk", TextNode.valueOf("1500"));
            noteNetworkOptions("network options with checks on", names);
            // 1 MB quota with only the 648-byte u09-site.jpg used (below 1 KB, so the KB figures are unchanged):
            // 1100.5 KB needs about 77 KB more; 2252.8 KB needs about 1229 KB more.
            upload(prefix, "alice", "u09-space-a.txt", "text/plain", filler(1_126_912), Map.of());
            upload(prefix, "alice", "u09-space-b.txt", "text/plain", filler(2_306_867), Map.of());
            // The site's own blog_upload_space of 2 (MB) overrides the network's 1.
            option(prefix, "blog_upload_space", "2");
            upload(prefix, "alice", "u09-big.txt", "text/plain", filler(1_537_024), Map.of());
            noteAttachment(
                    prefix,
                    "u09-limit.txt",
                    upload(prefix, "alice", "u09-limit.txt", "text/plain", filler(1_536_000), Map.of()));
            // Over quota: fill the site's uploads past 2 MB by hand (every file counts), then an empty file.
            ObjectNode fill = args().put("site", prefix + "/").put("folder", "u09-fill");
            fill.putObject("files").put("fill.bin", Base64.getEncoder().encodeToString(filler(2_200_000)));
            VerificationFacts.note(item, "fill", site.operation("prepare_folder", fill));
            upload(prefix, "alice", "u09-empty.txt", "text/plain", new byte[0], Map.of());
            upload(prefix, "alice", "u09-one.txt", "text/plain", new byte[] {'x'}, Map.of());
            // upload_filetypes: substring matching of each listed extension.
            for (String list : List.of("mp", "c", "jpg png pdf")) {
                setNetworkOption("upload_filetypes", TextNode.valueOf(list));
                List<ObjectNode> calls = List.of(call("get_allowed_mime_types", "alice"));
                noteProbes(
                        "get_allowed_mime_types/upload_filetypes=" + list,
                        calls,
                        probes(settings(prefix, "alice"), calls));
                List<ObjectNode> bob = List.of(call("get_allowed_mime_types", "bob"));
                noteProbes(
                        "get_allowed_mime_types/bob/upload_filetypes=" + list,
                        bob,
                        probes(settings(prefix, "bob"), bob));
            }
            setNetworkOption("upload_filetypes", original.get("upload_filetypes"));
            List<ObjectNode> setup = List.of(call("get_allowed_mime_types", "alice"));
            noteProbes("get_allowed_mime_types/setup list", setup, probes(settings(prefix, "alice"), setup));
        } finally {
            for (String name : names) {
                setNetworkOption(name, original.get(name));
            }
        }
    }

    @Test
    void u09zAdminUploadOrder() {
        assumeTrue(false, "SKIPPED: the admin upload path is not reproduced (media README D10)");
    }

    @Test
    void u10EmbeddedTitleCaptionAlt() throws Exception { // MU21-MU23
        String prefix = createSite("mu10");
        Map<String, JpegMeta> cases = new LinkedHashMap<>();
        JpegMeta none = JpegMeta.none();
        cases.put("a-headline", none.withIptc(105, "Sunset at S\u00E8te").withIptc(120, "Taken from the pier, 2025"));
        cases.put("b-object-name", none.withIptc(5, "u10 object name"));
        cases.put("c-caption-79", none.withIptc(120, padded("u10 caption ", 79)));
        cases.put("d-caption-80", none.withIptc(120, padded("u10 caption ", 80)));
        cases.put("e-object-2024", none.withIptc(5, "2024"));
        cases.put("f-object-3.5", none.withIptc(5, "3.5"));
        cases.put("f2-object-0012", none.withIptc(5, "0012"));
        cases.put("f3-object-img", none.withIptc(5, "IMG 4711"));
        cases.put("g-description", none.withExif("ImageDescription", "OLYMPUS DIGITAL CAMERA"));
        cases.put("h-user-comment", none.withExif("UserComment", "u10 user comment"));
        cases.put(
                "i-description-and-comment",
                none.withExif("ImageDescription", "Sunset").withExif("UserComment", "Shot at Malibu"));
        cases.put(
                "j-description-equals-comment",
                none.withExif("ImageDescription", "Same text").withExif("UserComment", "Same text"));
        cases.put(
                "k-long-description-and-comment",
                none.withExif("ImageDescription", padded("u10 long description ", 120))
                        .withExif("UserComment", "Short comment"));
        cases.put(
                "k2-headline-description-comment",
                none.withIptc(105, "Beach")
                        .withExif("ImageDescription", "Sunset")
                        .withExif("UserComment", "Malibu"));
        cases.put("l-xmp-x-default", none.withXmp(xmpAlt(Map.of("x-default", "u10 alt default"))));
        cases.put("m-xmp-en", none.withXmp(xmpAlt(ordered("x-default", "u10 alt default", "en", "u10 alt en"))));
        cases.put(
                "n-xmp-en-US", none.withXmp(xmpAlt(ordered("x-default", "u10 alt default", "en-US", "u10 alt en-US"))));
        cases.put(
                "o-xmp-en_US",
                none.withXmp(
                        xmpAlt(ordered("x-default", "u10 alt default", "en", "u10 alt en", "en_US", "u10 alt en_US"))));
        for (Map.Entry<String, JpegMeta> c : cases.entrySet()) {
            String name = "u10-" + c.getKey() + ".jpg";
            noteAttachment(
                    prefix,
                    name,
                    upload(
                            prefix,
                            "alice",
                            name,
                            "image/jpeg",
                            MediaFixtures.jpeg(32, 24, c.getValue()),
                            noSubSizes()));
        }
        JpegMeta full =
                none.withIptc(105, "u10 embedded headline").withXmp(xmpAlt(Map.of("x-default", "u10 embedded alt")));
        Map<String, String> given = new LinkedHashMap<>(noSubSizes());
        given.put("title", "u10 given title");
        given.put("alt_text", "");
        noteAttachment(
                prefix,
                "u10-p-request-title.jpg",
                upload(
                        prefix,
                        "alice",
                        "u10-p-request-title.jpg",
                        "image/jpeg",
                        MediaFixtures.jpeg(32, 24, full),
                        given));
        Map<String, String> caption = new LinkedHashMap<>(noSubSizes());
        caption.put("caption", "u10 given caption");
        caption.put("alt_text", "u10 given alt");
        noteAttachment(
                prefix,
                "u10-q-request-caption.jpg",
                upload(
                        prefix,
                        "alice",
                        "u10-q-request-caption.jpg",
                        "image/jpeg",
                        MediaFixtures.jpeg(32, 24, full.withIptc(120, "u10 embedded caption")),
                        caption));
    }

    @Test
    void u11FolderDate() throws Exception { // MU20, MU4 step 3
        String prefix = createSite("mu11");
        String path = prefix + "/";
        long post =
                insert(path, "alice", "post", "publish", "u11 post", fields().put("post_date", "2019-03-14 12:00:00"));
        long page =
                insert(path, "alice", "page", "publish", "u11 page", fields().put("post_date", "2019-03-14 12:00:00"));
        long future =
                insert(path, "alice", "post", "future", "u11 future", fields().put("post_date", "2027-01-10 12:00:00"));
        long draft =
                insert(path, "alice", "post", "draft", "u11 draft", fields().put("post_date", "2018-05-20 10:00:00"));
        long floating = insert(path, "alice", "post", "draft", "u11 floating draft", fields());
        long revision = site.operation(
                        "revision",
                        args().put("site", path)
                                .put("as", "alice")
                                .put("id", post)
                                .put("title", "u11 post revised")
                                .put("content", "u11 revised content"))
                .get("id")
                .longValue();
        VerificationFacts.note(
                item,
                "fixtures",
                OracleJson.MAPPER.valueToTree(ordered(
                        "post", String.valueOf(post),
                        "page", String.valueOf(page),
                        "future", String.valueOf(future),
                        "draft", String.valueOf(draft),
                        "floating draft", String.valueOf(floating),
                        "revision", String.valueOf(revision))));
        Map<String, Long> parents = new LinkedHashMap<>();
        parents.put("post", post);
        parents.put("page", page);
        parents.put("future", future);
        parents.put("draft", draft);
        parents.put("floating", floating);
        parents.put("revision", revision);
        parents.put("missing", 999_999L);
        Long attachment = null;
        for (Map.Entry<String, Long> parent : parents.entrySet()) {
            Map<String, String> fields = new LinkedHashMap<>(noSubSizes());
            fields.put("post", String.valueOf(parent.getValue()));
            String name = "u11-" + parent.getKey() + ".jpg";
            Exchange uploaded = upload(prefix, "alice", name, "image/jpeg", MediaFixtures.jpeg(16, 16), fields);
            noteAttachment(prefix, name, uploaded);
            if (attachment == null && uploaded.status() == 201) {
                attachment = body(uploaded).get("id").longValue();
            }
        }
        if (attachment != null) {
            Map<String, String> toAttachment = new LinkedHashMap<>();
            toAttachment.put("post", String.valueOf(attachment));
            upload(prefix, "alice", "u11-webp-to-attachment.webp", "image/webp", MediaFixtures.webp(), toAttachment);
            Map<String, String> toAttachmentNoSizes = new LinkedHashMap<>(noSubSizes());
            toAttachmentNoSizes.put("post", String.valueOf(attachment));
            upload(
                    prefix,
                    "alice",
                    "u11-jpeg-to-attachment.jpg",
                    "image/jpeg",
                    MediaFixtures.jpeg(16, 16),
                    toAttachmentNoSizes);
        }
        // Europe/Helsinki: the folder follows the site's local time; a month boundary cannot be forced.
        option(prefix, "timezone_string", "Europe/Helsinki");
        Exchange helsinki =
                upload(prefix, "alice", "u11-helsinki.jpg", "image/jpeg", MediaFixtures.jpeg(16, 16), noSubSizes());
        noteAttachment(prefix, "u11-helsinki.jpg", helsinki);
        VerificationFacts.note(
                item,
                "helsinki sent at (UTC)",
                TextNode.valueOf(helsinki.sentAt().toString()));
        VerificationFacts.note(
                item,
                "month boundary",
                TextNode.valueOf("not reproducible: the harness has no clock option; the Helsinki upload records the"
                        + " folder for the moment it was sent"));
    }

    @Test
    void u12NonAsciiFileName() throws Exception { // MU19 rule 6
        String prefix = createSite("mu12");
        String name = cyrillicPhoto() + " 1.jpg";
        Exchange uploaded = upload(prefix, "alice", name, "image/jpeg", MediaFixtures.jpeg(16, 16), noSubSizes());
        noteAttachment(prefix, name, uploaded);
        if (uploaded.status() == 201) {
            VerificationFacts.note(item, "source_url", body(uploaded).path("source_url"));
            VerificationFacts.note(item, "guid", body(uploaded).path("guid"));
        }
    }

    @Test
    void u13DeleteRemovesFiles() throws Exception { // MU27-MU29
        String prefix = createSite("mu13");
        String path = prefix + "/";
        Exchange uploaded = upload(prefix, "alice", "u13.jpg", "image/jpeg", MediaFixtures.jpeg(64, 48), noSubSizes());
        long id = body(uploaded).get("id").longValue();
        JsonNode stored = site.operation("attachment", args().put("site", path).put("id", id));
        String file = stored.get("meta").get("_wp_attached_file").get(0).asText();
        String folder = file.contains("/") ? file.substring(0, file.lastIndexOf('/')) : "";
        String stem = file.substring(file.lastIndexOf('/') + 1, file.lastIndexOf('.'));
        // Two sizes, an original and an editor backup, written by hand (no editor needed); their files beside.
        ObjectNode metadata = OracleJson.MAPPER.createObjectNode();
        metadata.put("width", 64).put("height", 48).put("file", file).put("filesize", 1000);
        ObjectNode sizes = metadata.putObject("sizes");
        sizes.set("thumbnail", sizeEntry(stem + "-150x150.jpg", 150, 150));
        sizes.set("medium", sizeEntry(stem + "-300x225.jpg", 300, 225));
        metadata.putObject("image_meta");
        metadata.put("original_image", stem + "-original.jpg");
        setMeta(path, id, "_wp_attachment_metadata", metadata);
        ObjectNode backups = OracleJson.MAPPER.createObjectNode();
        backups.putObject("full-orig")
                .put("width", 64)
                .put("height", 48)
                .put("filesize", 10)
                .put("file", stem + "-e1700000000123.jpg");
        setMeta(path, id, "_wp_attachment_backup_sizes", backups);
        ObjectNode files = OracleJson.MAPPER.createObjectNode();
        for (String extra :
                List.of("-150x150.jpg", "-300x225.jpg", "-original.jpg", "-e1700000000123.jpg", "-other.jpg")) {
            files.put(stem + extra, Base64.getEncoder().encodeToString(MediaFixtures.jpeg(4, 4)));
        }
        ObjectNode prepare = args().put("site", path).put("folder", folder).put("clear", false);
        prepare.set("files", files);
        site.operation("prepare_folder", prepare);
        long first = insert(path, "alice", "post", "publish", "u13 first", fields().put("thumbnail", id));
        long second = insert(path, "alice", "post", "publish", "u13 second", fields().put("thumbnail", id));
        VerificationFacts.note(
                item,
                "before",
                site.operation("attachment", args().put("site", path).put("id", id)));
        VerificationFacts.note(
                item,
                "main file removed",
                site.operation("remove_upload", args().put("site", path).put("file", file)));
        send(RestCall.json("alice", "DELETE", prefix + "/wp-json/wp/v2/media/" + id + "?force=true", "{}"));
        VerificationFacts.note(
                item,
                "after",
                site.operation("attachment", args().put("site", path).put("id", id)));
        VerificationFacts.note(
                item,
                "listing after",
                site.operation("uploads_listing", args().put("site", path).put("folder", folder)));
        noteMeta("first post _thumbnail_id", path, first, "_thumbnail_id");
        noteMeta("second post _thumbnail_id", path, second, "_thumbnail_id");
        VerificationFacts.note(
                item,
                "not covered here",
                TextNode.valueOf("a server-made -scaled upload's original_image needs GD (i10 records -scaled files);"
                        + " the original and the backup above were written by hand"));
    }

    @Test
    void u14HooksAndModifiedDate() throws Exception { // MU24 rule 15, MU28
        String prefix = createSite("mu14");
        String path = prefix + "/";
        try {
            option(prefix, "qp_parity_hook_log", "1");
            clearHookLog(prefix);
            Exchange uploaded =
                    upload(prefix, "alice", "u14.jpg", "image/jpeg", MediaFixtures.jpeg(16, 16), noSubSizes());
            long id = body(uploaded).get("id").longValue();
            noteHookLog(prefix, "upload");
            send(RestCall.json("alice", "POST", prefix + "/wp-json/wp/v2/media/" + id, "{\"title\":\"u14 renamed\"}"));
            noteHookLog(prefix, "title update");
            JsonNode before =
                    site.operation("attachment", args().put("site", path).put("id", id));
            Thread.sleep(1100);
            send(RestCall.json("alice", "POST", prefix + "/wp-json/wp/v2/media/" + id, "{\"alt_text\":\"u14 alt\"}"));
            JsonNode after =
                    site.operation("attachment", args().put("site", path).put("id", id));
            noteHookLog(prefix, "alt_text-only update");
            ObjectNode modified = OracleJson.MAPPER.createObjectNode();
            modified.set("before", before.path("row").path("post_modified_gmt"));
            modified.set("after", after.path("row").path("post_modified_gmt"));
            modified.put(
                    "changed",
                    !before.path("row")
                            .path("post_modified_gmt")
                            .equals(after.path("row").path("post_modified_gmt")));
            VerificationFacts.note(item, "alt_text-only update post_modified_gmt", modified);
            send(RestCall.json("alice", "DELETE", prefix + "/wp-json/wp/v2/media/" + id + "?force=true", "{}"));
            noteHookLog(prefix, "forced delete");
        } finally {
            option(prefix, "qp_parity_hook_log", "0");
        }
    }

    @Test
    void u15MimeTypeFilters() throws Exception { // MU30
        String prefix = createSite("mu15");
        String path = prefix + "/";
        noteAttachment(
                prefix,
                "u15.jpg",
                upload(prefix, "alice", "u15.jpg", "image/jpeg", MediaFixtures.jpeg(16, 16), noSubSizes()));
        noteAttachment(
                prefix,
                "u15.png",
                upload(prefix, "alice", "u15.png", "image/png", MediaFixtures.png(16, 16), noSubSizes()));
        noteAttachment(
                prefix,
                "u15.mp4",
                upload(prefix, "alice", "u15.mp4", "video/mp4", MediaFixtures.mp4(32, 24, 1), Map.of()));
        noteAttachment(
                prefix,
                "u15.txt",
                upload(prefix, "alice", "u15.txt", "text/plain", MediaFixtures.text("u15 text\n"), Map.of()));
        noteAttachment(
                prefix,
                "u15.pdf",
                upload(prefix, "alice", "u15.pdf", "application/pdf", MediaFixtures.pdf(), Map.of()));
        noteAttachment(
                prefix,
                "u15.xls",
                upload(prefix, "alice", "u15.xls", "application/vnd.ms-excel", MediaFixtures.xls(), Map.of()));
        List<JsonNode> filters = List.of(
                TextNode.valueOf("IMAGE"),
                TextNode.valueOf("image/*,video"),
                OracleJson.MAPPER.createArrayNode().add("image").add(""),
                TextNode.valueOf("image,"),
                TextNode.valueOf("*"),
                TextNode.valueOf("application/vnd.ms-*"),
                TextNode.valueOf("image"),
                TextNode.valueOf("image/jpeg"),
                TextNode.valueOf("IMAGE/JPEG"),
                TextNode.valueOf("%"),
                TextNode.valueOf("image/"));
        ArrayNode answers = OracleJson.MAPPER.createArrayNode();
        for (JsonNode filter : filters) {
            ObjectNode query = args().put("site", path);
            query.putObject("args").set("post_mime_type", filter);
            ObjectNode answer = OracleJson.MAPPER.createObjectNode();
            answer.set("post_mime_type", filter);
            answer.set("posts", site.operation("query_attachments", query).get("posts"));
            answers.add(answer);
        }
        VerificationFacts.note(item, "query_attachments", answers);
    }

    // ---- image-processing reference, Part G -------------------------------------------------------------------

    @Test
    void i01RegisteredSizes() throws Exception { // MI1, MI2
        String prefix = createSite("mi01");
        List<ObjectNode> sizes = List.of(call("wp_get_registered_image_subsizes"));
        noteProbes("fresh", sizes, probes(settings(prefix, null), sizes));
        option(prefix, "medium_size_w", "0");
        option(prefix, "medium_size_h", "0");
        noteProbes("medium 0x0", sizes, probes(settings(prefix, null), sizes));
        ObjectNode withX = settings(prefix, null);
        withX.putArray("add_image_size")
                .add("x")
                .add(100)
                .add(100)
                .add(OracleJson.MAPPER.createArrayNode().add("left").add("top"));
        noteProbes("add_image_size x 100x100 [left, top]", sizes, probes(withX, sizes));
        ObjectNode bad = settings(prefix, null);
        bad.putArray("add_image_size")
                .add("y")
                .add(-50)
                .add(80)
                .add(OracleJson.MAPPER.createArrayNode().add("middle"));
        noteProbes("add_image_size y -50x80 [middle]", sizes, probes(bad, sizes));
        option(prefix, "thumbnail_crop", "0");
        noteProbes("thumbnail_crop 0", sizes, probes(settings(prefix, null), sizes));
    }

    @Test
    void i02DimensionFunctions() throws Exception { // MI4-MI7
        assertThat(constrain(4000, 3000, 300, 300)).isEqualTo("[300,225]");
        assertThat(constrain(597, 600, 300, 300)).isEqualTo("[300,300]");
        assertThat(resize(600, 300, 400, 400, true)).isEqualTo("[0,0,100,0,400,300,400,300]");
        List<ObjectNode> calls = new ArrayList<>();
        int[][] fits = {
            {4000, 3000, 300, 300}, {4000, 3000, 768, 0}, {3000, 4000, 1024, 1024}, {3000, 4000, 768, 0},
            {1200, 800, 1024, 1024}, {597, 600, 300, 300}, {1600, 400, 1536, 1536}, {10000, 10, 300, 300},
            {4000, 3000, 0, 0}, {100, 50, 300, 300}
        };
        for (int[] fit : fits) {
            calls.add(call("wp_constrain_dimensions", fit[0], fit[1], fit[2], fit[3]));
        }
        ArrayNode right = OracleJson.MAPPER.createArrayNode().add("right").add("bottom");
        ArrayNode left = OracleJson.MAPPER.createArrayNode().add("left").add("top");
        calls.add(call("image_resize_dimensions", 4000, 3000, 150, 150, true));
        calls.add(call("image_resize_dimensions", 4000, 3000, 150, 150, right));
        calls.add(call("image_resize_dimensions", 4000, 3000, 150, 150, left));
        calls.add(call("image_resize_dimensions", 600, 300, 400, 400, true));
        calls.add(call("image_resize_dimensions", 200, 100, 150, 150, true));
        calls.add(call("image_resize_dimensions", 1000, 667, 300, 200, true));
        calls.add(call("image_resize_dimensions", 100, 80, 150, 150, true));
        calls.add(call("image_resize_dimensions", 150, 150, 150, 150, true));
        calls.add(call(
                "image_resize_dimensions",
                4000,
                3000,
                150,
                150,
                OracleJson.MAPPER.createArrayNode().add("x")));
        calls.add(call(
                "image_resize_dimensions",
                4000,
                3000,
                150,
                150,
                OracleJson.MAPPER.createArrayNode().add("bogus").add("bogus")));
        calls.add(call("image_resize_dimensions", 0, 3000, 150, 150, true));
        calls.add(call("image_resize_dimensions", 4000, 3000, 0, 0, false));
        calls.add(call("image_resize_dimensions", 1024, 500, 1024, 0, false));
        calls.add(call("image_resize_dimensions", 1000, 500, 1024, 0, false));
        calls.add(call("image_resize_dimensions", 500, 1000, 0, 1024, false));
        int[][] originals = {{4000, 3000}, {3000, 4000}, {1200, 800}, {600, 300}, {301, 200}, {1024, 1024}, {120, 90}};
        Object[][] defaults = {
            {"thumbnail", 150, 150, true}, {"medium", 300, 300, false}, {"medium_large", 768, 0, false},
            {"large", 1024, 1024, false}, {"1536x1536", 1536, 1536, false}, {"2048x2048", 2048, 2048, false}
        };
        for (int[] original : originals) {
            for (Object[] size : defaults) {
                calls.add(call("image_resize_dimensions", original[0], original[1], size[1], size[2], size[3]));
            }
        }
        noteProbes("dimension functions", calls, probes(settings(null, null), calls));
    }

    @Test
    void i03Rounding() throws Exception { // MI5 rule 7, MI40 rule 4
        List<ObjectNode> rounds = new ArrayList<>();
        double[][] values = {
            {1.775, 2},
            {2.675, 2},
            {1.005, 2},
            {0.285, 2},
            {1.115, 2},
            {2.5, 0},
            {-2.5, 0},
            {0.49999999999999994, 0},
            {5.015, 2},
            {1.4999999999999998, 0}
        };
        for (double[] value : values) {
            rounds.add(call("round", value[0], (int) value[1]));
        }
        noteProbes("round", rounds, probes(settings(null, null), rounds));
        // Every landscape original (long side L = 2561..6000, short side S = 1..L) whose exact scaled short side
        // S * 2560 / L ends in .5, fitted into 2560x2560 (the big-image threshold); portrait is the same arithmetic.
        List<int[]> candidates = new ArrayList<>();
        for (int longSide = 2561; longSide <= 6000; longSide++) {
            for (int shortSide = 1; shortSide <= longSide; shortSide++) {
                if (((long) shortSide * 5120) % (2L * longSide) == longSide) {
                    candidates.add(new int[] {longSide, shortSide});
                }
            }
        }
        List<ObjectNode> calls = new ArrayList<>();
        for (int[] c : candidates) {
            calls.add(call("wp_constrain_dimensions", c[0], c[1], 2560, 2560));
        }
        JsonNode answer = probes(settings(null, null), calls);
        ArrayNode disagreements = OracleJson.MAPPER.createArrayNode();
        int agree = 0;
        for (int i = 0; i < candidates.size(); i++) {
            int[] c = candidates.get(i);
            int[] expected = constrainHalfAwayOnDoubles(c[0], c[1], 2560, 2560);
            JsonNode got = answer.get("results").get(i);
            if (got.get(0).asInt() == expected[0] && got.get(1).asInt() == expected[1]) {
                agree++;
            } else {
                ObjectNode d = disagreements.addObject();
                d.put("w0", c[0]).put("h0", c[1]);
                d.set("php", got);
                d.set(
                        "halfAwayFromZeroOnTheDouble",
                        OracleJson.MAPPER.createArrayNode().add(expected[0]).add(expected[1]));
                d.put("double", new BigDecimal(c[1] * (2560.0 / c[0])).toPlainString());
            }
        }
        ObjectNode summary = OracleJson.MAPPER.createObjectNode();
        summary.put("candidates", candidates.size()).put("agree", agree).put("disagree", disagreements.size());
        summary.set("disagreements", disagreements);
        VerificationFacts.note(item, "wp_constrain_dimensions x.5 search into 2560x2560", summary);
    }

    @Test
    void i04ReadImageMetadata() throws Exception { // MI34-MI40
        String prefix = createSite("mi04");
        JpegMeta none = JpegMeta.none();
        Map<String, JpegMeta> cases = new LinkedHashMap<>();
        cases.put("01-headline", none.withIptc(105, "i04 headline"));
        cases.put("02-object-name", none.withIptc(5, "i04 object name"));
        cases.put("03-caption-79", none.withIptc(120, padded("i04 caption ", 79)));
        cases.put("04-caption-80", none.withIptc(120, padded("i04 caption ", 80)));
        cases.put("05-credit-and-byline", none.withIptc(110, "i04 credit").withIptc(80, "i04 by-line"));
        cases.put("06-byline", none.withIptc(80, "i04 by-line"));
        cases.put("07-date-time-zone", none.withIptc(55, "20231015").withIptc(60, "143000+0200"));
        cases.put("08-date-time", none.withIptc(55, "20231015").withIptc(60, "143000"));
        cases.put("09-date-only", none.withIptc(55, "20231015"));
        cases.put("10-keywords", none.withIptc(25, "one", " two ", "three"));
        Map<Integer, List<String>> latin = new LinkedHashMap<>();
        latin.put(0, List.of("ISO-8859-1"));
        latin.put(25, List.of("caf\u00E9", "na\u00EFve"));
        latin.put(120, List.of("Caption \u00E9t\u00E9"));
        latin.put(116, List.of("\u00A9 i04"));
        cases.put("11-latin1", new JpegMeta(Optional.empty(), latin, Map.of(), Optional.empty(), Optional.empty()));
        cases.put("12-description", none.withExif("ImageDescription", "Sunset"));
        cases.put("13-user-comment", none.withExif("UserComment", "Shot at Malibu"));
        cases.put(
                "14-description-comment",
                none.withExif("ImageDescription", "Sunset").withExif("UserComment", "Shot at Malibu"));
        cases.put(
                "15-description-equals-comment",
                none.withExif("ImageDescription", "Same").withExif("UserComment", "Same"));
        cases.put("16-long-description", none.withExif("ImageDescription", padded("i04 description ", 120)));
        cases.put(
                "17-headline-description-comment",
                none.withIptc(105, "Beach")
                        .withExif("ImageDescription", "Sunset")
                        .withExif("UserComment", "Malibu"));
        cases.put("18-comments", none.withExif("Comments", "i04 comments"));
        cases.put("19-fnumber-28-10", none.withExif("FNumber", "28/10"));
        cases.put("20-fnumber-8-1", none.withExif("FNumber", "8/1"));
        cases.put("20b-fnumber-1775", none.withExif("FNumber", "1775/1000"));
        cases.put("21-exposure-1-60", none.withExif("ExposureTime", "1/60"));
        cases.put("22-exposure-1-100000", none.withExif("ExposureTime", "1/100000"));
        cases.put("22b-exposure-1-250", none.withExif("ExposureTime", "1/250"));
        cases.put("23-focal-50-1", none.withExif("FocalLength", "50/1"));
        cases.put("23b-focal-1-3", none.withExif("FocalLength", "1/3"));
        cases.put("24-iso-list", none.withExif("ISOSpeedRatings", "100,200"));
        cases.put("25-iso", none.withExif("ISOSpeedRatings", "400"));
        cases.put("26-orientation-6", none.withOrientation(6));
        cases.put("27-digitized", none.withExif("DateTimeDigitized", "2023:10:15 14:30:00"));
        cases.put("28-original-only", none.withExif("DateTimeOriginal", "2023:10:15 14:30:00"));
        cases.put("29-iptc-caption-html", none.withIptc(120, "<b>bold</b> & co <script>x</script>"));
        cases.put("30-exif-description-html", none.withExif("ImageDescription", "<b>x</b> & y"));
        cases.put("31-artist-copyright", none.withExif("Artist", "i04 artist").withExif("Copyright", "i04 (c)"));
        cases.put("32-model", none.withExif("Model", "i04 camera"));
        cases.put("33-credit-iptc-and-artist", none.withIptc(110, "i04 credit").withExif("Artist", "i04 artist"));
        cases.put("34-none", none);
        List<ObjectNode> calls = new ArrayList<>();
        for (Map.Entry<String, JpegMeta> c : cases.entrySet()) {
            calls.add(call(
                    "wp_read_image_metadata",
                    fileArg("i04-" + c.getKey() + ".jpg", MediaFixtures.jpeg(16, 16, c.getValue()))));
        }
        calls.add(call("wp_read_image_metadata", fileArg("i04-png.png", MediaFixtures.png(16, 16))));
        calls.add(call("wp_read_image_metadata", fileArg("i04-text.jpg", MediaFixtures.text("not an image"))));
        noteProbes("wp_read_image_metadata", calls, probes(settings(prefix, null), calls));
        List<ObjectNode> numbers = new ArrayList<>();
        for (String value : List.of(
                "28/10",
                "8/1",
                "1/60",
                "1/100000",
                "50/1",
                "1/3",
                "abc",
                "5",
                "2.5",
                "0/0",
                "1/0",
                "2/1/3",
                "",
                "-1/2")) {
            numbers.add(call("wp_exif_frac2dec", value));
        }
        for (String value : List.of("2023:10:15 14:30:00", "2023-10-15 14:30:00", "2023:10:15", "garbage", "")) {
            numbers.add(call("wp_exif_date2ts", value));
        }
        noteProbes("wp_exif_frac2dec and wp_exif_date2ts", numbers, probes(settings(prefix, null), numbers));
    }

    @Test
    void i05XmpAlt() throws Exception { // MI37
        assumeTrue(extensions.dom(), "SKIPPED: php8.4-xml (DOM) is not installed");
        String english = createSite("mi05");
        String german = createSite("mi05de");
        // Harness rule: the German site's locale is its WPLANG option.
        option(german, "WPLANG", "de_DE");
        Map<String, byte[]> files = new LinkedHashMap<>();
        files.put(
                "i05-en.jpg",
                MediaFixtures.jpeg(
                        16,
                        16,
                        JpegMeta.none()
                                .withXmp(xmpAlt(ordered(
                                        "x-default",
                                        "alt x-default",
                                        "en",
                                        "alt en",
                                        "en-US",
                                        "alt en-US",
                                        "de",
                                        "alt de",
                                        "de-DE",
                                        "alt de-DE")))));
        files.put(
                "i05-underscore.jpg",
                MediaFixtures.jpeg(
                        16,
                        16,
                        JpegMeta.none()
                                .withXmp(xmpAlt(ordered(
                                        "x-default", "alt x-default", "en_US", "alt en_US", "de_DE", "alt de_DE")))));
        files.put(
                "i05-default.jpg",
                MediaFixtures.jpeg(16, 16, JpegMeta.none().withXmp(xmpAlt(Map.of("x-default", "alt x-default")))));
        files.put(
                "i05-attribute.jpg", MediaFixtures.jpeg(16, 16, JpegMeta.none().withXmp(XMP_ATTRIBUTE_FORM)));
        files.put(
                "i05-invalid.jpg",
                MediaFixtures.jpeg(
                        16,
                        16,
                        JpegMeta.none()
                                .withXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF><unclosed></x:xmpmeta>")));
        files.put("i05.png", MediaFixtures.png(16, 16, Optional.of(xmpAlt(Map.of("x-default", "png alt")))));
        files.put(
                "i05-html.jpg",
                MediaFixtures.jpeg(
                        16,
                        16,
                        JpegMeta.none()
                                .withXmp(xmpAlt(Map.of("x-default", "  alt &lt;b&gt;bold&lt;/b&gt; &amp; co  ")))));
        List<ObjectNode> calls = new ArrayList<>();
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            calls.add(call("wp_read_image_metadata", fileArg(file.getKey(), file.getValue())));
        }
        noteProbes("en_US site", calls, probes(settings(english, null), calls));
        noteProbes("de_DE site", calls, probes(settings(german, null), calls));
    }

    @Test
    void i06RefusalByDeclaredType() throws Exception { // MI45
        String prefix = createSite("mi06");
        upload(prefix, "alice", "i06.jpg", "image/jpeg", MediaFixtures.jpeg(16, 16), Map.of());
        try {
            option(prefix, "qp_parity_allow_svg", "1");
            upload(prefix, "alice", "i06.svg", "image/svg+xml", svg(), Map.of());
        } finally {
            option(prefix, "qp_parity_allow_svg", "0");
        }
        upload(prefix, "alice", "i06.heic", "image/heic", MediaFixtures.heif("heic"), Map.of());
        upload(prefix, "alice", "i06.heics", "image/heic-sequence", MediaFixtures.heif("msf1"), Map.of());
        upload(prefix, "alice", "i06.bmp", "image/bmp", MediaFixtures.bmp(16, 16), Map.of());
        upload(prefix, "alice", "i06.pdf", "application/pdf", MediaFixtures.pdf(), Map.of());
        upload(prefix, "alice", "i06-octet.jpg", "application/octet-stream", MediaFixtures.jpeg(16, 16), Map.of());
        upload(prefix, "alice", "i06-declared-png.jpg", "image/png", MediaFixtures.jpeg(16, 16), Map.of());
        upload(prefix, "alice", "i06-webp.webp", "image/webp", MediaFixtures.webp(), Map.of());
        upload(prefix, "alice", "i06-avif.avif", "image/avif", MediaFixtures.heif("avif"), Map.of());
        VerificationFacts.note(
                item,
                "non-secure host",
                TextNode.valueOf("not reproducible: the harness router presents every request as one to localhost"));
    }

    @Test
    void i07MetadataWithoutAnEditor() throws Exception { // MI20 rule 3, MI31 rule 5, MI32
        String prefix = createSite("mi07");
        VerificationFacts.note(item, "extensions", OracleJson.MAPPER.valueToTree(extensions));
        noteWithRestView(
                prefix,
                "i07.heic",
                upload(prefix, "alice", "i07.heic", "image/heic", MediaFixtures.heif("heic"), Map.of()));
        noteWithRestView(
                prefix,
                "i07.avif",
                upload(prefix, "alice", "i07.avif", "image/avif", MediaFixtures.heif("avif"), noSubSizes()));
        try {
            option(prefix, "qp_parity_no_unsupported_refusal", "1");
            noteWithRestView(
                    prefix,
                    "i07.tif",
                    upload(prefix, "alice", "i07.tif", "image/tiff", MediaFixtures.tiff(32, 24), Map.of()));
            noteWithRestView(
                    prefix,
                    "i07-refusal-off.jpg",
                    upload(
                            prefix,
                            "alice",
                            "i07-refusal-off.jpg",
                            "image/jpeg",
                            MediaFixtures.jpeg(1200, 800),
                            Map.of()));
        } finally {
            option(prefix, "qp_parity_no_unsupported_refusal", "0");
        }
        noteWithRestView(
                prefix,
                "i07.pdf",
                upload(prefix, "alice", "i07.pdf", "application/pdf", MediaFixtures.pdf(), Map.of()));
        noteWithRestView(
                prefix,
                "i07.webp",
                upload(prefix, "alice", "i07.webp", "image/webp", MediaFixtures.webp(), noSubSizes()));
        noteWithRestView(
                prefix,
                "i07-no-sizes.jpg",
                upload(
                        prefix,
                        "alice",
                        "i07-no-sizes.jpg",
                        "image/jpeg",
                        MediaFixtures.jpeg(4000, 3000),
                        noSubSizes()));
    }

    @Test
    void i08AudioVideoMetadata() throws Exception { // MI29, MI30
        String prefix = createSite("mi08");
        String path = prefix + "/";
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("title", "i08 song");
        tags.put("artist", "i08 artist");
        tags.put("album", "i08 album");
        tags.put("year", "2024");
        tags.put("track_number", "3/9");
        tags.put("genre", "Jazz");
        byte[] mp3 = MediaFixtures.mp3(tags, Optional.of(MediaFixtures.jpeg(32, 32)));
        noteWithRestView(prefix, "i08-song.mp3", upload(prefix, "alice", "i08-song.mp3", "audio/mpeg", mp3, Map.of()));
        noteImages(path, "images after the first mp3");
        noteWithRestView(
                prefix,
                "i08-song-again.mp3",
                upload(prefix, "alice", "i08-song-again.mp3", "audio/mpeg", mp3, Map.of()));
        noteImages(path, "images after the second mp3");
        noteWithRestView(
                prefix,
                "i08-bare.mp3",
                upload(
                        prefix,
                        "alice",
                        "i08-bare.mp3",
                        "audio/mpeg",
                        MediaFixtures.mp3(Map.of(), Optional.empty()),
                        Map.of()));
        noteWithRestView(
                prefix,
                "i08.mp4",
                upload(prefix, "alice", "i08.mp4", "video/mp4", MediaFixtures.mp4(64, 48, 2), Map.of()));
        noteWithRestView(
                prefix, "i08.webm", upload(prefix, "alice", "i08.webm", "video/webm", MediaFixtures.webm(), Map.of()));
        noteWithRestView(
                prefix, "i08.m4a", upload(prefix, "alice", "i08.m4a", "audio/mpeg", MediaFixtures.m4a(), Map.of()));
        noteWithRestView(
                prefix, "i08.wav", upload(prefix, "alice", "i08.wav", "audio/wav", MediaFixtures.wav(2), Map.of()));
        noteWithRestView(
                prefix, "i08.ogg", upload(prefix, "alice", "i08.ogg", "audio/ogg", MediaFixtures.ogg(), Map.of()));
    }

    @Test
    void i09SizeTableEndToEnd() throws Exception { // MI7, MI3, MI9 [GD]
        requireEditor();
        String prefix = createSite("mi09");
        int[][] originals = {{4000, 3000}, {3000, 4000}, {1200, 800}, {600, 300}, {301, 200}, {1024, 1024}, {120, 90}};
        for (int[] original : originals) {
            String name = "i09-" + original[0] + "x" + original[1] + ".jpg";
            noteWithRestView(
                    prefix,
                    name,
                    upload(
                            prefix,
                            "alice",
                            name,
                            "image/jpeg",
                            MediaFixtures.jpeg(original[0], original[1]),
                            Map.of()));
        }
    }

    @Test
    void i10BigImageThreshold() throws Exception { // MI13, MI14 [GD]
        requireEditor();
        String prefix = createSite("mi10");
        int[][] jpegs = {{6000, 4000}, {2561, 2561}, {2561, 1000}, {2560, 2560}};
        for (int[] size : jpegs) {
            String name = "i10-" + size[0] + "x" + size[1] + ".jpg";
            noteWithRestView(
                    prefix,
                    name,
                    upload(prefix, "alice", name, "image/jpeg", MediaFixtures.jpeg(size[0], size[1]), Map.of()));
        }
        noteWithRestView(
                prefix,
                "i10-3000x3000.png",
                upload(prefix, "alice", "i10-3000x3000.png", "image/png", MediaFixtures.png(3000, 3000), Map.of()));
        noteWithRestView(
                prefix,
                "i10-animated.gif",
                upload(prefix, "alice", "i10-animated.gif", "image/gif", MediaFixtures.gif(3000, 2000, 2), Map.of()));
        try {
            option(prefix, "qp_parity_no_big_image_threshold", "1");
            noteWithRestView(
                    prefix,
                    "i10-no-threshold.jpg",
                    upload(
                            prefix,
                            "alice",
                            "i10-no-threshold.jpg",
                            "image/jpeg",
                            MediaFixtures.jpeg(6000, 4000),
                            Map.of()));
        } finally {
            option(prefix, "qp_parity_no_big_image_threshold", "0");
        }
    }

    @Test
    void i11ExifOrientation() throws Exception { // MI16-MI18 [GD]
        requireEditor();
        String prefix = createSite("mi11");
        for (int orientation = 1; orientation <= 9; orientation++) {
            String name = "i11-o" + orientation + ".jpg";
            noteWithRestView(
                    prefix,
                    name,
                    upload(
                            prefix,
                            "alice",
                            name,
                            "image/jpeg",
                            MediaFixtures.jpeg(1000, 800, JpegMeta.none().withOrientation(orientation)),
                            Map.of()));
        }
        for (int orientation : new int[] {6, 8}) {
            String name = "i11-big-o" + orientation + ".jpg";
            noteWithRestView(
                    prefix,
                    name,
                    upload(
                            prefix,
                            "alice",
                            name,
                            "image/jpeg",
                            MediaFixtures.jpeg(4000, 3000, JpegMeta.none().withOrientation(orientation)),
                            Map.of()));
        }
        noteWithRestView(
                prefix,
                "i11-exif.png",
                upload(
                        prefix,
                        "alice",
                        "i11-exif.png",
                        "image/png",
                        MediaFixtures.pngExifOrientation(1000, 800, 6),
                        Map.of()));
    }

    @Test
    void i11zImagickHalf() {
        assumeTrue(false, IMAGICK_SKIP);
    }

    @Test
    void i12HeicConversion() {
        assumeTrue(false, IMAGICK_SKIP);
    }

    @Test
    void i13EncodeQuality() throws Exception { // MI11 [GD]
        requireEditor();
        String prefix = createSite("mi13");
        noteWithRestView(
                prefix,
                "i13.jpg",
                upload(prefix, "alice", "i13.jpg", "image/jpeg", MediaFixtures.jpeg(1200, 800), Map.of()));
        noteWithRestView(
                prefix, "i13.webp", upload(prefix, "alice", "i13.webp", "image/webp", MediaFixtures.webp(), Map.of()));
        noteWithRestView(
                prefix,
                "i13.avif",
                upload(prefix, "alice", "i13.avif", "image/avif", MediaFixtures.heif("avif"), Map.of()));
        VerificationFacts.note(
                item,
                "written quality",
                TextNode.valueOf("unobservable here: the JPEG quality of a written file needs an external tool"
                        + " (identify); the lossless WebP fixture is smaller than every size, so no WebP is written"));
    }

    @Test
    void i13zImagickHalf() {
        assumeTrue(false, IMAGICK_SKIP);
    }

    @Test
    void i14SubSizeMetadata() throws Exception { // MI12 rule 2 [GD]
        requireEditor();
        String prefix = createSite("mi14");
        JpegMeta meta = JpegMeta.none()
                .withOrientation(1)
                .withExif("Model", "i14 camera")
                .withIptc(105, "i14 headline")
                .withXmp(xmpAlt(Map.of("x-default", "i14 alt")))
                .withIcc(fakeIccProfile());
        Exchange tagged =
                upload(prefix, "alice", "i14.jpg", "image/jpeg", MediaFixtures.jpeg(1200, 900, meta), Map.of());
        noteWithRestView(prefix, "i14.jpg", tagged);
        noteSizeFile(tagged, "medium", "tagged medium");
        Exchange progressive = upload(
                prefix,
                "alice",
                "i14-progressive.jpg",
                "image/jpeg",
                MediaFixtures.progressiveJpeg(1200, 900),
                Map.of());
        noteWithRestView(prefix, "i14-progressive.jpg", progressive);
        noteSizeFile(progressive, "medium", "progressive medium");
    }

    @Test
    void i14zImagickHalf() {
        assumeTrue(false, IMAGICK_SKIP);
    }

    @Test
    void i15PartialSizesAndPostProcess() throws Exception { // MI23, MI26-MI28 [GD]
        requireEditor();
        String prefix = createSite("mi15");
        String path = prefix + "/";
        Exchange crashed;
        try {
            option(prefix, "qp_parity_fail_on_second_size", "1");
            crashed = upload(prefix, "alice", "i15.jpg", "image/jpeg", MediaFixtures.jpeg(1200, 800), Map.of());
        } finally {
            option(prefix, "qp_parity_fail_on_second_size", "0");
        }
        Optional<String> header = crashed.header("X-WP-Upload-Attachment-ID");
        VerificationFacts.note(item, "X-WP-Upload-Attachment-ID", TextNode.valueOf(header.orElse("(absent)")));
        if (header.isEmpty()) {
            return;
        }
        long id = Long.parseLong(header.get().trim());
        VerificationFacts.note(
                item,
                "after the crash",
                site.operation("attachment", args().put("site", path).put("id", id)));
        send(RestCall.get("alice", prefix + "/wp-json/wp/v2/media/" + id + "?context=edit"));
        send(RestCall.json(
                "alice",
                "POST",
                prefix + "/wp-json/wp/v2/media/" + id + "/post-process",
                "{\"action\":\"create-image-subsizes\"}"));
        VerificationFacts.note(
                item,
                "after post-process",
                site.operation("attachment", args().put("site", path).put("id", id)));
        String file = site.operation("attachment", args().put("site", path).put("id", id))
                .get("meta")
                .get("_wp_attached_file")
                .get(0)
                .asText();
        // The metadata row is deleted (delete_meta) and the attached file removed, as Part G item 15 describes.
        site.operation("delete_meta", args().put("site", path).put("post", id).put("key", "_wp_attachment_metadata"));
        site.operation("remove_upload", args().put("site", path).put("file", file));
        send(RestCall.json(
                "alice",
                "POST",
                prefix + "/wp-json/wp/v2/media/" + id + "/post-process",
                "{\"action\":\"create-image-subsizes\"}"));
        VerificationFacts.note(
                item,
                "after post-process without file and metadata",
                site.operation("attachment", args().put("site", path).put("id", id)));
    }

    @Test
    void i16RotatedOriginalQuirk() throws Exception { // MI19 rule 3 [GD]
        requireEditor();
        String prefix = createSite("mi16");
        try {
            option(prefix, "qp_parity_extra_sizes", "900");
            Exchange uploaded = upload(
                    prefix,
                    "alice",
                    "i16.jpg",
                    "image/jpeg",
                    MediaFixtures.jpeg(1000, 700, JpegMeta.none().withOrientation(6)),
                    Map.of());
            noteWithRestView(prefix, "i16.jpg", uploaded);
            if (uploaded.status() == 201) {
                long id = body(uploaded).get("id").longValue();
                send(RestCall.json(
                        "alice",
                        "POST",
                        prefix + "/wp-json/wp/v2/media/" + id + "/post-process",
                        "{\"action\":\"create-image-subsizes\"}"));
                send(RestCall.get("alice", prefix + "/wp-json/wp/v2/media/" + id + "?context=edit"));
            }
        } finally {
            option(prefix, "qp_parity_extra_sizes", "");
        }
    }

    @Test
    void i17SameDimensionsDifferentGravity() throws Exception { // MI9 rule 4 [GD]
        requireEditor();
        String prefix = createSite("mi17");
        try {
            option(prefix, "qp_parity_extra_sizes", "gravity");
            Exchange uploaded =
                    upload(prefix, "alice", "i17.png", "image/png", MediaFixtures.halvesPng(400, 200), Map.of());
            noteWithRestView(prefix, "i17.png", uploaded);
            noteSizeFile(uploaded, "qp-left", "qp-left file");
            noteSizeFile(uploaded, "qp-right", "qp-right file");
        } finally {
            option(prefix, "qp_parity_extra_sizes", "");
        }
    }

    @Test
    void i18PdfPreview() {
        assumeTrue(false, IMAGICK_SKIP);
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    static final String DOCX = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    static final String XMP_ATTRIBUTE_FORM = "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
            + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
            + " xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\""
            + " Iptc4xmpCore:AltTextAccessibility=\"attribute alt\"/></rdf:RDF></x:xmpmeta>";

    /** Skips the item without GD, or fails it under {@code -Dwp.media.require-editor=true}. */
    static void requireEditor() {
        if (extensions.gd()) {
            return;
        }
        if (Boolean.getBoolean("wp.media.require-editor")) {
            fail("php8.4-gd is required (-Dwp.media.require-editor=true) but the server's PHP has no GD");
        }
        assumeTrue(false, GD_SKIP);
    }

    /** Creates the item's subdirectory site {@code /<id>/} with empty uploads; answers its URL prefix {@code /<id>}. */
    String createSite(String id) throws Exception {
        String path = "/" + id + "/";
        site.operation("create_site", args().put("path", path).put("title", id));
        sites.add(path);
        site.operation("clear_uploads", args().put("site", path));
        return "/" + id;
    }

    /** A multipart upload to the site's media route, recorded with the file part summarised. */
    Exchange upload(String prefix, String as, String name, String type, byte[] bytes, Map<String, String> fields)
            throws Exception {
        RestCall call = RestCall.multipart(as, prefix + "/wp-json/wp/v2/media", name, type, bytes, fields);
        Exchange exchange = client.send(call);
        String summary = "multipart: file=\"" + name + "\" type=" + type + " bytes=" + bytes.length + " sha256="
                + sha256(bytes) + " fields=" + OracleJson.MAPPER.writeValueAsString(fields);
        RestCall recorded = new RestCall(
                call.as(),
                call.method(),
                call.target(),
                call.headers(),
                call.contentType(),
                Optional.of(summary.getBytes(StandardCharsets.UTF_8)));
        if (item != null) {
            VerificationFacts.record(
                    item,
                    new Exchange(recorded, exchange.status(), exchange.headers(), exchange.body(), exchange.sentAt()));
        }
        return exchange;
    }

    static Map<String, String> noSubSizes() {
        return Map.of("generate_sub_sizes", "false");
    }

    /**
     * The exchange's JSON body; text before the first {@code {} or {@code [} (PHP warnings WordPress printed into the
     * response) is skipped and noted under the item.
     */
    JsonNode body(Exchange exchange) {
        String text = exchange.text();
        int start = 0;
        while (start < text.length() && text.charAt(start) != '{' && text.charAt(start) != '[') {
            start++;
        }
        if (start > 0 && item != null) {
            VerificationFacts.note(
                    item,
                    "harness: non-JSON output before the body of "
                            + exchange.call().target() + " at " + exchange.sentAt(),
                    TextNode.valueOf(text.substring(0, start)));
        }
        try {
            return OracleJson.MAPPER.readTree(text.substring(start));
        } catch (java.io.IOException e) {
            throw new AssertionError("not JSON: " + text, e);
        }
    }

    /** Sends the call and records the exchange under the running item. */
    Exchange send(RestCall call) throws Exception {
        Exchange exchange = client.send(call);
        if (item != null) {
            VerificationFacts.record(item, exchange);
        }
        return exchange;
    }

    /** Notes the stored attachment (rest-setup.php {@code attachment}) of a 201 upload, or the refusal's status. */
    void noteAttachment(String prefix, String label, Exchange uploaded) throws Exception {
        if (uploaded.status() != 201) {
            ObjectNode refused = OracleJson.MAPPER.createObjectNode();
            refused.put("status", uploaded.status());
            refused.put("body", uploaded.text());
            VerificationFacts.note(item, "upload " + label, refused);
            return;
        }
        long id = body(uploaded).get("id").longValue();
        VerificationFacts.note(
                item,
                "upload " + label,
                site.operation("attachment", args().put("site", prefix + "/").put("id", id)));
    }

    /** {@link #noteAttachment}, then the attachment's REST view in edit context. */
    void noteWithRestView(String prefix, String label, Exchange uploaded) throws Exception {
        noteAttachment(prefix, label, uploaded);
        if (uploaded.status() == 201) {
            send(RestCall.get(
                    "alice",
                    prefix + "/wp-json/wp/v2/media/" + body(uploaded).get("id").asLong() + "?context=edit"));
        }
    }

    /** Fetches a size's file over HTTP and notes what it holds (markers, dimensions, average colour). */
    void noteSizeFile(Exchange uploaded, String size, String key) throws Exception {
        JsonNode url = uploaded.status() == 201
                ? body(uploaded).path("media_details").path("sizes").path(size).path("source_url")
                : null;
        if (url == null || !url.isTextual()) {
            VerificationFacts.note(item, key, TextNode.valueOf("(no such size)"));
            return;
        }
        String target = uploadPath(url.asText());
        Exchange file = client.send(RestCall.get("anonymous", target));
        byte[] bytes = file.body();
        assertThat(file.status()).as("GET %s", target).isEqualTo(200);
        ObjectNode facts = OracleJson.MAPPER.createObjectNode();
        facts.put("url", url.asText()).put("status", file.status()).put("bytes", bytes.length);
        facts.put("exifApp1", indexOf(bytes, "Exif\0\0".getBytes(StandardCharsets.ISO_8859_1)) >= 0);
        facts.put("iccApp2", indexOf(bytes, "ICC_PROFILE".getBytes(StandardCharsets.ISO_8859_1)) >= 0);
        facts.put("xmp", indexOf(bytes, "<x:xmpmeta".getBytes(StandardCharsets.ISO_8859_1)) >= 0);
        facts.put("iptc", indexOf(bytes, "Photoshop 3.0".getBytes(StandardCharsets.ISO_8859_1)) >= 0);
        facts.put("jpegFrameMarker", jpegFrameMarker(bytes));
        BufferedImage image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertThat(image).as("an image at %s", target).isNotNull();
        facts.put("width", image.getWidth()).put("height", image.getHeight());
        long red = 0;
        long blue = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                int rgb = image.getRGB(x, y);
                red += (rgb >> 16) & 0xFF;
                blue += rgb & 0xFF;
            }
        }
        long pixels = (long) image.getWidth() * image.getHeight();
        facts.put("averageRed", red / pixels).put("averageBlue", blue / pixels);
        VerificationFacts.note(item, key, facts);
    }

    /**
     * The server path of an upload URL: {@code http://localhost} dropped and, harness rule mirroring WordPress's
     * multisite rewrite (which the php -S router does not apply), a leading {@code /<site>} before
     * {@code /wp-content/} dropped too, so the file itself is fetched.
     */
    static String uploadPath(String url) {
        String path = url.startsWith("http://localhost") ? url.substring("http://localhost".length()) : url;
        int content = path.indexOf("/wp-content/");
        return content > 0 ? path.substring(content) : path;
    }

    /**
     * The start-of-frame marker of a JPEG, read by walking the marker segments from SOI up to SOS: {@code "SOF0"}
     * (baseline), {@code "SOF2"} (progressive), another {@code "SOFn"}, or {@code "none"} (not a JPEG, or no frame
     * before SOS).
     */
    static String jpegFrameMarker(byte[] bytes) {
        if (bytes.length < 4 || (bytes[0] & 0xFF) != 0xFF || (bytes[1] & 0xFF) != 0xD8) {
            return "none";
        }
        int at = 2;
        while (at + 4 <= bytes.length) {
            if ((bytes[at] & 0xFF) != 0xFF) {
                return "none";
            }
            int marker = bytes[at + 1] & 0xFF;
            if (marker == 0xFF) {
                at++;
                continue;
            }
            if (marker == 0xDA) {
                return "none";
            }
            if (marker >= 0xC0 && marker <= 0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                return "SOF" + (marker - 0xC0);
            }
            int length = ((bytes[at + 2] & 0xFF) << 8) | (bytes[at + 3] & 0xFF);
            at += 2 + length;
        }
        return "none";
    }

    void noteImages(String path, String key) throws Exception {
        ObjectNode query = args().put("site", path);
        query.putObject("args").put("post_mime_type", "image");
        JsonNode posts = site.operation("query_attachments", query).get("posts");
        ArrayNode images = OracleJson.MAPPER.createArrayNode();
        for (JsonNode post : posts) {
            images.add(site.operation(
                    "attachment",
                    args().put("site", path).put("id", post.get("id").asLong())));
        }
        VerificationFacts.note(item, key, images);
    }

    void clearHookLog(String prefix) throws Exception {
        ObjectNode input = args().put("name", "qp_parity_hooks").put("site", prefix + "/");
        input.putArray("value");
        site.operation("option", input);
    }

    void noteHookLog(String prefix, String key) throws Exception {
        VerificationFacts.note(
                item,
                "hooks: " + key,
                site.operation("get_option", args().put("site", prefix + "/").put("name", "qp_parity_hooks"))
                        .get("value"));
        clearHookLog(prefix);
    }

    void option(String prefix, String name, String value) throws Exception {
        site.operation(
                "option", args().put("site", prefix + "/").put("name", name).put("value", value));
    }

    void setNetworkOption(String name, JsonNode value) throws Exception {
        ObjectNode input = args().put("name", name);
        input.set("value", value == null || value.isMissingNode() ? NullNode.instance : value);
        site.operation("set_network_option", input);
    }

    void noteNetworkOptions(String key, List<String> names) throws Exception {
        ObjectNode values = OracleJson.MAPPER.createObjectNode();
        for (String name : names) {
            values.set(
                    name,
                    site.operation("network_option", args().put("name", name)).get("value"));
        }
        VerificationFacts.note(item, key, values);
    }

    long insert(String sitePath, String as, String type, String status, String title, ObjectNode fields)
            throws Exception {
        ObjectNode input = args().put("site", sitePath)
                .put("as", as)
                .put("type", type)
                .put("status", status)
                .put("title", title);
        input.set("fields", fields);
        return site.operation("post", input).get("id").longValue();
    }

    void setMeta(String sitePath, long post, String key, JsonNode value) throws Exception {
        ObjectNode input = args().put("site", sitePath).put("post", post).put("key", key);
        input.set("value", value);
        site.operation("set_meta", input);
    }

    void noteMeta(String key, String sitePath, long post, String metaKey) throws Exception {
        VerificationFacts.note(
                item,
                key,
                site.operation(
                                "meta",
                                args().put("site", sitePath).put("post", post).put("key", metaKey))
                        .get("values"));
    }

    /** The probe's single answer as text (a JSON string's value, else the compact JSON). */
    String probe(String fn, Object... arguments) throws Exception {
        ObjectNode input = call(fn, arguments);
        input.put("as", "alice");
        JsonNode answer = site.operation("probe", input);
        JsonNode result = answer.get("result");
        String text = result.isTextual() ? result.textValue() : OracleJson.MAPPER.writeValueAsString(result);
        if (item != null) {
            ObjectNode entry = OracleJson.MAPPER.createObjectNode();
            entry.set("args", input.get("args"));
            entry.set("result", result);
            entry.set("type", answer.path("result_type"));
            VerificationFacts.note(item, "firm " + fn + " " + input.get("args"), entry);
        }
        return text;
    }

    String constrain(int w0, int h0, int maxW, int maxH) throws Exception {
        return probe("wp_constrain_dimensions", w0, h0, maxW, maxH);
    }

    String resize(int w0, int h0, int maxW, int maxH, Object crop) throws Exception {
        return probe("image_resize_dimensions", w0, h0, maxW, maxH, crop);
    }

    /** Settings of a batch probe: the site (a URL prefix, or null for the main site) and the acting user. */
    static ObjectNode settings(String prefix, String as) {
        ObjectNode settings = args();
        if (prefix != null) {
            settings.put("site", prefix + "/");
        }
        if (as != null) {
            settings.put("as", as);
        }
        return settings;
    }

    JsonNode probes(ObjectNode settings, List<ObjectNode> calls) throws Exception {
        ObjectNode input = settings.deepCopy();
        ArrayNode list = input.putArray("calls");
        calls.forEach(list::add);
        return site.operation("probe", input);
    }

    /** Notes each call (file arguments summarised) with its result, plus the locale the probe ran under. */
    void noteProbes(String key, List<ObjectNode> calls, JsonNode answer) {
        ObjectNode note = OracleJson.MAPPER.createObjectNode();
        note.set("locale", answer.path("locale"));
        if (answer.has("switch_locale")) {
            note.set("switch_locale", answer.get("switch_locale"));
        }
        ArrayNode results = note.putArray("calls");
        for (int i = 0; i < calls.size(); i++) {
            ObjectNode entry = results.addObject();
            entry.put("fn", calls.get(i).get("fn").asText());
            ArrayNode summarised = entry.putArray("args");
            for (JsonNode argument : calls.get(i).path("args")) {
                if (argument.isObject() && argument.has("base64")) {
                    byte[] bytes =
                            Base64.getDecoder().decode(argument.get("base64").asText());
                    summarised
                            .addObject()
                            .put("file", argument.get("name").asText())
                            .put("bytes", bytes.length)
                            .put("sha256", sha256(bytes));
                } else {
                    summarised.add(argument);
                }
            }
            entry.set("result", answer.path("results").path(i));
            entry.set("type", answer.path("result_types").path(i));
        }
        VerificationFacts.note(item, key, note);
    }

    static ObjectNode call(String fn, Object... arguments) {
        ObjectNode call = args().put("fn", fn);
        ArrayNode list = call.putArray("args");
        for (Object argument : arguments) {
            list.add(json(argument));
        }
        return call;
    }

    static ObjectNode fileArg(String name, byte[] bytes) {
        return args().put("name", name).put("base64", Base64.getEncoder().encodeToString(bytes));
    }

    static JsonNode json(Object value) {
        if (value == null) {
            return NullNode.instance;
        }
        if (value instanceof JsonNode node) {
            return node;
        }
        if (value instanceof String text) {
            return TextNode.valueOf(text);
        }
        if (value instanceof Integer number) {
            return IntNode.valueOf(number);
        }
        if (value instanceof Long number) {
            return LongNode.valueOf(number);
        }
        if (value instanceof Double number) {
            return DoubleNode.valueOf(number);
        }
        if (value instanceof Boolean flag) {
            return BooleanNode.valueOf(flag);
        }
        throw new IllegalArgumentException("not a probe argument: " + value);
    }

    static ObjectNode args() {
        return OracleJson.MAPPER.createObjectNode();
    }

    static ObjectNode fields() {
        return OracleJson.MAPPER.createObjectNode();
    }

    static ObjectNode sizeEntry(String file, int width, int height) {
        ObjectNode entry = OracleJson.MAPPER.createObjectNode();
        entry.put("file", file).put("width", width).put("height", height).put("mime-type", "image/jpeg");
        entry.put("filesize", 100);
        return entry;
    }

    /**
     * MI5 with every round taken half away from zero on the exact value of the double product (the reference's
     * Java reading), for comparison with PHP's answer.
     */
    static int[] constrainHalfAwayOnDoubles(int w0, int h0, int maxW, int maxH) {
        double rw = 1.0;
        double rh = 1.0;
        boolean didWidth = false;
        boolean didHeight = false;
        if (maxW > 0 && w0 > maxW) {
            rw = (double) maxW / w0;
            didWidth = true;
        }
        if (maxH > 0 && h0 > maxH) {
            rh = (double) maxH / h0;
            didHeight = true;
        }
        double small = Math.min(rw, rh);
        double large = Math.max(rw, rh);
        double ratio = halfAway(w0 * large) <= maxW && halfAway(h0 * large) <= maxH ? large : small;
        int w = Math.max(1, halfAway(w0 * ratio));
        int h = Math.max(1, halfAway(h0 * ratio));
        if (didWidth && w == maxW - 1) {
            w = maxW;
        }
        if (didHeight && h == maxH - 1) {
            h = maxH;
        }
        return new int[] {w, h};
    }

    static int halfAway(double value) {
        return new BigDecimal(value).setScale(0, RoundingMode.HALF_UP).intValueExact();
    }

    static String md5Of(JsonNode listing, String name) {
        for (JsonNode entry : listing) {
            String path = entry.path("path").asText();
            if (path.equals(name) || path.endsWith("/" + name)) {
                return entry.path("md5").asText();
            }
        }
        return "(absent)";
    }

    static String cyrillicPhoto() {
        return new String(new int[] {0x0424, 0x043E, 0x0442, 0x043E}, 0, 4);
    }

    /** {@code start} padded with {@code x} to exactly {@code bytes} ASCII bytes. */
    static String padded(String start, int bytes) {
        return start + "x".repeat(bytes - start.length());
    }

    static Map<String, String> ordered(String... pairs) {
        Map<String, String> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put(pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /** An XMP packet with Iptc4xmpCore:AltTextAccessibility as an rdf:Alt of the given languages, in order. */
    static String xmpAlt(Map<String, String> languages) {
        StringBuilder items = new StringBuilder();
        languages.forEach((lang, text) -> items.append("<rdf:li xml:lang=\"")
                .append(lang)
                .append("\">")
                .append(text)
                .append("</rdf:li>"));
        return "<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                + " xmlns:Iptc4xmpCore=\"http://iptc.org/std/Iptc4xmpCore/1.0/xmlns/\">"
                + "<Iptc4xmpCore:AltTextAccessibility><rdf:Alt>" + items
                + "</rdf:Alt></Iptc4xmpCore:AltTextAccessibility></rdf:Description></rdf:RDF></x:xmpmeta>";
    }

    static byte[] svg() {
        return MediaFixtures.text("<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\">"
                + "<rect width=\"10\" height=\"10\" fill=\"red\"/></svg>\n");
    }

    static byte[] filler(int bytes) {
        byte[] out = new byte[bytes];
        for (int i = 0; i < bytes; i++) {
            out[i] = (byte) (i % 64 == 63 ? '\n' : 'a' + (i % 26));
        }
        return out;
    }

    static byte[] gzip(byte[] content) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        try (java.util.zip.GZIPOutputStream gzip = new java.util.zip.GZIPOutputStream(out)) {
            gzip.write(content);
        }
        return out.toByteArray();
    }

    /** A RAR 4 signature, a main archive header and the end-of-archive block (an empty archive). */
    static byte[] rar() {
        return new byte[] {
            0x52,
            0x61,
            0x72,
            0x21,
            0x1A,
            0x07,
            0x00, // signature
            (byte) 0xCF,
            (byte) 0x90,
            0x73,
            0x00,
            0x00,
            0x0D,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00,
            0x00, // main header
            (byte) 0xC4,
            0x3D,
            0x7B,
            0x00,
            0x40,
            0x07,
            0x00 // end of archive
        };
    }

    /** "fLaC" and a STREAMINFO block (44.1 kHz, stereo, 16 bits, no samples) marked last. */
    static byte[] flac() {
        byte[] info = new byte[34];
        info[0] = 0x10; // min block size 4096
        info[2] = 0x10; // max block size 4096
        // sample rate 44100 (20 bits), channels-1 = 1 (3 bits), bits-1 = 15 (5 bits), total samples 0 (36 bits)
        info[10] = (byte) 0x0A;
        info[11] = (byte) 0xC4;
        info[12] = (byte) 0x42;
        info[13] = (byte) 0xF0;
        byte[] out = new byte[4 + 4 + info.length];
        out[0] = 'f';
        out[1] = 'L';
        out[2] = 'a';
        out[3] = 'C';
        out[4] = (byte) 0x80; // last block, type STREAMINFO
        out[7] = 34;
        System.arraycopy(info, 0, out, 8, info.length);
        return out;
    }

    /** 128 bytes shaped like an ICC header ("acsp" at 36); no colour management reads it. */
    static byte[] fakeIccProfile() {
        byte[] profile = new byte[132];
        profile[3] = (byte) 132;
        byte[] acsp = "acsp".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(acsp, 0, profile, 36, 4);
        return profile;
    }

    static int indexOf(byte[] haystack, byte[] needle) {
        outer:
        for (int i = 0; i + needle.length <= haystack.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (haystack[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
