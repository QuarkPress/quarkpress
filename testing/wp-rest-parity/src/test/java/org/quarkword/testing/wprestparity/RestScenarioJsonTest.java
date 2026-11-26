package org.quarkword.testing.wprestparity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.quarkword.testing.wprestparity.RestScenario.SetupStep;

/** The front-end scenario format: the {@code front} flag, {@code update} steps and requests in a login session. */
class RestScenarioJsonTest {

    static RestScenario scenario(String id) {
        return RestScenarios.handWritten().stream()
                .filter(s -> s.id().equals(id))
                .findFirst()
                .orElseThrow();
    }

    static String front(String setup, String requests) {
        return "{\"id\":\"fx\",\"front\":true,\"rule\":\"r\",\"setup\":[" + setup + "],\"requests\":[" + requests
                + "]}";
    }

    static final String STRUCTURE = "{\"option\":\"permalink_structure\",\"value\":\"\"}";

    static final String DRAFT =
            "{\"post\":\"p:draft\",\"as\":\"carol\",\"type\":\"post\",\"status\":\"draft\",\"title\":\"d\"}";

    @Test
    void fr01IsAFrontEndScenarioInPlainModeWithASessionRequest() {
        RestScenario fr01 = scenario("fr01");
        assertThat(fr01.front()).isTrue();
        assertThat(fr01.editor()).isFalse();
        assertThat(fr01.setup().get(0)).isInstanceOf(SetupStep.Option.class);
        SetupStep.Option structure = (SetupStep.Option) fr01.setup().get(0);
        assertThat(structure.name()).isEqualTo("permalink_structure");
        assertThat(structure.value().textValue()).isEmpty();
        assertThat(fr01.requests()).allMatch(call -> call.as().equals("anonymous"));
        assertThat(fr01.requests().stream().filter(call -> call.session().isPresent()))
                .extracting(call -> call.session().get(), RestCall::target)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("alice", "/?p={p:priv}"));
        // the REST scenarios stay what they were
        assertThat(scenario("d01").front()).isFalse();
    }

    @Test
    void fr06UpdatesPostsThroughRest() {
        RestScenario fr06 = scenario("fr06");
        List<SetupStep.Update> updates = fr06.setup().stream()
                .filter(SetupStep.Update.class::isInstance)
                .map(SetupStep.Update.class::cast)
                .toList();
        assertThat(updates).extracting(SetupStep.Update::postRef).containsExactly("p:hello", "p:second", "p:dune");
        assertThat(updates.get(0).type()).isEqualTo("post");
        assertThat(updates.get(0).as()).isEqualTo("alice");
        assertThat(updates.get(0).fields().get("slug").textValue()).isEqualTo("hello-world");
        assertThat(updates.get(2).type()).isEqualTo("book");
        assertThat(Side.postBase(updates.get(2).type())).isEqualTo("books");
        assertThat(Side.updateFields(updates.get(1), Map.of("p:second", 4L)).toString())
                .isEqualTo("{\"date_gmt\":\"2025-11-23T08:00:00\"}");
    }

    @Test
    void fr10ResolvesItsPreviewPlaceholdersToTheSidesPreviewLinks() throws Exception {
        RestScenario fr10 = scenario("fr10");
        int index = 0;
        while (!fr10.requests().get(index).target().contains(RestScenario.PREVIEW)) {
            index++;
        }
        RestCall preview = fr10.requests().get(index);
        assertThat(preview.target()).isEqualTo("/?{preview:p:draft}");
        assertThat(preview.session()).contains("alice");
        RestCall call = fr10.call(index, Map.of("p:draft", 12L, "u:alice", 2L));
        // the ref is resolved to the post's REST base and id; the side replaces the placeholder
        assertThat(call.target()).isEqualTo("/?{preview:posts:12}");
        assertThat(call.session()).contains("alice");
        String sent = Side.resolvePreview(call.target(), post -> {
            assertThat(post).isEqualTo(new Side.PreviewTarget("posts", 12));
            return "http://localhost/fr10/?p=12&preview_id=12&preview_nonce=abcdef1234&preview=true#main";
        });
        assertThat(sent).isEqualTo("/?p=12&preview_id=12&preview_nonce=abcdef1234&preview=true");
        RestScenario media = scenario("fr07");
        assertThat(media.postTypes()).containsEntry("m:shot", "attachment");
        assertThat(Side.postBase("attachment")).isEqualTo("media");
        assertThat(Side.postBase("page")).isEqualTo("pages");
        assertThat(Side.postBase("wp_block")).isEqualTo("wp_block");
    }

    @Test
    void anOptionValueNamingARefIsThatRefsIdOnEachSide() {
        SetupStep.Option onFront = scenario("fr05").setup().stream()
                .filter(SetupStep.Option.class::isInstance)
                .map(SetupStep.Option.class::cast)
                .filter(option -> option.name().equals("page_on_front"))
                .findFirst()
                .orElseThrow();
        assertThat(onFront.ref()).contains("p:front");
        assertThat(onFront.valueOn(Map.of("p:front", 9L)).isIntegralNumber()).isTrue();
        assertThat(onFront.valueOn(Map.of("p:front", 9L)).longValue()).isEqualTo(9L);
        assertThatThrownBy(() -> RestScenarioJson.read(
                        front(STRUCTURE + ",{\"option\":\"page_on_front\",\"value\":\"{p:later}\"}", get("/"))))
                .hasMessageContaining("p:later is used before it is defined");
    }

    @Test
    void theFrontEndKeysSurviveWritingAndReading() {
        for (String id : List.of("fr01", "fr05", "fr06", "fr10", "fr11")) {
            RestScenario scenario = scenario(id);
            assertThat(RestScenarioJson.read(RestScenarioJson.write(scenario)))
                    .usingRecursiveComparison()
                    .isEqualTo(scenario);
        }
        RestCall postpass = scenario("fr11").requests().get(1);
        assertThat(postpass.method()).isEqualTo("POST");
        assertThat(postpass.contentType()).contains("application/x-www-form-urlencoded");
        assertThat(new String(postpass.body().orElseThrow(), StandardCharsets.UTF_8))
                .startsWith("post_password=s3&");
    }

    @Test
    void aFrontEndScenarioIsRefusedWhenItBreaksItsRules() {
        assertThatThrownBy(() -> RestScenarioJson.read(front("", get("/"))))
                .hasMessageContaining("first setup step sets permalink_structure");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        front(STRUCTURE, "{\"as\":\"alice\",\"method\":\"GET\",\"target\":\"/\"}")))
                .hasMessageContaining("sent by an anonymous visitor");
        assertThatThrownBy(() -> RestScenarioJson.read(front(STRUCTURE, session("/", "invalid"))))
                .hasMessageContaining("unknown caller 'invalid'");
        assertThatThrownBy(() -> RestScenarioJson.read(front(STRUCTURE + "," + DRAFT, get("/?{preview:p:draft}"))))
                .hasMessageContaining("a preview placeholder is the session's preview link");
        assertThatThrownBy(() -> RestScenarioJson.read(front(STRUCTURE, session("/?{preview:p:draft}", "alice"))))
                .hasMessageContaining("p:draft is used before it is defined");
        assertThatThrownBy(() -> RestScenarioJson.read(front(
                        STRUCTURE + "," + DRAFT
                                + ",{\"update\":\"p:draft\",\"type\":\"post\",\"as\":\"carol\",\"fields\":{\"thumbnail_meta\":\"p:draft\"}}",
                        get("/"))))
                .hasMessageContaining("not thumbnail_meta");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        front(STRUCTURE + ",{\"update\":\"p:nope\",\"type\":\"post\",\"as\":\"carol\"}", get("/"))))
                .hasMessageContaining("p:nope is used before it is defined");
        assertThatThrownBy(() -> RestScenarioJson.read(front(
                        STRUCTURE + "," + DRAFT
                                + ",{\"update\":\"p:draft\",\"type\":\"post\",\"as\":\"carol\",\"x\":1}",
                        get("/"))))
                .hasMessageContaining("unknown key x");
        // a session belongs to a front-end request only
        assertThatThrownBy(() -> RestScenarioJson.read(
                        "{\"id\":\"rx\",\"rule\":\"r\",\"requests\":[" + session("/wp-json/", "alice") + "]}"))
                .hasMessageContaining("only a front-end request carries a session");
        assertThatThrownBy(() -> RestScenarioJson.read(
                        "{\"id\":\"rx\",\"front\":\"yes\",\"rule\":\"r\",\"requests\":[" + get("/") + "]}"))
                .hasMessageContaining("front is true or false");
    }

    @Test
    void aFrontEndAnswerIsComparedByStatusAndItsHeadersAndTheCookiesByNamePathAndAttributes() {
        RestScenario scenario = RestScenarioJson.read(front(STRUCTURE, get("/")));
        RestCall call = scenario.requests().get(0);
        Exchange wp = new Exchange(
                call,
                302,
                Map.of(
                        "Set-Cookie",
                        List.of(
                                "wordpress_test_cookie=WP%20Cookie%20check; path=/; HttpOnly",
                                "wp-postpass_x=%24P; path=/"),
                        "Location",
                        List.of("http://localhost/fx/?p=7"),
                        "X-Redirect-By",
                        List.of("WordPress")),
                "<html>one</html>".getBytes(StandardCharsets.UTF_8));
        Exchange qp = new Exchange(
                call,
                302,
                Map.of(
                        "set-cookie",
                        List.of("wp-postpass_x=%24Q; expires=x; path=/"),
                        "location",
                        List.of("http://localhost/fx/?p=9"),
                        "X-Redirect-By",
                        List.of("QuarkWord")),
                "<html>two</html>".getBytes(StandardCharsets.UTF_8));
        org.quarkword.testing.wporacle.ParityRun run = new org.quarkword.testing.wporacle.ParityRun(
                "front",
                org.quarkword.testing.wporacle.ParitySkipList.empty(),
                java.nio.file.Path.of("target", "front-comparison-failures.json"));
        new RestComparison(Map.of("p:a", 7L), Map.of("p:a", 9L), "/fx/")
                .compare(scenario, List.of(wp), List.of(qp), run);
        assertThat(run.comparisons()).isEqualTo(1 + RestComparison.FRONT_HEADERS.size());
        assertThat(run.mismatches())
                .extracting(
                        org.quarkword.testing.wporacle.ParityRun.Mismatch::aspect,
                        org.quarkword.testing.wporacle.ParityRun.Mismatch::wordpress,
                        org.quarkword.testing.wporacle.ParityRun.Mismatch::quarkword)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(
                        "req1:header:set-cookie",
                        "wordpress_test_cookie; path=/; attributes=httponly,path | wp-postpass_x; path=/;"
                                + " attributes=path",
                        "wp-postpass_x; path=/; attributes=expires,path"));
    }

    static String get(String target) {
        return "{\"as\":\"anonymous\",\"method\":\"GET\",\"target\":\"" + target + "\"}";
    }

    static String session(String target, String user) {
        return "{\"as\":\"anonymous\",\"method\":\"GET\",\"target\":\"" + target + "\",\"session\":\"" + user + "\"}";
    }
}
