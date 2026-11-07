package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wprestparity.MediaComparison.Observed;
import org.quarkpress.testing.wprestparity.MediaScenario.Fixture;

/** {@link MediaComparison}'s normalisation and aspects, on hand-made observations (no WordPress). */
class MediaComparisonTest {

    static final MediaScenario SCENARIO = new MediaScenario(
            "mt",
            "comparison test",
            false,
            List.of(),
            List.of(new MediaScenario.Step.Upload(
                    "alice", new Fixture("jpeg", null), "x.jpg", "image/jpeg", Map.of())));

    /** WordPress: attachment 101 by user 1 on site 13, uploaded just before the end of November. */
    static Observed wordpress(Consumer<ObjectNode> change) {
        ObjectNode attachment =
                attachment(101, "1", "sites/13/2026/11", "2026-11-30 23:59:31", 999, 1234, "x.jpg", "x-scaled.jpg");
        change.accept(attachment);
        return new Observed(
                1,
                "upload",
                Instant.parse("2026-11-30T23:59:30Z"),
                MediaSide.Outcome.accepted(101),
                Optional.of(attachment),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Map.of(101L, "step1"),
                Map.of(1L, "u:alice"),
                "/mt/");
    }

    /** QuarkPress: attachment 7 by user 42 on site 2, uploaded just after midnight: another month folder. */
    static Observed quarkpress(Consumer<ObjectNode> change) {
        ObjectNode attachment =
                attachment(7, "42", "sites/2/2026/12", "2026-12-01 00:00:21", 555, 4321, "x.jpg", "x-scaled.jpg");
        change.accept(attachment);
        return new Observed(
                1,
                "upload",
                Instant.parse("2026-12-01T00:00:20Z"),
                MediaSide.Outcome.accepted(7),
                Optional.of(attachment),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Map.of(7L, "step1"),
                Map.of(42L, "u:alice"),
                "/mt/");
    }

    static ObjectNode attachment(
            long id, String author, String folder, String date, long mainSize, long sizeSize, String... files) {
        String month = folder.substring(folder.indexOf('/', "sites/".length()) + 1);
        ObjectNode root = OracleJson.MAPPER.createObjectNode();
        ObjectNode post = root.putObject("post");
        post.put("ID", id);
        post.put("post_type", "attachment");
        post.put("post_title", "x");
        post.put("post_author", author);
        post.put("post_parent", 0);
        post.put("guid", "http://localhost/mt/wp-content/uploads/" + folder + "/x.jpg");
        post.put("post_date", date);
        post.put("post_date_gmt", date);
        post.put("menu_order", 0);
        ObjectNode meta = root.putObject("meta");
        meta.put("_wp_attached_file", month + "/x-scaled.jpg");
        ObjectNode metadata = meta.putObject("_wp_attachment_metadata");
        metadata.put("width", 2560).put("height", 1920).put("file", month + "/x-scaled.jpg");
        metadata.put("filesize", mainSize);
        metadata.putObject("sizes")
                .putObject("thumbnail")
                .put("file", "x-150x150.jpg")
                .put("width", 150)
                .put("filesize", sizeSize);
        metadata.putObject("image_meta").putArray("keywords");
        metadata.put("original_image", "x.jpg");
        var listing = root.putArray("files");
        for (String file : files) {
            listing.add(file);
        }
        return root;
    }

    static ParityRun compare(Observed wp, Observed qp) {
        ParityRun run = new ParityRun(
                "media-comparison-test", ParitySkipList.empty(), Path.of("target", "media-comparison-test.json"));
        MediaComparison.compare(SCENARIO, List.of(wp), List.of(qp), run);
        return run;
    }

    @Test
    void anIdTheSiteFolderTheMonthAndAWrittenFileSizeCompareEqual() {
        ParityRun run = compare(wordpress(a -> {}), quarkpress(a -> {}));

        assertThat(run.mismatches()).isEmpty();
        // outcome, 18 columns, 2 meta keys, 9 metadata leaves, files
        assertThat(run.comparisons()).isEqualTo(1 + 18 + 2 + 9 + 1);
    }

    @Test
    void aDifferingTitleIsOneMismatch() {
        ParityRun run =
                compare(wordpress(a -> {}), quarkpress(a -> ((ObjectNode) a.get("post")).put("post_title", "y")));

        assertThat(run.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.caseId()).isEqualTo("mt");
            assertThat(m.aspect()).isEqualTo("step1:post:post_title");
        });
    }

    @Test
    void aDifferingFileNameIsOneMismatch() {
        ParityRun run = compare(
                wordpress(a -> {}),
                quarkpress(a -> a.putArray("files").add("x.jpg").add("x-scaled-1.jpg")));

        assertThat(run.mismatches())
                .singleElement()
                .satisfies(m -> assertThat(m.aspect()).isEqualTo("step1:files"));
    }

    @Test
    void aDifferingWidthIsOneMismatch() {
        ParityRun run = compare(wordpress(a -> {}), quarkpress(a -> metadata(a).put("width", 2559)));

        assertThat(run.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.aspect()).isEqualTo("step1:metadata:/width");
            assertThat(m.wordpress()).isEqualTo("2560");
            assertThat(m.quarkpress()).isEqualTo("2559");
        });
    }

    @Test
    void theOriginalsSizeIsComparedWhenNoFileReplacedIt() {
        ParityRun run = compare(
                wordpress(a -> metadata(a).remove("original_image")),
                quarkpress(a -> metadata(a).remove("original_image")));

        assertThat(run.mismatches())
                .singleElement()
                .satisfies(m -> assertThat(m.aspect()).isEqualTo("step1:metadata:/filesize"));
    }

    @Test
    void aDateOutsideTheStepsWindowIsCompared() {
        ParityRun run = compare(
                wordpress(a -> {}),
                quarkpress(a -> ((ObjectNode) a.get("post")).put("post_date", "2026-12-01 00:03:00")));

        assertThat(run.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.aspect()).isEqualTo("step1:post:post_date");
            assertThat(m.wordpress()).isEqualTo("{now}");
            assertThat(m.quarkpress()).isEqualTo("2026-12-01 00:03:00");
        });
    }

    @Test
    void anAuthorOfAnotherUserDiffers() {
        ParityRun run =
                compare(wordpress(a -> {}), quarkpress(a -> ((ObjectNode) a.get("post")).put("post_author", "43")));

        assertThat(run.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.aspect()).isEqualTo("step1:post:post_author");
            assertThat(m.quarkpress()).isEqualTo("?43");
        });
    }

    @Test
    void anEmptyAltAndNoAltAreTheSameButAnEmptyArrayAndObjectToo() {
        ParityRun run = compare(
                wordpress(a -> ((ObjectNode) a.get("meta")).put("_wp_attachment_image_alt", "")),
                quarkpress(a -> metadata(a).putObject("image_meta").putObject("keywords")));

        assertThat(run.mismatches()).isEmpty();
    }

    @Test
    void wordPressesUnknownUploadErrorIsTheUploadError() {
        Observed wp = refused("rest_upload_unknown_error", "Sorry, you are not allowed to upload this file type.");
        Observed qp = refused("upload_error", "Sorry, you are not allowed to upload this file type.");

        ParityRun run = compare(wp, qp);

        assertThat(run.mismatches()).isEmpty();
        assertThat(run.comparisons()).isEqualTo(1);
        assertThat(compare(wp, refused("upload_error", "another message")).mismatches())
                .singleElement()
                .satisfies(m -> assertThat(m.aspect()).isEqualTo("step1:outcome"));
    }

    @Test
    void anAcceptanceAgainstARefusalComparesTheOutcomeOnly() {
        ParityRun run = compare(wordpress(a -> {}), refused("rest_upload_image_type_not_supported", "no"));

        assertThat(run.comparisons()).isEqualTo(1);
        assertThat(run.mismatches()).singleElement().satisfies(m -> {
            assertThat(m.wordpress()).isEqualTo("accepted");
            assertThat(m.quarkpress()).isEqualTo("rest_upload_image_type_not_supported: no");
        });
    }

    @Test
    void queriedIdsAreComparedByRefInCreationOrder() {
        Observed wp = query(List.of(12L, 11L), Map.of(11L, "step1", 12L, "step2"));
        Observed qp = query(List.of(5L, 6L), Map.of(5L, "step1", 6L, "step2"));

        assertThat(compare(wp, qp).mismatches()).isEmpty();
        assertThat(compare(wp, query(List.of(5L), Map.of(5L, "step1", 6L, "step2")))
                        .mismatches())
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.aspect()).isEqualTo("step1:ids");
                    assertThat(m.wordpress()).isEqualTo("step1, step2");
                    assertThat(m.quarkpress()).isEqualTo("step1");
                });
    }

    @Test
    void observationsMissingForAStepAreRefused() {
        ParityRun run = new ParityRun("t", ParitySkipList.empty(), Path.of("target", "media-comparison-test.json"));

        assertThatThrownBy(() -> MediaComparison.compare(SCENARIO, List.of(), List.of(), run))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theNineteenScenariosReadAndTheirFilesBuild() {
        List<MediaScenario> scenarios = MediaScenarios.all();

        assertThat(scenarios)
                .extracting(MediaScenario::id)
                .containsExactly(
                        "m01", "m02", "m03", "m04", "m05", "m06", "m07", "m08", "m09", "m10", "m11", "m12", "m13",
                        "m14", "m15", "m16", "m17", "m18", "m19");
        assertThat(scenarios)
                .filteredOn(MediaScenario::editor)
                .extracting(MediaScenario::id)
                .containsExactly("m07", "m12", "m13", "m14", "m15", "m16");
        for (MediaScenario scenario : scenarios) {
            for (MediaScenario.Setup setup : scenario.setup()) {
                if (setup instanceof MediaScenario.Setup.Folder folder) {
                    folder.files()
                            .values()
                            .forEach(file ->
                                    assertThat(MediaScenarios.fixture(file)).isNotEmpty());
                }
            }
            for (MediaScenario.Step step : scenario.steps()) {
                if (step instanceof MediaScenario.Step.Upload upload && !scenario.editor()) {
                    assertThat(MediaScenarios.fixture(upload.fixture()))
                            .as("%s %s", scenario.id(), upload.clientName())
                            .isNotEmpty();
                }
            }
        }
    }

    @Test
    void aScenarioWithAnUnknownKeyOrCallerIsRefused() {
        assertThatThrownBy(() -> MediaScenarioJson.read(
                        "{\"id\": \"x\", \"rule\": \"r\", \"steps\": [{\"upload\": \"png\", \"name\": \"a.png\","
                                + " \"type\": \"image/png\", \"feilds\": {}}]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("feilds");
        assertThatThrownBy(() -> MediaScenarioJson.read("{\"id\": \"x\", \"rule\": \"r\", \"steps\": [{\"delete\":"
                        + " \"step1\", \"as\": \"mallory\"}]}"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mallory");
    }

    @Test
    void anUploadsPostRefBecomesThisSidesId() {
        assertThat(MediaScenarios.resolved(Map.of("post", "p:dated", "title", "p:dated"), Map.of("p:dated", 42L)))
                .containsEntry("post", "42")
                .containsEntry("title", "p:dated");
        assertThat(MediaScenarios.resolved(Map.of("post", "999999"), Map.of())).containsEntry("post", "999999");
    }

    @Test
    void sitesFoldersOfAnyIdBecomeOnePlaceholder() {
        assertThat(MediaComparison.sitesFolder("a/sites/13/b sites/x/ sites/7"))
                .isEqualTo("a/sites/{id}/b sites/x/ sites/7");
    }

    private static ObjectNode metadata(ObjectNode attachment) {
        return (ObjectNode) attachment.get("meta").get("_wp_attachment_metadata");
    }

    private static Observed refused(String code, String message) {
        return new Observed(
                1,
                "upload",
                Instant.parse("2026-11-30T23:59:30Z"),
                MediaSide.Outcome.refused(code, message),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Map.of(),
                Map.of(),
                Map.of(),
                "/mt/");
    }

    private static Observed query(List<Long> ids, Map<Long, String> posts) {
        java.util.LinkedHashMap<Long, String> ordered = new java.util.LinkedHashMap<>();
        posts.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .forEach(e -> ordered.put(e.getKey(), e.getValue()));
        return new Observed(
                1,
                "query",
                Instant.parse("2026-11-30T23:59:30Z"),
                MediaSide.Outcome.accepted(0),
                Optional.empty(),
                Optional.empty(),
                Optional.of(ids),
                Map.of(),
                ordered,
                Map.of(),
                "/mt/");
    }
}
