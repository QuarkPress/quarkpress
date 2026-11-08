package org.quarkpress.it.media;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.quarkus.test.common.WithTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.keycloak.client.KeycloakTestClient;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.quarkpress.testing.wporacle.OracleJson;
import org.quarkpress.testing.wporacle.ParityRun;
import org.quarkpress.testing.wporacle.ParitySkipList;
import org.quarkpress.testing.wprestparity.MediaComparison;
import org.quarkpress.testing.wprestparity.MediaComparison.Observed;
import org.quarkpress.testing.wprestparity.MediaScenario;
import org.quarkpress.testing.wprestparity.MediaScenarios;
import org.quarkpress.testing.wprestparity.MediaSide;
import org.quarkpress.testing.wprestparity.RestClient;
import org.quarkpress.testing.wprestparity.WordPressRestSite;
import org.quarkpress.testing.wpscenario.ScenarioDatabase;
import org.quarkpress.testing.wpscenario.WordPressScenarioOracle;
import org.quarkpress.testing.wpscenario.WordPressTree;

/**
 * The media library below the REST layer against WordPress 7.1.3: every scenario of {@link MediaScenarios#all()} runs
 * on a fresh subdirectory site on both sides (WordPress served by {@code php -S} on its own MySQL container and
 * uploaded to through its REST media routes; QuarkPress this test application through the media test resource), and
 * what each stored (the attachment row, its meta and metadata, the files of its folder, query answers) is compared
 * after normalisation ({@link MediaComparison}). Deliberate differences are listed in {@code
 * wp-media-parity-known-differences.txt}, each with its label in the media README's "Differences from WordPress"
 * table; a listed difference that no longer differs fails the run, and so does a scenario that compared nothing, or
 * fewer comparisons than the minimum.
 *
 * <p>Scenarios marked {@code editor} need PHP's GD on WordPress's side (the reference image editor). Without it each
 * is skipped with {@link #EDITOR_SKIP} (its known differences are then left out of the run, as nothing can show them;
 * QuarkPress's side still plays it, so a scenario that cannot run fails here and not first in CI), unless {@code
 * -Dwp.media.require-editor=true}, which fails the run instead. Lines whose reason says {@code (needs gd_webp)} or
 * {@code (needs gd_avif)} apply only when the GD build writes that format. Runs with {@code -Pwp-parity} only, and
 * then fails without a container runtime instead of skipping. The installing super admin is the current user while
 * WordPress's template network is created (harness rule, as {@code MediaFactsDbTest}), so the network's upload types
 * are the ones an administrator's installation writes.
 */
@QuarkusTest
@WithTestResource(MediaFilesResource.class)
@EnabledIfSystemProperty(named = "wp.version", matches = ".+")
class MediaParityDbTest {

    /** 90 % of the comparisons of the first green run without GD (4130), rounded down to a multiple of 50. */
    static final int MIN_WITHOUT_EDITOR = 3700;

    /** 90 % of the comparisons of the first CI run with an editor (5934), rounded down to a multiple of 50. */
    static final int MIN_WITH_EDITOR = 5300;

    static final String EDITOR_SKIP =
            "SKIPPED (needs an image editor): php8.4-gd is not installed; the CI parity cell installs it";

    static final Path KNOWN = Path.of("src", "test", "resources", "wp-media-parity-known-differences.txt");
    static final Path DUMPS = Path.of("target", "media-parity");

    @TestHTTPResource("/")
    URL root;

    @ConfigProperty(name = "quarkpress.media.fs.directory")
    String storageDirectory;

    KeycloakTestClient keycloak = new KeycloakTestClient();

    @Test
    void storedMediaMatchesWordPress() throws Exception {
        assertThat(ScenarioDatabase.runtimeAvailable())
                .as("the media parity run (-Pwp-parity) needs a container runtime")
                .isTrue();
        List<MediaScenario> scenarios = MediaScenarios.all();
        // -Dmedia.parity.only=m01,m04 narrows the run (local debugging only: the known differences of the others then
        // match nothing, so such a run never passes)
        String only = System.getProperty("media.parity.only", "");
        if (!only.isEmpty()) {
            List<String> ids = List.of(only.split(","));
            scenarios = scenarios.stream().filter(s -> ids.contains(s.id())).toList();
        } else {
            assertThat(scenarios).as("the media scenarios").hasSize(19);
        }
        Files.createDirectories(DUMPS);
        List<String> comparedNothing = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        ParityRun run;
        boolean gd;
        try (ScenarioDatabase database = ScenarioDatabase.start();
                WordPressRestSite site = start(database)) {
            JsonNode extensions = site.operation("extensions", OracleJson.MAPPER.createObjectNode());
            gd = extensions.path("gd").asBoolean();
            // the reference editor is GD alone: WordPress prefers Imagick when both are loaded, and Imagick accepts
            // types GD refuses (TIFF, ICO, BMP), so a server with Imagick would compare another WordPress
            assertThat(extensions.path("imagick").asBoolean())
                    .as("WordPress's PHP must not load Imagick: the parity reference is WordPress with GD")
                    .isFalse();
            if (!gd && Boolean.getBoolean("wp.media.require-editor")) {
                fail("php8.4-gd is required (-Dwp.media.require-editor=true) but WordPress's PHP has no GD");
            }
            for (MediaScenario scenario : scenarios) {
                if (scenario.editor() && !gd) {
                    skipped.add(scenario.id());
                }
            }
            run = new ParityRun(
                    "media-parity",
                    ParitySkipList.load(effectiveKnownDifferences(
                            skipped,
                            extensions.path("gd_webp").asBoolean(),
                            extensions.path("gd_avif").asBoolean())),
                    Path.of("target", "media-parity-failures.json"));
            MediaSide wordpress =
                    MediaSide.wordpress(site, new RestClient(site.root(), RestClient.applicationPasswords(site)));
            MediaSide quarkpress = new QuarkPressMediaSide(root.toURI(), keycloak, Path.of(storageDirectory));
            for (MediaScenario scenario : scenarios) {
                if (skipped.contains(scenario.id())) {
                    System.out.println("media-parity: " + scenario.id() + " " + EDITOR_SKIP);
                    // QuarkPress still plays it, so a scenario that cannot run fails here
                    List<Observed> qp = MediaScenarios.run(quarkpress, scenario);
                    dump(scenario, List.of(), qp);
                    continue;
                }
                List<Observed> wp = MediaScenarios.run(wordpress, scenario);
                List<Observed> qp = MediaScenarios.run(quarkpress, scenario);
                dump(scenario, wp, qp);
                int before = run.comparisons();
                MediaComparison.compare(scenario, wp, qp, run);
                if (run.comparisons() == before) {
                    comparedNothing.add(scenario.id());
                }
            }
        }
        System.out.println("media-parity: " + scenarios.size() + " scenarios (" + skipped.size() + " skipped: "
                + skipped + "), " + run.comparisons() + " comparisons, "
                + run.mismatches().size() + " differences");
        StringBuilder all = new StringBuilder();
        for (ParityRun.Mismatch m : run.mismatches()) {
            all.append(m.caseId())
                    .append('\t')
                    .append(m.aspect())
                    .append('\t')
                    .append(m.wordpress())
                    .append('\t')
                    .append(m.quarkpress())
                    .append('\n');
        }
        Files.writeString(DUMPS.resolve("differences.tsv"), all);
        run.assertParity(gd ? MIN_WITH_EDITOR : MIN_WITHOUT_EDITOR);
        assertThat(comparedNothing)
                .as("scenarios every aspect of which is a known difference")
                .isEmpty();
    }

    /** WordPress with the installing super admin as the current user while its template network is created. */
    private static WordPressRestSite start(ScenarioDatabase database) throws Exception {
        System.setProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER, "true");
        try {
            return WordPressRestSite.start(
                    WordPressTree.ensure(System.getProperty("wp.version")),
                    database,
                    System.getProperty("wp.php", "php"));
        } finally {
            System.clearProperty(WordPressScenarioOracle.INSTALLER_CURRENT_USER);
        }
    }

    /**
     * The known differences this run can show, written to {@code target/media-parity/}: without the entries of
     * skipped scenarios, and without those that need a GD format ({@code (needs gd_webp)}, {@code (needs gd_avif)})
     * the server's GD does not write.
     */
    static Path effectiveKnownDifferences(List<String> skipped, boolean gdWebp, boolean gdAvif) throws Exception {
        StringBuilder kept = new StringBuilder();
        for (String line : Files.readAllLines(KNOWN, StandardCharsets.UTF_8)) {
            String text = line.strip();
            if (!text.isEmpty() && !text.startsWith("#")) {
                int space = text.indexOf(' ');
                String scenario = space < 0 ? text : text.substring(0, space);
                if (skipped.contains(scenario)
                        || (!gdWebp && text.contains("(needs gd_webp)"))
                        || (!gdAvif && text.contains("(needs gd_avif)"))) {
                    kept.append("# left out of this run: ");
                }
            }
            kept.append(line).append('\n');
        }
        Path effective = DUMPS.resolve("known-differences.effective.txt");
        Files.writeString(effective, kept, StandardCharsets.UTF_8);
        return effective;
    }

    /** Writes both sides' observations to {@code target/media-parity/<id>.json}, for reading a difference. */
    static void dump(MediaScenario scenario, List<Observed> wp, List<Observed> qp) throws Exception {
        ObjectNode out = OracleJson.MAPPER.createObjectNode();
        out.put("id", scenario.id());
        out.set("wordpress", observations(wp));
        out.set("quarkpress", observations(qp));
        OracleJson.MAPPER
                .writerWithDefaultPrettyPrinter()
                .writeValue(DUMPS.resolve(scenario.id() + ".json").toFile(), out);
    }

    private static ArrayNode observations(List<Observed> observed) {
        ArrayNode steps = OracleJson.MAPPER.createArrayNode();
        for (Observed o : observed) {
            ObjectNode step = steps.addObject();
            step.put("step", o.step()).put("kind", o.kind()).put("at", o.at().toString());
            step.put("accepted", o.outcome().accepted())
                    .put("id", o.outcome().id())
                    .put("code", o.outcome().code())
                    .put("message", o.outcome().message());
            o.attachment().ifPresent(a -> step.set("attachment", a));
            o.files().ifPresent(files -> files.forEach(step.putArray("files")::add));
            o.ids().ifPresent(ids -> ids.forEach(step.putArray("ids")::add));
            ObjectNode featured = step.putObject("featured");
            o.featured().forEach((ref, ids) -> ids.forEach(featured.withArray(ref)::add));
            ObjectNode refs = step.putObject("refs");
            o.posts().forEach((id, ref) -> refs.put(ref, id));
            o.users().forEach((id, ref) -> refs.put(ref, id));
        }
        return steps;
    }
}
