package org.quarkword.it.mediavips;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import org.quarkword.testing.wporacle.OracleJson;
import org.quarkword.testing.wprestparity.MediaScenario.Setup;
import org.quarkword.testing.wprestparity.MediaScenario.Step;
import org.quarkword.testing.wprestparity.MediaScenarios;
import org.quarkword.testing.wprestparity.MediaSide;
import org.quarkword.testing.wprestparity.WordPressRestSite;

/**
 * The WordPress side of a {@code vips} scenario: WordPress 7.1.3 with Imagick, replayed from its answers recorded once
 * in CI ({@code MediaImagickFactsDbTest}, facts v01-v07), kept in {@code src/test/resources/imagick-facts/<id>.json}.
 * Each upload is looked up by its file name; its declared type and the SHA-256 of its bytes must be those of the
 * recorded upload, so the scenario cannot drift from what WordPress was sent. Uploads only: a scenario with setup
 * entries or other steps is refused. {@link #close} fails when an upload the file records was never played, so a
 * recorded answer cannot be dropped from the scenario unnoticed.
 *
 * <p>The file records v01-v07's uploads except {@code v01-lossless-to-jpeg.webp}: WordPress made that one with the
 * harness's WebP-to-JPEG output mapping ({@code qw_parity_webp_to_jpeg}), which a scenario cannot switch on for
 * QuarkWord: its output mapping is the application's configuration ({@code quarkword.media.output-formats}), not a
 * site option. The media core's own tests cover a lossless WebP mapped to JPEG.
 *
 * <p>Provenance: {@code imagick-facts/m20.json} was extracted from GitHub Actions run 44250089682 (commit fbe3706) of
 * {@code MediaImagickFactsDbTest} (ubuntu-24.04, PHP 8.4, ImageMagick 6.9.12-98, php-imagick 3.8.1), from its
 * artifact's {@code wp-rest-parity/verification-facts.json}: {@code items.vNN[].request.body} for each upload's
 * declared type and SHA-256, {@code notes.vNN["upload <name>"]} for the answer and the stored attachment. It is
 * committed as recorded output on purpose: only a run with Imagick can make it again, on a temporary branch with the
 * facts workflow, as the parity module's README says ("The libvips media parity run").
 *
 * <p>The facts ran on one site per item; the scenario runs on one site. Harness rules, not WordPress behaviour, that
 * make the recorded answers read as if this scenario's site had just written them: the site URL becomes this site's,
 * the recorded upload folder ({@code yyyy/MM}) the current one, the post dates the moment of the upload, the post ids
 * this side's own (from 1001, by upload order), and an attachment's folder lists the files of every earlier recorded
 * upload of the scenario as well as its own.
 */
final class ImagickFactsSide implements MediaSide {

    static final String RESOURCES = "/imagick-facts/";

    private static final DateTimeFormatter MYSQL_DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final List<String> DATES =
            List.of("post_date", "post_date_gmt", "post_modified", "post_modified_gmt");

    private final JsonNode uploads;
    private final String file;
    private String site;
    private long nextId = 1001;
    private final Map<Long, ObjectNode> attachments = new HashMap<>();
    private final TreeSet<String> folder = new TreeSet<>();
    private final TreeSet<String> played = new TreeSet<>();

    /** The side of scenario {@code scenarioId}: its recorded answers are {@code imagick-facts/<scenarioId>.json}. */
    ImagickFactsSide(String scenarioId) {
        this.file = scenarioId + ".json";
        try (InputStream in = ImagickFactsSide.class.getResourceAsStream(RESOURCES + file)) {
            if (in == null) {
                throw new IllegalStateException("no recorded Imagick facts " + RESOURCES + file);
            }
            this.uploads = OracleJson.MAPPER.readTree(in).get("uploads");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String newSite(String scenarioId) {
        site = "/" + scenarioId + "/";
        attachments.clear();
        folder.clear();
        return site;
    }

    @Override
    public Map<String, Long> setup(String sitePath, List<Setup> setup) {
        if (!setup.isEmpty()) {
            throw new UnsupportedOperationException("recorded Imagick facts hold uploads only, no setup: " + setup);
        }
        Map<String, Long> refs = new LinkedHashMap<>();
        // alice is the recorded rows' post_author; the other users made nothing there
        refs.put("u:alice", aliceId());
        long other = 901;
        for (String user : WordPressRestSite.USERS) {
            if (!user.equals("alice")) {
                refs.put("u:" + user, other++);
            }
        }
        return refs;
    }

    @Override
    public Outcome upload(String sitePath, Step.Upload upload) {
        JsonNode recorded = uploads.get(upload.clientName());
        played.add(upload.clientName());
        if (recorded == null) {
            throw new IllegalArgumentException(file + " records no upload named " + upload.clientName()
                    + " (WordPress with Imagick was never sent it)");
        }
        byte[] bytes = MediaScenarios.fixture(upload.fixture());
        if (!recorded.path("declared_type").asText().equals(upload.declaredType())
                || !recorded.path("sha256").asText().equals(sha256(bytes))
                || !upload.as().equals("alice")
                || !upload.fields().isEmpty()) {
            throw new IllegalArgumentException(upload.clientName() + ": the step is not the recorded upload (declared "
                    + recorded.path("declared_type").asText() + ", SHA-256 "
                    + recorded.path("sha256").asText()
                    + ", as alice without fields)");
        }
        if (recorded.path("status").asInt() != 201) {
            JsonNode body = recorded.path("body");
            return Outcome.refused(
                    body.path("code").asText(), body.path("message").asText(""));
        }
        long id = nextId++;
        attachments.put(id, replayed(recorded.get("attachment"), id));
        return Outcome.accepted(id);
    }

    /** The recorded attachment as this site holds it now, in {@link MediaSide#attachment}'s shape. */
    private ObjectNode replayed(JsonNode recorded, long id) {
        String attached =
                recorded.path("meta").path("_wp_attached_file").path(0).asText();
        String recordedFolder = attached.substring(0, attached.lastIndexOf('/') + 1);
        String currentFolder = MediaSide.monthFolder(Instant.now()) + "/";
        String recordedSite = siteUrl(recorded.path("row").path("guid").asText());
        String text = recorded.toString()
                .replace(recordedSite, "http://localhost" + site)
                .replace(recordedFolder, currentFolder);
        JsonNode stored;
        try {
            stored = OracleJson.MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        ObjectNode out = OracleJson.MAPPER.createObjectNode();
        ObjectNode post = out.putObject("post");
        stored.get("row").fields().forEachRemaining(column -> post.set(column.getKey(), column.getValue()));
        post.put("ID", id);
        String now = MYSQL_DATE.format(Instant.now().atZone(ZoneOffset.UTC));
        DATES.forEach(column -> post.put(column, now));
        ObjectNode meta = out.putObject("meta");
        for (Iterator<Map.Entry<String, JsonNode>> it = stored.get("meta").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> key = it.next();
            JsonNode values = key.getValue();
            meta.set(key.getKey(), values.isArray() && values.size() == 1 ? values.get(0) : values);
        }
        if (!stored.path("metadata").isNull() && !stored.path("metadata").isMissingNode()) {
            meta.set("_wp_attachment_metadata", stored.get("metadata"));
        }
        for (JsonNode path : stored.get("folder_listing")) {
            String name = path.asText();
            folder.add(name.substring(name.lastIndexOf('/') + 1));
        }
        ArrayNode files = out.putArray("files");
        folder.forEach(files::add);
        return out;
    }

    /** {@code http://localhost/<site>/} of a recorded guid. */
    private static String siteUrl(String guid) {
        String prefix = "http://localhost/";
        int end = guid.indexOf("/wp-content/");
        if (!guid.startsWith(prefix) || end < prefix.length()) {
            throw new IllegalStateException("a recorded guid of an unexpected form: " + guid);
        }
        return guid.substring(0, end + 1);
    }

    private long aliceId() {
        for (JsonNode upload : uploads) {
            JsonNode author = upload.path("attachment").path("row").path("post_author");
            if (!author.isMissingNode()) {
                return Long.parseLong(author.asText());
            }
        }
        throw new IllegalStateException(file + " records no attachment");
    }

    @Override
    public ObjectNode attachment(String sitePath, long id) {
        ObjectNode attachment = attachments.get(id);
        if (attachment == null) {
            throw new IllegalStateException("no replayed attachment " + id);
        }
        return attachment.deepCopy();
    }

    @Override
    public Outcome update(String sitePath, String as, long id, Map<String, String> fields) {
        throw unsupported("update");
    }

    @Override
    public Outcome feature(String sitePath, long post, long attachment) {
        throw unsupported("feature");
    }

    @Override
    public List<Long> featured(String sitePath, long post) {
        throw unsupported("featured");
    }

    @Override
    public Outcome delete(String sitePath, String as, long id) {
        throw unsupported("delete");
    }

    @Override
    public Outcome process(String sitePath, String as, long id) {
        throw unsupported("process");
    }

    @Override
    public List<Long> query(String sitePath, Step.Query query) {
        throw unsupported("query");
    }

    @Override
    public List<String> files(String sitePath, String folderPath) {
        return new ArrayList<>(folder);
    }

    @Override
    public void finish(String sitePath) {
        // nothing was changed
    }

    @Override
    public void close() {
        TreeSet<String> unplayed = new TreeSet<>();
        uploads.fieldNames().forEachRemaining(unplayed::add);
        unplayed.removeAll(played);
        if (!unplayed.isEmpty()) {
            throw new IllegalStateException(file + " records uploads the scenario never played: " + unplayed);
        }
    }

    private UnsupportedOperationException unsupported(String step) {
        return new UnsupportedOperationException("recorded Imagick facts hold uploads only, no " + step);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
