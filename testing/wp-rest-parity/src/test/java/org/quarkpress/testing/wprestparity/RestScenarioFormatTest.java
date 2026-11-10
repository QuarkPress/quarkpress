package org.quarkpress.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RestScenarioFormatTest {

    static String scenario(String setup, String requests) {
        return "{\"id\":\"x1\",\"rule\":\"r\",\"setup\":[" + setup + "],\"requests\":[" + requests + "]}";
    }

    @Test
    void everyHandWrittenScenarioReadsAndTheVerificationListIsComplete() {
        List<RestScenario> scenarios = RestScenarios.handWritten();
        Set<String> ids = new HashSet<>();
        for (RestScenario scenario : scenarios) {
            assertThat(ids.add(scenario.id())).as("unique id %s", scenario.id()).isTrue();
            // a scenario survives being written and read back
            assertThat(RestScenarioJson.read(RestScenarioJson.write(scenario)))
                    .usingRecursiveComparison()
                    .isEqualTo(scenario);
        }
        for (int item = 1; item <= 28; item++) {
            assertThat(ids).contains("d%02d".formatted(item));
        }
        assertThat(scenarios.get(0).id()).isEqualTo("d01");
    }

    @Test
    void aRefUsedBeforeItIsDefinedIsRefusedButTheStartingRefsAreKnown() {
        String uses = "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/categories/{t:news}\"}";
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("", uses)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("t:news is used before it is defined");
        String parentLater =
                "{\"term\":\"t:child\",\"as\":\"alice\",\"taxonomy\":\"category\",\"name\":\"c\",\"parent\":\"t:news\"},"
                        + "{\"term\":\"t:news\",\"as\":\"alice\",\"taxonomy\":\"category\",\"name\":\"n\"}";
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(parentLater, uses)))
                .hasMessageContaining("t:news is used before it is defined");
        RestScenario fine = RestScenarioJson.read(
                scenario(
                        "{\"term\":\"t:news\",\"as\":\"bob\",\"taxonomy\":\"category\",\"name\":\"n\",\"parent\":\"t:uncategorized\"}",
                        uses
                                + ",{\"as\":\"anonymous\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/users/{u:erin}?x={u:alice}\"}"));
        assertThat(fine.requests()).hasSize(2);
        assertThat(RestScenario.resolve(fine.requests().get(1), Map.of("u:erin", 6L, "u:alice", 2L))
                        .target())
                .isEqualTo("/wp-json/wp/v2/users/6?x=2");
    }

    @Test
    void onlyTheSevenCallersAndKnownKeysAreAccepted() {
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario("", "{\"as\":\"mallory\",\"method\":\"GET\",\"target\":\"/wp-json/\"}")))
                .hasMessageContaining("unknown caller 'mallory'");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:1\",\"as\":\"anonymous\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"t\"}",
                        "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/\"}")))
                .hasMessageContaining("unknown caller 'anonymous'");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario("", "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/\",\"bdy\":\"x\"}")))
                .hasMessageContaining("unknown key bdy");
        for (String as : RestScenario.CALLERS) {
            RestScenarioJson.read(scenario("", "{\"as\":\"" + as + "\",\"method\":\"GET\",\"target\":\"/wp-json/\"}"));
        }
    }

    @Test
    void aUserLeavesTheSiteButAliceNever() {
        String get = "{\"as\":\"bob\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/users/me\"}";
        RestScenario read = RestScenarioJson.read(scenario("{\"leave\":\"bob\"}", get));
        assertThat(read.setup()).containsExactly(new RestScenario.SetupStep.Leave("bob"));
        assertThat(RestScenarioJson.read(RestScenarioJson.write(read)))
                .usingRecursiveComparison()
                .isEqualTo(read);
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("{\"leave\":\"alice\"}", get)))
                .hasMessageContaining("alice, the super admin, never leaves a site");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("{\"leave\":\"anonymous\"}", get)))
                .hasMessageContaining("unknown caller 'anonymous'");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("{\"leave\":\"bob\",\"site\":\"x\"}", get)))
                .hasMessageContaining("unknown key site");
    }

    @Test
    void contentStepsReadAndValidate() {
        String get = "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/posts/{p:a}/revisions/{r:v1}\"}";
        String steps =
                "{\"post\":\"p:img\",\"as\":\"alice\",\"type\":\"attachment\",\"status\":\"inherit\",\"title\":\"i\"},"
                        + "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"publish\",\"title\":\"t\","
                        + "\"fields\":{\"content\":\"c\",\"excerpt\":\"e\",\"password\":\"s3\",\"date_gmt\":\"2026-07-15T10:00:00\","
                        + "\"slug\":\"s\",\"menu_order\":2,\"comment_status\":\"closed\",\"sticky\":true,"
                        + "\"categories\":[\"t:uncategorized\"],\"tags\":[],\"format\":\"aside\",\"featured_media\":\"p:img\","
                        + "\"template\":\"\",\"meta\":{\"footnotes\":\"[]\"}}},"
                        + "{\"post\":\"p:child\",\"as\":\"bob\",\"type\":\"page\",\"status\":\"draft\",\"title\":\"c\",\"fields\":{\"parent\":\"p:a\"}},"
                        + "{\"revision\":\"r:v1\",\"as\":\"carol\",\"post\":\"p:a\",\"title\":\"t2\",\"content\":\"c2\"},"
                        + "{\"autosave\":\"r:auto\",\"as\":\"bob\",\"post\":\"p:a\",\"title\":\"t3\",\"content\":\"c3\"},"
                        + "{\"lock\":\"p:a\",\"user\":\"carol\",\"age\":10},"
                        + "{\"own_site\":\"dave\"}";
        RestScenario read = RestScenarioJson.read(scenario(steps, get));
        assertThat(read.setup()).hasSize(7);
        RestScenario.SetupStep.Post post =
                (RestScenario.SetupStep.Post) read.setup().get(1);
        assertThat(post.fields().get("menu_order").intValue()).isEqualTo(2);
        assertThat(post.fields().get("categories").get(0).textValue()).isEqualTo("t:uncategorized");
        assertThat(((RestScenario.SetupStep.Post) read.setup().get(0)).fields().isEmpty())
                .isTrue();
        assertThat(read.setup().get(3))
                .isEqualTo(new RestScenario.SetupStep.Revision("r:v1", "carol", "p:a", "t2", "c2"));
        assertThat(read.setup().get(4))
                .isEqualTo(new RestScenario.SetupStep.Autosave("r:auto", "bob", "p:a", "t3", "c3"));
        assertThat(read.setup().get(5)).isEqualTo(new RestScenario.SetupStep.Lock("p:a", "carol", 10));
        assertThat(read.setup().get(6)).isEqualTo(new RestScenario.SetupStep.OwnSite("dave"));
        assertThat(RestScenarioJson.read(RestScenarioJson.write(read)))
                .usingRecursiveComparison()
                .isEqualTo(read);
        assertThat(RestScenarioJson.write(read)).doesNotContain("\"fields\" : { }");
        // an r: ref is defined by a revision or an autosave step only, and resolves like the others
        assertThat(RestScenario.resolve(read.requests().get(0), Map.of("p:a", 4L, "r:v1", 9L))
                        .target())
                .isEqualTo("/wp-json/wp/v2/posts/4/revisions/9");
    }

    @Test
    void aDraftStepDefinesAPostRef() {
        String get = "{\"as\":\"carol\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/posts/{p:new}?context=edit\"}";
        RestScenario read =
                RestScenarioJson.read(scenario("{\"draft\":\"p:new\",\"as\":\"carol\",\"type\":\"post\"}", get));
        assertThat(read.setup()).containsExactly(new RestScenario.SetupStep.Draft("p:new", "carol", "post"));
        assertThat(RestScenarioJson.read(RestScenarioJson.write(read)))
                .usingRecursiveComparison()
                .isEqualTo(read);
        assertThat(RestScenario.resolve(read.requests().get(0), Map.of("p:new", 7L))
                        .target())
                .isEqualTo("/wp-json/wp/v2/posts/7?context=edit");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario("{\"draft\":\"r:new\",\"as\":\"carol\",\"type\":\"post\"}", get)))
                .hasMessageContaining("r:new");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario("{\"draft\":\"p:new\",\"as\":\"anonymous\",\"type\":\"post\"}", get)))
                .hasMessageContaining("unknown caller 'anonymous'");
        assertThatThrownBy(() ->
                        RestScenarioJson.read(scenario("{\"draft\":\"p:new\",\"as\":\"carol\",\"type\":\"\"}", get)))
                .hasMessageContaining("a draft needs a type");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario("{\"draft\":\"p:new\",\"as\":\"carol\",\"type\":\"post\",\"title\":\"x\"}", get)))
                .hasMessageContaining("unknown key title");
    }

    static final String POST_A =
            "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"publish\",\"title\":\"t\"}";
    static final String GET_ROOT = "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/\"}";

    @Test
    void anRRefIsDefinedByARevisionOrAnAutosaveOnly() {
        String get = "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/posts/{p:a}/revisions/{r:v1}\"}";
        String postA = POST_A;
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(postA, get)))
                .hasMessageContaining("r:v1 is used before it is defined");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        postA
                                + ",{\"revision\":\"p:v1\",\"as\":\"carol\",\"post\":\"p:a\",\"title\":\"t\",\"content\":\"c\"}",
                        get)))
                .hasMessageContaining("not a r: ref: p:v1");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"r:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"publish\",\"title\":\"t\"}",
                        GET_ROOT)))
                .hasMessageContaining("not a p: ref: r:a");
    }

    @Test
    void aRevisionAnAutosaveOrALockNamesADefinedPostAndALockAKnownUser() {
        String postA = POST_A;
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"autosave\":\"r:x\",\"as\":\"carol\",\"post\":\"p:zz\",\"title\":\"t\",\"content\":\"c\"}",
                        GET_ROOT)))
                .hasMessageContaining("p:zz is used before it is defined");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario(postA + ",{\"lock\":\"t:uncategorized\",\"user\":\"carol\",\"age\":1}", GET_ROOT)))
                .hasMessageContaining("not a p: ref: t:uncategorized");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario(postA + ",{\"lock\":\"p:a\",\"user\":\"mallory\",\"age\":1}", GET_ROOT)))
                .hasMessageContaining("unknown caller 'mallory'");
    }

    @Test
    void anOwnSiteStepRefusesAlice() {
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("{\"own_site\":\"alice\"}", GET_ROOT)))
                .hasMessageContaining("alice, the super admin, never leaves a site");
    }

    @Test
    void thePostFieldsAreCheckedForKeysShapesAndRefs() {
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"t\",\"fields\":{\"nope\":1}}",
                        GET_ROOT)))
                .hasMessageContaining("unknown key nope");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"t\","
                                + "\"fields\":{\"categories\":[\"u:alice\"]}}",
                        GET_ROOT)))
                .hasMessageContaining("not a t: ref: u:alice");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"page\",\"status\":\"draft\",\"title\":\"t\","
                                + "\"fields\":{\"parent\":\"p:zz\"}}",
                        GET_ROOT)))
                .hasMessageContaining("p:zz is used before it is defined");
        assertThatThrownBy(() -> RestScenarioJson.read(scenario(
                        "{\"post\":\"p:a\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"t\","
                                + "\"fields\":{\"sticky\":\"yes\"}}",
                        GET_ROOT)))
                .hasMessageContaining("sticky");
    }

    @Test
    void mediaSetupUploadAndRawRequestsRoundTrip() {
        String json = "{\"id\":\"x1\",\"rule\":\"r\",\"editor\":true,\"setup\":["
                + "{\"post\":\"p:draft\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"d\"},"
                + "{\"media\":\"m:photo\",\"as\":\"carol\",\"fixture\":{\"fixture\":\"jpeg\",\"args\":[64,48]},"
                + "\"name\":\"photo.jpg\",\"type\":\"image/jpeg\","
                + "\"fields\":{\"post\":\"p:draft\",\"title\":\"A photo\",\"alt_text\":\"Alt\"}},"
                + "{\"post\":\"p:pictured\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"publish\","
                + "\"title\":\"p\",\"fields\":{\"featured_media\":\"m:photo\"}}],"
                + "\"requests\":["
                + "{\"as\":\"carol\",\"method\":\"POST\",\"target\":\"/wp-json/wp/v2/media\","
                + "\"upload\":{\"fixture\":{\"fixture\":\"png\",\"args\":[8,8]},\"name\":\"dot.png\","
                + "\"type\":\"image/png\",\"fields\":{\"post\":\"{p:draft}\",\"title\":\"after {m:photo}\"}}},"
                + "{\"as\":\"carol\",\"method\":\"POST\",\"target\":\"/wp-json/wp/v2/media\","
                + "\"contentType\":\"image/jpeg\",\"headerLines\":{\"Content-Disposition\":"
                + "[\"attachment; filename=a.jpg\",\"attachment; filename=b.jpg\"]},"
                + "\"raw\":{\"fixture\":{\"fixture\":\"jpeg\",\"args\":[16,16]}}},"
                + "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/wp/v2/media/{m:photo}\",\"https\":true}]}";
        RestScenario read = RestScenarioJson.read(json);
        assertThat(read.editor()).isTrue();
        RestScenario.SetupStep.Media media =
                (RestScenario.SetupStep.Media) read.setup().get(1);
        assertThat(media.ref()).isEqualTo("m:photo");
        assertThat(media.as()).isEqualTo("carol");
        assertThat(media.fixture().name()).isEqualTo("jpeg");
        assertThat(media.fixture().args().toString()).isEqualTo("[64,48]");
        assertThat(media.name()).isEqualTo("photo.jpg");
        assertThat(media.type()).isEqualTo("image/jpeg");
        assertThat(media.fields().toString())
                .isEqualTo("{\"post\":\"p:draft\",\"title\":\"A photo\",\"alt_text\":\"Alt\"}");
        // the scenario survives being written and read back, with its editor mark and every request form
        String written = RestScenarioJson.write(read);
        assertThat(RestScenarioJson.read(written)).usingRecursiveComparison().isEqualTo(read);
        assertThat(written).contains("\"editor\" : true", "\"https\" : true", "\"upload\" : {", "\"raw\" : {");
        assertThat(RestScenarioJson.write(RestScenarioJson.read(
                        scenario("", "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/\"}"))))
                .doesNotContain("editor", "https", "upload", "raw", "headerLines");

        Map<String, Long> refs = Map.of("p:draft", 4L, "m:photo", 9L);
        // an upload: a multipart body with the fixture's bytes as its file part and the fields, refs resolved
        RestCall upload = read.call(0, refs);
        assertThat(upload.method()).isEqualTo("POST");
        assertThat(upload.contentType())
                .hasValueSatisfying(type -> assertThat(type).startsWith("multipart/form-data; boundary="));
        String body = new String(upload.body().orElseThrow(), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(body)
                .contains("name=\"file\"; filename=\"dot.png\"\r\nContent-Type: image/png\r\n\r\n")
                .contains("name=\"post\"\r\n\r\n4\r\n")
                .contains("name=\"title\"\r\n\r\nafter 9\r\n");
        byte[] png = MediaScenarios.fixture(new MediaScenario.Fixture("png", OracleJsonArgs.of(8, 8)));
        assertThat(new String(png, java.nio.charset.StandardCharsets.ISO_8859_1))
                .isSubstringOf(body);
        // a raw body: the fixture's bytes as they are, with the content type and both header lines
        RestCall raw = read.call(1, refs);
        assertThat(raw.body())
                .hasValueSatisfying(bytes -> assertThat(bytes)
                        .isEqualTo(
                                MediaScenarios.fixture(new MediaScenario.Fixture("jpeg", OracleJsonArgs.of(16, 16)))));
        assertThat(raw.contentType()).hasValue("image/jpeg");
        assertThat(raw.headerLines())
                .containsExactly(Map.entry(
                        "Content-Disposition", List.of("attachment; filename=a.jpg", "attachment; filename=b.jpg")));
        // https: the marker header each side turns into its own form of the scheme; the target's ref resolved
        RestCall https = read.call(2, refs);
        assertThat(https.target()).isEqualTo("/wp-json/wp/v2/media/9");
        assertThat(https.headers()).containsExactly(Map.entry(Side.HTTPS_MARKER, "1"));
        assertThat(read.call(0, refs).headers()).isEmpty();

        // an unknown key of an upload is refused, naming the scenario and the key, as the other unknown keys are
        String typo = "{\"as\":\"carol\",\"method\":\"POST\",\"target\":\"/wp-json/wp/v2/media\","
                + "\"upload\":{\"fixture\":{\"fixture\":\"png\",\"args\":[8,8]},\"name\":\"dot.png\","
                + "\"type\":\"image/png\",\"feilds\":{}}}";
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("", typo)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x1")
                .hasMessageContaining("unknown key feilds");
        // an upload or a raw body is a POST without a body of its own; a media step's ref is an m: ref
        String put = "{\"as\":\"carol\",\"method\":\"PUT\",\"target\":\"/wp-json/wp/v2/media\","
                + "\"raw\":{\"fixture\":{\"fixture\":\"jpeg\",\"args\":[16,16]}}}";
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("", put)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x1");
        String both = "{\"as\":\"carol\",\"method\":\"POST\",\"target\":\"/wp-json/wp/v2/media\",\"body\":\"x\","
                + "\"raw\":{\"fixture\":{\"fixture\":\"jpeg\",\"args\":[16,16]}}}";
        assertThatThrownBy(() -> RestScenarioJson.read(scenario("", both)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("x1");
        String wrongRef = "{\"media\":\"p:photo\",\"as\":\"carol\",\"fixture\":{\"fixture\":\"jpeg\",\"args\":[8,8]},"
                + "\"name\":\"photo.jpg\",\"type\":\"image/jpeg\"}";
        assertThatThrownBy(() -> RestScenarioJson.read(
                        scenario(wrongRef, "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/\"}")))
                .hasMessageContaining("not a m: ref");
    }

    @Test
    void aMediaStepOfAnEditorScenarioMayAskForNoSizes() {
        String step = "{\"media\":\"m:big\",\"as\":\"carol\",\"fixture\":{\"fixture\":\"jpeg\",\"args\":[64,48]},"
                + "\"name\":\"big.jpg\",\"type\":\"image/jpeg\",\"fields\":{\"generate_sub_sizes\":\"false\"}}";
        RestScenario read =
                RestScenarioJson.read(scenario(step, "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/\"}"));
        RestScenario.SetupStep.Media media =
                (RestScenario.SetupStep.Media) read.setup().get(0);
        // sent as it is in an editor scenario; without an editor every setup upload makes no sizes anyway
        assertThat(Side.mediaFields(media, Map.of(), true)).containsExactly(Map.entry("generate_sub_sizes", "false"));
        assertThat(Side.mediaFields(media, Map.of(), false)).containsExactly(Map.entry("generate_sub_sizes", "false"));
        // "false" is its only value: a step without an editor would silently lose any other
        for (String other : List.of("true", "0", "")) {
            String asked = step.replace("\"generate_sub_sizes\":\"false\"", "\"generate_sub_sizes\":\"" + other + "\"");
            assertThatThrownBy(() -> RestScenarioJson.read(
                            scenario(asked, "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/wp-json/\"}")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("x1")
                    .hasMessageContaining("generate_sub_sizes is \"false\" or absent");
        }
    }

    /** Fixture arguments as a JSON array of numbers. */
    static final class OracleJsonArgs {
        static com.fasterxml.jackson.databind.JsonNode of(int... values) {
            com.fasterxml.jackson.databind.node.ArrayNode array =
                    org.quarkpress.testing.wporacle.OracleJson.MAPPER.createArrayNode();
            for (int value : values) {
                array.add(value);
            }
            return array;
        }
    }

    @Test
    void generatedScenariosAreReproduciblePerSeedAndValid() {
        List<RestScenario> first = RestScenarios.generated("rest-a", RestScenarios.GENERATED);
        List<RestScenario> again = RestScenarios.generated("rest-a", RestScenarios.GENERATED);
        List<RestScenario> other = RestScenarios.generated("rest-b", RestScenarios.GENERATED);
        assertThat(first).hasSize(RestScenarios.GENERATED);
        assertThat(first.stream().map(RestScenarioJson::write).toList())
                .isEqualTo(again.stream().map(RestScenarioJson::write).toList());
        assertThat(first.stream().map(RestScenarioJson::write).toList())
                .isNotEqualTo(other.stream().map(RestScenarioJson::write).toList());
        assertThat(first.get(0).id()).isEqualTo("rest-a-001");
        for (RestScenario scenario : first) {
            assertThat(scenario.requests()).hasSizeBetween(2, 6);
            for (RestCall call : scenario.requests()) {
                // the generator stays clear of request classes that differ as a whole (known differences)
                assertThat(call.as()).isNotEqualTo("invalid");
                assertThat(call.target()).doesNotContain("_jsonp");
                assertThat(call.headers()).isEmpty();
            }
            // every generated scenario reads back as written (refs defined before use)
            RestScenarioJson.read(RestScenarioJson.write(scenario));
        }
        assertThat(RestScenarios.all()).hasSize(RestScenarios.handWritten().size() + 2 * RestScenarios.GENERATED);
        assertThat(RestScenarios.isGenerated("rest-a-001")).isTrue();
        assertThat(RestScenarios.isGenerated("d01")).isFalse();
        assertThat(RestScenarios.isGenerated("x01")).isFalse();
    }
}
