package org.quarkpress.testing.wprestparity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.GZIPOutputStream;
import org.quarkpress.testing.wprestparity.MediaComparison.Observed;
import org.quarkpress.testing.wprestparity.MediaFixtures.JpegMeta;
import org.quarkpress.testing.wprestparity.MediaScenario.Fixture;
import org.quarkpress.testing.wprestparity.MediaScenario.Step;

/**
 * The media parity scenarios ({@code scenarios/media/index.txt}, in run order), their test files and the runner that
 * plays a scenario on one side.
 */
public final class MediaScenarios {

    static final String RESOURCES = "scenarios/media/";

    private MediaScenarios() {}

    /** Every scenario of {@code index.txt}, in its order. */
    public static List<MediaScenario> all() {
        List<MediaScenario> scenarios = new ArrayList<>();
        for (String line : resource("index.txt").lines().toList()) {
            String name = line.strip();
            if (name.isEmpty() || name.startsWith("#")) {
                continue;
            }
            MediaScenario scenario = MediaScenarioJson.read(resource(name));
            if (!name.startsWith(scenario.id() + "-")) {
                throw new IllegalStateException(name + " holds scenario " + scenario.id());
            }
            for (MediaScenario earlier : scenarios) {
                if (earlier.id().equals(scenario.id())) {
                    throw new IllegalStateException("scenario " + scenario.id() + " twice in index.txt");
                }
            }
            scenarios.add(scenario);
        }
        return scenarios;
    }

    private static String resource(String name) {
        try (InputStream in = MediaScenarios.class.getResourceAsStream(RESOURCES + name)) {
            if (in == null) {
                throw new IllegalStateException("no resource " + RESOURCES + name);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * A test file's bytes. Names and arguments: {@code jpeg [w, h]} or {@code jpeg {"w", "h", "orientation",
     * "iptc": {"<dataset>": [values]}, "exif": {"<field>": "<value>"}}}; {@code png}, {@code bmp}, {@code tiff},
     * {@code progressiveJpeg}, {@code pngHeaderOnly} {@code [w, h]}; {@code gif [w, h, frames]}; {@code
     * pngExifOrientation [w, h, orientation]}; {@code heif [brand]}; {@code zipOoxml [content type]}; {@code mp3
     * {"id3": {...}, "cover": true}}; {@code mp4 [w, h, seconds]}; {@code wav [seconds]}; {@code text [content]}; no
     * arguments for {@code webp}, {@code ico}, {@code psd}, {@code pdf}, {@code odt}, {@code xls}, {@code m4a},
     * {@code ogg}, {@code ogv}, {@code webm}; and two that are not {@link MediaFixtures}': {@code gzip [content]} (the
     * text gzipped) and {@code hex [hex digits]} (the bytes as written).
     */
    public static byte[] fixture(Fixture fixture) {
        JsonNode args = fixture.args();
        return switch (fixture.name()) {
            case "jpeg" -> args.isObject() ? jpeg(args) : MediaFixtures.jpeg(arg(args, 0), arg(args, 1));
            case "png" -> MediaFixtures.png(arg(args, 0), arg(args, 1));
            case "bmp" -> MediaFixtures.bmp(arg(args, 0), arg(args, 1));
            case "tiff" -> MediaFixtures.tiff(arg(args, 0), arg(args, 1));
            case "progressiveJpeg" -> MediaFixtures.progressiveJpeg(arg(args, 0), arg(args, 1));
            case "pngHeaderOnly" -> MediaFixtures.pngHeaderOnly(arg(args, 0), arg(args, 1));
            case "gif" -> MediaFixtures.gif(arg(args, 0), arg(args, 1), arg(args, 2));
            case "pngExifOrientation" -> MediaFixtures.pngExifOrientation(arg(args, 0), arg(args, 1), arg(args, 2));
            case "heif" -> MediaFixtures.heif(text(args, 0));
            case "zipOoxml" -> MediaFixtures.zipOoxml(text(args, 0));
            case "mp3" -> mp3(args);
            case "mp4" -> MediaFixtures.mp4(arg(args, 0), arg(args, 1), arg(args, 2));
            case "wav" -> MediaFixtures.wav(arg(args, 0));
            case "text" -> MediaFixtures.text(text(args, 0));
            case "webp" -> MediaFixtures.webp();
            case "ico" -> MediaFixtures.ico();
            case "psd" -> MediaFixtures.psd();
            case "pdf" -> MediaFixtures.pdf();
            case "odt" -> MediaFixtures.odt();
            case "xls" -> MediaFixtures.xls();
            case "m4a" -> MediaFixtures.m4a();
            case "ogg" -> MediaFixtures.ogg();
            case "ogv" -> MediaFixtures.ogv();
            case "webm" -> MediaFixtures.webm();
            case "gzip" -> gzip(MediaFixtures.text(text(args, 0)));
            case "hex" -> HexFormat.of().parseHex(text(args, 0));
            default -> throw new IllegalArgumentException("unknown fixture " + fixture.name());
        };
    }

    private static byte[] jpeg(JsonNode args) {
        JpegMeta meta = JpegMeta.none();
        if (args.has("orientation")) {
            meta = meta.withOrientation(args.get("orientation").intValue());
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = args.path("iptc").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> dataset = it.next();
            List<String> values = new ArrayList<>();
            dataset.getValue().forEach(value -> values.add(value.textValue()));
            meta = meta.withIptc(Integer.parseInt(dataset.getKey()), values.toArray(String[]::new));
        }
        for (Iterator<Map.Entry<String, JsonNode>> it = args.path("exif").fields(); it.hasNext(); ) {
            Map.Entry<String, JsonNode> field = it.next();
            meta = meta.withExif(field.getKey(), field.getValue().textValue());
        }
        for (Iterator<String> it = args.fieldNames(); it.hasNext(); ) {
            String key = it.next();
            if (!List.of("w", "h", "orientation", "iptc", "exif").contains(key)) {
                throw new IllegalArgumentException("unknown jpeg fixture argument " + key);
            }
        }
        return MediaFixtures.jpeg(args.get("w").intValue(), args.get("h").intValue(), meta);
    }

    private static byte[] mp3(JsonNode args) {
        Map<String, String> id3 = new LinkedHashMap<>();
        args.path("id3")
                .fields()
                .forEachRemaining(tag -> id3.put(tag.getKey(), tag.getValue().textValue()));
        Optional<byte[]> cover =
                args.path("cover").asBoolean(false) ? Optional.of(MediaFixtures.jpeg(32, 32)) : Optional.empty();
        return MediaFixtures.mp3(id3, cover);
    }

    private static byte[] gzip(byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(content);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    private static int arg(JsonNode args, int index) {
        JsonNode value = args.get(index);
        if (value == null || !value.isInt()) {
            throw new IllegalArgumentException("fixture argument " + index + " is a number in " + args);
        }
        return value.intValue();
    }

    private static String text(JsonNode args, int index) {
        JsonNode value = args.get(index);
        if (value == null || !value.isTextual()) {
            throw new IllegalArgumentException("fixture argument " + index + " is a string in " + args);
        }
        return value.textValue();
    }

    /**
     * Plays the scenario on one side: a new site, the setup, then each step, observing what the comparison needs.
     * An upload's {@code post} field, an update's {@code post} and {@code featured_media} and a step's ref name refs,
     * replaced by this side's ids. The side's {@link MediaSide#finish} runs whatever happened.
     */
    public static List<Observed> run(MediaSide side, MediaScenario scenario) throws Exception {
        String site = side.newSite(scenario.id());
        try {
            Map<String, Long> refs = new LinkedHashMap<>(side.setup(site, scenario.setup()));
            Map<Long, String> posts = new LinkedHashMap<>();
            Map<Long, String> users = new LinkedHashMap<>();
            List<String> setupPosts = new ArrayList<>();
            refs.forEach((ref, id) -> {
                if (ref.startsWith("u:")) {
                    users.put(id, ref);
                } else {
                    posts.put(id, ref);
                    setupPosts.add(ref);
                }
            });
            Map<String, String> folders = new LinkedHashMap<>();
            List<Observed> observed = new ArrayList<>();
            for (int i = 0; i < scenario.steps().size(); i++) {
                int n = i + 1;
                Step step = scenario.steps().get(i);
                Instant at = Instant.now();
                MediaSide.Outcome outcome;
                String kind;
                Optional<ObjectNode> attachment = Optional.empty();
                Optional<List<String>> files = Optional.empty();
                Optional<List<Long>> ids = Optional.empty();
                Map<String, List<Long>> featured = new LinkedHashMap<>();
                switch (step) {
                    case Step.Upload upload -> {
                        kind = "upload";
                        Step.Upload resolved = new Step.Upload(
                                upload.as(),
                                upload.fixture(),
                                upload.clientName(),
                                upload.declaredType(),
                                resolved(upload.fields(), refs));
                        outcome = side.upload(site, resolved);
                        if (outcome.accepted()) {
                            String ref = MediaScenario.stepRef(n);
                            refs.put(ref, outcome.id());
                            posts.put(outcome.id(), ref);
                            attachment = Optional.of(side.attachment(site, outcome.id()));
                            folders.put(ref, folder(attachment.get()));
                        }
                    }
                    case Step.Update update
                    when setupPosts.contains(update.ref()) -> {
                        kind = "feature";
                        if (!update.fields().keySet().equals(java.util.Set.of("featured_media"))) {
                            throw new IllegalArgumentException(
                                    scenario.id() + " step " + n + ": a setup post's update sets featured_media only");
                        }
                        long post = id(refs, update.ref());
                        outcome = side.feature(
                                site, post, id(refs, update.fields().get("featured_media")));
                        featured.put(update.ref(), side.featured(site, post));
                    }
                    case Step.Update update -> {
                        kind = "update";
                        long id = id(refs, update.ref());
                        outcome = side.update(site, update.as(), id, resolved(update.fields(), refs));
                        if (outcome.accepted()) {
                            attachment = Optional.of(side.attachment(site, id));
                        }
                    }
                    case Step.Delete delete -> {
                        kind = "delete";
                        outcome = side.delete(site, delete.as(), id(refs, delete.ref()));
                        if (outcome.accepted()) {
                            String folder = folders.get(delete.ref());
                            files = Optional.of(folder == null ? List.of() : side.files(site, folder));
                            for (String post : setupPosts) {
                                featured.put(post, side.featured(site, refs.get(post)));
                            }
                        }
                    }
                    case Step.Process process -> {
                        kind = "process";
                        long id = id(refs, process.ref());
                        outcome = side.process(site, process.as(), id);
                        if (outcome.accepted()) {
                            attachment = Optional.of(side.attachment(site, id));
                        }
                    }
                    case Step.Query query -> {
                        kind = "query";
                        ids = Optional.of(side.query(site, query));
                        outcome = MediaSide.Outcome.accepted(0);
                    }
                }
                observed.add(new Observed(n, kind, at, outcome, attachment, files, ids, featured, posts, users, site));
            }
            return observed;
        } finally {
            side.finish(site);
        }
    }

    /** The folder of the attachment's file ({@code 2026/11}), from {@code _wp_attached_file}. */
    static String folder(ObjectNode attachment) {
        String file = attachment.path("meta").path("_wp_attached_file").asText("");
        int slash = file.lastIndexOf('/');
        return slash < 0 ? "" : file.substring(0, slash);
    }

    /** The fields with each {@code post} or {@code featured_media} ref replaced by its id. */
    static Map<String, String> resolved(Map<String, String> fields, Map<String, Long> refs) {
        Map<String, String> out = new LinkedHashMap<>(fields);
        for (String key : List.of("post", "featured_media")) {
            String value = out.get(key);
            if (value != null && refs.containsKey(value)) {
                out.put(key, String.valueOf(refs.get(value)));
            }
        }
        return out;
    }

    private static long id(Map<String, Long> refs, String ref) {
        Long id = refs.get(ref);
        if (id == null) {
            throw new IllegalArgumentException("unknown ref " + ref + " (known: " + refs.keySet() + ")");
        }
        return id;
    }
}
