package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.awt.color.ColorSpace;
import java.awt.color.ICC_Profile;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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
 * Records what WordPress 7.1.3 with Imagick answers for the libvips slice's facts {@code v01}-{@code v09} (ruling V7,
 * T3a): WebP quality and losslessness, AVIF, HEIC and HEIF, animated GIF and WebP, TIFF, the HEIC sequence refusal, BMP,
 * truncated JPEG/PNG/GIF and the metadata a JPEG and a PNG keep; v08 and v09 also with GD alone (the harness switch
 * {@code qp_parity_editor_gd}).
 * Every item runs on a subdirectory site of its own, uploads through {@code POST /wp/v2/media} as alice and records,
 * for each upload, the exchange, the stored attachment (post row with {@code post_mime_type}, raw meta with
 * {@code _wp_attached_file} and {@code _wp_attachment_metadata}, the metadata, the folder's listing), every file of
 * the attachment as PHP, WordPress and Imagick read it (type, dimensions, frames, quality, WebP chunks, profile names,
 * channel means), and the quality log of the harness (the default quality WordPress asks per size, with the function
 * that asked, and the quality each written file was saved at). The run also notes the server's Imagick (version, formats and the policy lines for PDF and HEIC) and GD ({@code gd_info},
 * {@code gd.jpeg_ignore_warning}).
 *
 * <p>Only what the images reference states firmly is asserted, after everything is recorded: the lossy WebP's sizes
 * are WebP; a HEIC's sizes are JPEG and its {@code post_mime_type} is {@code image/heic}; a declared
 * {@code image/heic-sequence} is refused 400 {@code rest_upload_image_type_not_supported}.
 *
 * <p>This host's PHP has neither GD nor Imagick: the items run in the temporary CI job {@code imagick-facts} only, and
 * are skipped with a message elsewhere, or fail instead under {@code -Dwp.media.require-imagick=true}.
 */
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
@TestMethodOrder(MethodOrderer.MethodName.class)
class MediaImagickFactsDbTest {

    static final String IMAGICK_SKIP = "SKIPPED (needs Imagick): php8.4-imagick is not installed on this host; the"
            + " temporary CI job imagick-facts installs it (ruling V7)";

    static ScenarioDatabase database;
    static WordPressRestSite site;
    static RestClient client;
    static boolean imagick;

    /** The item the running test records under ({@code v01}...); null when it records nothing. */
    String item;

    /** The site paths ({@code /mv01/}) the running item created; their uploads are emptied after it. */
    final List<String> sites = new ArrayList<>();

    @BeforeAll
    static void start() throws Exception {
        assumeTrue(ScenarioDatabase.runtimeAvailable(), "ASSUMPTION: no container runtime");
        database = ScenarioDatabase.start();
        // Harness rule (as in MediaFactsDbTest): the installing administrator is the current user while the template
        // network is created, so upload_filetypes is written for that user.
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
        JsonNode extensions = site.operation("extensions", args());
        imagick = extensions.path("imagick").asBoolean();
        VerificationFacts.note("x-imagick", "extensions", extensions);
        VerificationFacts.note("x-imagick", "imagick", site.operation("imagick_info", args()));
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
                && method.charAt(0) == 'v'
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

    /**
     * WebP: a lossy and a lossless 3000&times;2000 upload, then (P1-d) the lossless one again with the harness's
     * WebP-to-JPEG output mapping, to record the JPEG quality WordPress asks for a lossless source. Reading of
     * WordPress, not recorded here: GD asks quality 101 for a lossless WebP source where Imagick asks 100; both mean
     * lossless (QuarkPress uses 100).
     */
    @Test
    void v01WebpQualityAndLossless() throws Exception {
        requireImagick();
        String prefix = createSite("mv01");
        JsonNode lossy = uploadAndNote(prefix, "v01-lossy.webp", "image/webp", MediaFixtures.webpLossy(3000, 2000));
        uploadAndNote(prefix, "v01-lossless.webp", "image/webp", MediaFixtures.webpLossless(3000, 2000));
        try {
            option(prefix, "qp_parity_webp_to_jpeg", "1");
            uploadAndNote(prefix, "v01-lossless-to-jpeg.webp", "image/webp", MediaFixtures.webpLossless(3000, 2000));
        } finally {
            option(prefix, "qp_parity_webp_to_jpeg", "0");
        }
        assertThat(lossy.path("exists").asBoolean())
                .as("the lossy WebP is stored")
                .isTrue();
        assertThat(sizeTypes(lossy)).as("the lossy WebP's sizes").isNotEmpty().containsOnly("image/webp");
    }

    @Test
    void v02Avif() throws Exception {
        requireImagick();
        String prefix = createSite("mv02");
        uploadAndNote(prefix, "v02.avif", "image/avif", MediaFixtures.avif(2600, 1800));
    }

    /**
     * HEIC with and without {@code irot}, then (P1-8) still HEIF declared {@code image/heif} as {@code .heif}: the HEIC
     * file itself, and the same file with the major brand {@code mif1} (which {@code wp_get_image_mime} reads as
     * {@code image/heif}).
     */
    @Test
    void v03HeicAndHeif() throws Exception {
        requireImagick();
        String prefix = createSite("mv03");
        JsonNode plain = uploadAndNote(prefix, "v03.heic", "image/heic", MediaFixtures.heic(4032, 3024));
        JsonNode rotated = uploadAndNote(prefix, "v03-irot.heic", "image/heic", MediaFixtures.heicRotated(4032, 3024));
        uploadAndNote(prefix, "v03-heic-bytes.heif", "image/heif", MediaFixtures.heic(4032, 3024));
        uploadAndNote(prefix, "v03-mif1.heif", "image/heif", MediaFixtures.heifMif1(4032, 3024));
        for (JsonNode heic : List.of(plain, rotated)) {
            assertThat(heic.path("exists").asBoolean()).as("the HEIC is stored").isTrue();
            assertThat(heic.path("row").path("post_mime_type").asText()).isEqualTo("image/heic");
            assertThat(sizeTypes(heic)).as("the HEIC's sizes").isNotEmpty().containsOnly("image/jpeg");
        }
    }

    @Test
    void v04AnimatedGifAndWebp() throws Exception {
        requireImagick();
        String prefix = createSite("mv04");
        uploadAndNote(prefix, "v04.gif", "image/gif", MediaFixtures.gif(800, 600, 3));
        uploadAndNote(prefix, "v04.webp", "image/webp", MediaFixtures.animatedWebp());
    }

    @Test
    void v05Tiff() throws Exception {
        requireImagick();
        String prefix = createSite("mv05");
        uploadAndNote(prefix, "v05.tif", "image/tiff", MediaFixtures.tiff(400, 300));
    }

    @Test
    void v06HeicSequenceRefused() throws Exception {
        requireImagick();
        String prefix = createSite("mv06");
        Exchange sequence = upload(prefix, "v06.heics", "image/heic-sequence", MediaFixtures.heif("msf1"));
        noteUpload(prefix, "v06.heics", sequence);
        assertThat(sequence.status()).isEqualTo(400);
        assertThat(body(sequence).path("code").asText()).isEqualTo("rest_upload_image_type_not_supported");
    }

    @Test
    void v07Bmp() throws Exception {
        requireImagick();
        String prefix = createSite("mv07");
        uploadAndNote(prefix, "v07.bmp", "image/bmp", MediaFixtures.bmp(400, 300));
    }

    /**
     * Truncated uploads (T4a): a 1200&times;900 JPEG, PNG and GIF each cut to half its bytes (the header intact), each
     * uploaded with Imagick and with GD alone. Recorded only: the answer, the metadata (sizes or none) and the files.
     */
    @Test
    void v08TruncatedImages() throws Exception {
        requireImagick();
        String prefix = createSite("mv08");
        uploadWithEachEditor(prefix, "v08", "jpg", "image/jpeg", half(MediaFixtures.jpeg(1200, 900)));
        uploadWithEachEditor(prefix, "v08", "png", "image/png", half(MediaFixtures.png(1200, 900)));
        uploadWithEachEditor(prefix, "v08", "gif", "image/gif", half(MediaFixtures.gif(1200, 900, 1)));
    }

    /**
     * Metadata the sizes keep (M13): a 3000&times;2000 JPEG with EXIF (Orientation 6, Model), ICC (the JDK's sRGB
     * profile), XMP and IPTC, and a 1200&times;900 PNG with alpha and the same ICC profile, each uploaded with Imagick
     * and with GD alone. Recorded only; the profile names per file are in the {@code files} notes.
     */
    @Test
    void v09MetadataKept() throws Exception {
        requireImagick();
        String prefix = createSite("mv09");
        byte[] icc = ICC_Profile.getInstance(ColorSpace.CS_sRGB).getData();
        JpegMeta meta = JpegMeta.none()
                .withOrientation(6)
                .withExif("Model", "v09 camera")
                .withIptc(105, "v09 headline")
                .withXmp("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF"
                        + " xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\"><rdf:Description rdf:about=\"\""
                        + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title><rdf:Alt><rdf:li"
                        + " xml:lang=\"x-default\">v09 title</rdf:li></rdf:Alt></dc:title></rdf:Description></rdf:RDF>"
                        + "</x:xmpmeta>")
                .withIcc(icc);
        uploadWithEachEditor(prefix, "v09", "jpg", "image/jpeg", MediaFixtures.jpeg(3000, 2000, meta));
        uploadWithEachEditor(prefix, "v09-alpha", "png", "image/png", MediaFixtures.pngAlphaIcc(1200, 900, icc));
    }

    // ---- helpers ------------------------------------------------------------------------------------------------

    /**
     * Uploads {@code <base>-imagick.<extension>} with the server's editors, then {@code <base>-gd.<extension>} with
     * the harness switch {@code qp_parity_editor_gd} on (GD alone), switched off again afterwards.
     */
    void uploadWithEachEditor(String prefix, String base, String extension, String type, byte[] bytes)
            throws Exception {
        uploadAndNote(prefix, base + "-imagick." + extension, type, bytes);
        try {
            option(prefix, "qp_parity_editor_gd", "1");
            uploadAndNote(prefix, base + "-gd." + extension, type, bytes);
        } finally {
            option(prefix, "qp_parity_editor_gd", "0");
        }
    }

    /** The first half of the file's bytes: the header stays, the coded data stops midway. */
    static byte[] half(byte[] bytes) {
        return Arrays.copyOf(bytes, bytes.length / 2);
    }

    /** Skips the running item without Imagick, or fails it under {@code -Dwp.media.require-imagick=true}. */
    static void requireImagick() {
        if (imagick) {
            return;
        }
        if (Boolean.getBoolean("wp.media.require-imagick")) {
            fail("php8.4-imagick is required (-Dwp.media.require-imagick=true) but the server's PHP has no Imagick");
        }
        assumeTrue(false, IMAGICK_SKIP);
    }

    /** Creates the item's subdirectory site {@code /<id>/} with empty uploads and the quality log on. */
    String createSite(String id) throws Exception {
        String path = "/" + id + "/";
        site.operation("create_site", args().put("path", path).put("title", id));
        sites.add(path);
        site.operation("clear_uploads", args().put("site", path));
        String prefix = "/" + id;
        option(prefix, "qp_parity_quality_log", "1");
        return prefix;
    }

    /**
     * Uploads the file with the quality log emptied first, notes everything about it (see {@link #noteUpload}) and
     * answers the stored attachment ({@code {exists: false}} for a refusal).
     */
    JsonNode uploadAndNote(String prefix, String name, String type, byte[] bytes) throws Exception {
        return noteUpload(prefix, name, upload(prefix, name, type, bytes));
    }

    /**
     * A multipart upload as alice to the site's media route, recorded with the file part summarised; the site's
     * quality log is emptied first, so that it holds this upload's entries only.
     */
    Exchange upload(String prefix, String name, String type, byte[] bytes) throws Exception {
        ObjectNode empty = args().put("site", prefix + "/").put("name", "qp_parity_qualities");
        empty.putArray("value");
        site.operation("option", empty);
        RestCall call = RestCall.multipart("alice", prefix + "/wp-json/wp/v2/media", name, type, bytes, Map.of());
        Exchange exchange = client.send(call);
        String summary =
                "multipart: file=\"" + name + "\" type=" + type + " bytes=" + bytes.length + " sha256=" + sha256(bytes);
        RestCall recorded = new RestCall(
                call.as(),
                call.method(),
                call.target(),
                call.headers(),
                call.contentType(),
                Optional.of(summary.getBytes(StandardCharsets.UTF_8)));
        VerificationFacts.record(
                item,
                new Exchange(recorded, exchange.status(), exchange.headers(), exchange.body(), exchange.sentAt()));
        return exchange;
    }

    /**
     * Notes under {@code upload <name>}: the status, the stored attachment (rest-setup.php {@code attachment}) of a 201
     * answer or the refusal's body, the attachment's files ({@code image_files}) and the quality log. Answers the stored
     * attachment, or {@code {exists: false}} for a refusal.
     */
    JsonNode noteUpload(String prefix, String name, Exchange uploaded) throws Exception {
        ObjectNode note = OracleJson.MAPPER.createObjectNode();
        note.put("status", uploaded.status());
        JsonNode stored = OracleJson.MAPPER.createObjectNode().put("exists", false);
        if (uploaded.status() == 201) {
            long id = body(uploaded).path("id").longValue();
            stored = site.operation(
                    "attachment", args().put("site", prefix + "/").put("id", id));
            note.set("attachment", stored);
            note.set(
                    "files",
                    site.operation(
                            "image_files", args().put("site", prefix + "/").put("id", id)));
        } else {
            note.put("body", uploaded.text());
        }
        note.set(
                "qualities",
                site.operation("get_option", args().put("site", prefix + "/").put("name", "qp_parity_qualities"))
                        .path("value"));
        VerificationFacts.note(item, "upload " + name, note);
        return stored;
    }

    /** The {@code mime-type} of each entry of the stored metadata's {@code sizes}. */
    static List<String> sizeTypes(JsonNode stored) {
        List<String> types = new ArrayList<>();
        stored.path("metadata")
                .path("sizes")
                .forEach(size -> types.add(size.path("mime-type").asText()));
        return types;
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
                    OracleJson.MAPPER.getNodeFactory().textNode(text.substring(0, start)));
        }
        try {
            return OracleJson.MAPPER.readTree(text.substring(start));
        } catch (java.io.IOException e) {
            throw new AssertionError("not JSON: " + text, e);
        }
    }

    void option(String prefix, String name, String value) throws Exception {
        site.operation(
                "option", args().put("site", prefix + "/").put("name", name).put("value", value));
    }

    static ObjectNode args() {
        return OracleJson.MAPPER.createObjectNode();
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
