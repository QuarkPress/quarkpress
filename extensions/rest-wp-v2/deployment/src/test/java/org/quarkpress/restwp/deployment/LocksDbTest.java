package org.quarkpress.restwp.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.test.QuarkusExtensionTest;
import io.restassured.response.Response;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;

/**
 * The editor's lock routes {@code /quarkpress/v1/locks/<id>}: take, the other user's take and refresh, takeover,
 * the holder's refresh and release, with the stored values and the answers WordPress's edit screen, heartbeat and
 * admin-ajax give; the one-time release token bound to the lock value that stands in for a caller without
 * credentials; the refusals; another site's post; and that REST writes ignore locks. Members of the site: the
 * author carol (Carol Chen), the editor bob (Bob Baker) and the subscriber erin; the clock is the test's.
 */
class LocksDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = RestApp.network(
            LocksDbTest.class, BookType.class, PostFixtures.class, PostFixtures.Builder.class, TestClock.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final Instant NOW = Instant.parse("2026-10-18T10:00:00Z");

    private static final String LOCKS = "/wp-json/quarkpress/v1/locks/";

    private static final String GRAVATAR = "https://secure.gravatar.com/avatar/";

    private static final String CANNOT_EDIT = "Sorry, you are not allowed to edit this post.";

    static AgroalDataSource data;
    static SiteId site;
    static String host;
    static long carol;
    static long bob;
    static long erin;

    @Inject
    TestClock clock;

    @BeforeAll
    static void members() {
        data = Jdbc.dataSource();
        site = TestData.site(data);
        host = Jdbc.strings(data, "select domain from qp_site where id = ?", site.value())
                .get(0);
        carol = member("carol", "Carol Chen", "carol@example.com", "author");
        bob = member("bob", "Bob Baker", "bob@example.com", "editor");
        erin = member("erin", "Erin Evans", "erin@example.com", "subscriber");
    }

    private static long member(String nicename, String displayName, String email, String role) {
        UserId user = TestData.profile(data, nicename, displayName, email);
        TestData.member(data, site, user, "[\"" + role + "\"]");
        return user.value();
    }

    @Test
    void takeRefreshTakeOverAndRelease() {
        clock.set(NOW);
        PostId post = draftOf(carol);
        String carolsLock = NOW.getEpochSecond() + ":" + carol;
        Response taken = as(carol).post(LOCKS + post.value());
        taken.then()
                .statusCode(200)
                .body("isLocked", equalTo(false))
                .body("activePostLock", equalTo(carolsLock))
                .body("release_token", not(nullValue()));
        assertThat(taken.jsonPath().getString("release_token")).hasSize(43);
        assertThat(json(taken).fieldNames())
                .toIterable()
                .containsExactly("isLocked", "activePostLock", "release_token");
        assertThat(storedLock(post)).isEqualTo(carolsLock);

        // the other user's take: the dialog's data, nothing taken
        Response held = as(bob).post(LOCKS + post.value());
        held.then()
                .statusCode(200)
                .body("isLocked", equalTo(true))
                .body("user.name", equalTo("Carol Chen"))
                .body("user.avatar", equalTo(avatar("carol@example.com", 128)));
        assertThat(json(held).fieldNames()).toIterable().containsExactly("isLocked", "user");
        assertThat(json(held).get("user").fieldNames()).toIterable().containsExactly("name", "avatar");
        assertThat(storedLock(post)).isEqualTo(carolsLock);

        // the other user's refresh: the heartbeat's lock_error, nothing taken
        Response error = as(bob).put(LOCKS + post.value());
        error.then()
                .statusCode(200)
                .body("lock_error.name", equalTo("Carol Chen"))
                .body("lock_error.text", equalTo("Carol Chen has taken over and is currently editing."))
                .body("lock_error.avatar_src", equalTo(avatar("carol@example.com", 64)))
                .body("lock_error.avatar_src_2x", equalTo(avatar("carol@example.com", 128)));
        assertThat(json(error).fieldNames()).toIterable().containsExactly("lock_error");
        assertThat(json(error).get("lock_error").fieldNames())
                .toIterable()
                .containsExactly("name", "text", "avatar_src", "avatar_src_2x");
        assertThat(storedLock(post)).isEqualTo(carolsLock);

        // the takeover moves the lock; the previous holder's refresh then names the taker
        String bobsLock = NOW.getEpochSecond() + ":" + bob;
        Response takenOver = as(bob).post(LOCKS + post.value() + "/takeover");
        takenOver.then().statusCode(200).body("isLocked", equalTo(false)).body("activePostLock", equalTo(bobsLock));
        assertThat(takenOver.jsonPath().getString("release_token")).hasSize(43);
        assertThat(storedLock(post)).isEqualTo(bobsLock);
        as(carol)
                .put(LOCKS + post.value())
                .then()
                .statusCode(200)
                .body("lock_error.name", equalTo("Bob Baker"))
                .body("lock_error.text", equalTo("Bob Baker has taken over and is currently editing."))
                .body("lock_error.avatar_src", equalTo(avatar("bob@example.com", 64)))
                .body("lock_error.avatar_src_2x", equalTo(avatar("bob@example.com", 128)));
        assertThat(storedLock(post)).isEqualTo(bobsLock);

        // the holder's refresh: new_lock with a fresh release token
        Response refreshed = as(bob).put(LOCKS + post.value());
        refreshed.then().statusCode(200).body("new_lock", equalTo(bobsLock));
        assertThat(refreshed.jsonPath().getString("release_token"))
                .hasSize(43)
                .isNotEqualTo(takenOver.jsonPath().getString("release_token"));
        assertThat(json(refreshed).fieldNames()).toIterable().containsExactly("new_lock", "release_token");

        // the release backdates the holder's own value to expire five seconds later
        String releasedLock = (NOW.getEpochSecond() - 145) + ":" + bob;
        as(bob).contentType("application/json")
                .body("{\"active_post_lock\":\"" + bobsLock + "\"}")
                .delete(LOCKS + post.value())
                .then()
                .statusCode(200)
                .body("released", equalTo(true));
        assertThat(storedLock(post)).isEqualTo(releasedLock);
        // a stale value, and another user's release of the holder's value: nothing changes
        as(bob).contentType("application/json")
                .body("{\"active_post_lock\":\"" + bobsLock + "\"}")
                .delete(LOCKS + post.value())
                .then()
                .statusCode(200)
                .body("released", equalTo(false));
        as(carol)
                .contentType("application/json")
                .body("{\"active_post_lock\":\"" + releasedLock + "\"}")
                .delete(LOCKS + post.value())
                .then()
                .statusCode(200)
                .body("released", equalTo(false));
        assertThat(storedLock(post)).isEqualTo(releasedLock);
        // the released lock is fresh for five more seconds, then the other user takes the post
        as(carol).post(LOCKS + post.value()).then().statusCode(200).body("isLocked", equalTo(true));
        clock.plus(Duration.ofSeconds(5));
        String carolsLater = NOW.plusSeconds(5).getEpochSecond() + ":" + carol;
        as(carol)
                .post(LOCKS + post.value())
                .then()
                .statusCode(200)
                .body("isLocked", equalTo(false))
                .body("activePostLock", equalTo(carolsLater));
        assertThat(storedLock(post)).isEqualTo(carolsLater);

        // without show_avatars, no avatar anywhere
        Jdbc.option(site, "show_avatars", "false");
        try {
            Response noAvatar = as(bob).post(LOCKS + post.value());
            noAvatar.then().statusCode(200).body("isLocked", equalTo(true)).body("user.name", equalTo("Carol Chen"));
            assertThat(json(noAvatar).get("user").fieldNames()).toIterable().containsExactly("name");
            Response noAvatars = as(bob).put(LOCKS + post.value());
            noAvatars.then().statusCode(200).body("lock_error.name", equalTo("Carol Chen"));
            assertThat(json(noAvatars).get("lock_error").fieldNames())
                    .toIterable()
                    .containsExactly("name", "text");
        } finally {
            Jdbc.deleteOption(site, "show_avatars");
        }
        assertThat(storedLock(post)).isEqualTo(carolsLater);
    }

    @Test
    void theReleaseTokenIsOneTimeAndBoundToTheLockValue() {
        clock.set(NOW);
        PostId post = draftOf(carol);
        Response first = as(carol).post(LOCKS + post.value());
        String firstLock = first.jsonPath().getString("activePostLock");
        String firstToken = first.jsonPath().getString("release_token");
        // a refresh one second later answers a new value and a new token
        clock.plus(Duration.ofSeconds(1));
        Response second = as(carol).put(LOCKS + post.value());
        String secondLock = second.jsonPath().getString("new_lock");
        String secondToken = second.jsonPath().getString("release_token");
        assertThat(secondLock).isNotEqualTo(firstLock);
        assertThat(secondToken).hasSize(43).isNotEqualTo(firstToken);

        // the refresh replaced the token: the one of the older value is gone (one token per user and post)
        beacon(post, firstLock, firstToken)
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_edit"))
                .body("message", equalTo(CANNOT_EDIT))
                .body("data.status", equalTo(401));
        assertThat(storedLock(post)).isEqualTo(secondLock);
        // bob takes over: carol's token is valid for her value, but the compare-and-set finds bob's
        String bobsLock =
                as(bob).post(LOCKS + post.value() + "/takeover").jsonPath().getString("activePostLock");
        beacon(post, secondLock, secondToken).then().statusCode(200).body("released", equalTo(false));
        assertThat(storedLock(post)).isEqualTo(bobsLock);
        // consumed either way: the same token again is nobody's credential
        beacon(post, secondLock, secondToken)
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_edit"))
                .body("message", equalTo(CANNOT_EDIT));
        // the current value with its token releases once
        Response third = as(carol).post(LOCKS + post.value() + "/takeover");
        String thirdLock = third.jsonPath().getString("activePostLock");
        String thirdToken = third.jsonPath().getString("release_token");
        beacon(post, thirdLock, thirdToken).then().statusCode(200).body("released", equalTo(true));
        assertThat(storedLock(post)).isEqualTo((NOW.getEpochSecond() + 1 - 145) + ":" + carol);
        beacon(post, thirdLock, thirdToken)
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_edit"))
                .body("message", equalTo(CANNOT_EDIT));

        // a token presented with another value than it was issued for is refused and spent
        clock.plus(Duration.ofSeconds(1));
        Response fourth = as(carol).put(LOCKS + post.value());
        String fourthLock = fourth.jsonPath().getString("new_lock");
        String fourthToken = fourth.jsonPath().getString("release_token");
        beacon(post, thirdLock, fourthToken).then().statusCode(401).body("code", equalTo("rest_cannot_edit"));
        beacon(post, fourthLock, fourthToken).then().statusCode(401).body("code", equalTo("rest_cannot_edit"));
        assertThat(storedLock(post)).isEqualTo(fourthLock);
        // a token of another post stands for nobody here
        PostId other = draftOf(carol);
        Response others = as(carol).post(LOCKS + other.value());
        String otherLock = others.jsonPath().getString("activePostLock");
        String otherToken = others.jsonPath().getString("release_token");
        beacon(post, fourthLock, otherToken).then().statusCode(401).body("code", equalTo("rest_cannot_edit"));
        // a fresh token with a value naming another user: the token is bound to its user
        clock.plus(Duration.ofSeconds(1));
        Response fifth = as(carol).put(LOCKS + post.value());
        String fifthLock = fifth.jsonPath().getString("new_lock");
        String fifthToken = fifth.jsonPath().getString("release_token");
        beacon(post, fifthLock.replace(":" + carol, ":" + bob), fifthToken)
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_edit"));
        assertThat(storedLock(post)).isEqualTo(fifthLock);
        // the token's user must still have edit_post when the beacon fires: demoted, her valid token is refused
        // and spent, the lock untouched
        roles(carol, "subscriber");
        try {
            beacon(post, fifthLock, fifthToken)
                    .then()
                    .statusCode(401)
                    .body("code", equalTo("rest_cannot_edit"))
                    .body("message", equalTo(CANNOT_EDIT));
        } finally {
            roles(carol, "author");
        }
        assertThat(storedLock(post)).isEqualTo(fifthLock);
        beacon(post, fifthLock, fifthToken).then().statusCode(401).body("code", equalTo("rest_cannot_edit"));
        // a caller with credentials releases with its own rights; the token in its body is left alone
        as(carol)
                .contentType("application/json")
                .body("{\"active_post_lock\":\"" + fifthLock + "\",\"release_token\":\"" + otherToken + "\"}")
                .delete(LOCKS + post.value())
                .then()
                .statusCode(200)
                .body("released", equalTo(true));
        beacon(other, otherLock, otherToken).then().statusCode(200).body("released", equalTo(true));
    }

    @Test
    void refusals() {
        clock.set(NOW);
        PostId post = draftOf(carol);
        String carolsLock = as(carol).post(LOCKS + post.value()).jsonPath().getString("activePostLock");
        // a subscriber: 403 on every route, the lock untouched
        for (Response refused : List.of(
                as(erin).post(LOCKS + post.value()),
                as(erin).put(LOCKS + post.value()),
                as(erin).post(LOCKS + post.value() + "/takeover"),
                as(erin).contentType("application/json")
                        .body("{\"active_post_lock\":\"" + carolsLock + "\"}")
                        .delete(LOCKS + post.value()))) {
            refused.then()
                    .statusCode(403)
                    .body("code", equalTo("rest_cannot_edit"))
                    .body("message", equalTo(CANNOT_EDIT))
                    .body("data.status", equalTo(403));
        }
        // nobody: 401, with or without a body, and with a token that is no token
        for (Response refused : List.of(
                as(null).post(LOCKS + post.value()),
                as(null).put(LOCKS + post.value()),
                as(null).post(LOCKS + post.value() + "/takeover"),
                as(null).contentType("application/json")
                        .body("{\"active_post_lock\":\"" + carolsLock + "\"}")
                        .delete(LOCKS + post.value()),
                as(null).post(LOCKS + post.value() + "?_method=DELETE"),
                beacon(post, carolsLock, "x".repeat(43)),
                beacon(post, "", ""))) {
            refused.then()
                    .statusCode(401)
                    .body("code", equalTo("rest_cannot_edit"))
                    .body("message", equalTo(CANNOT_EDIT))
                    .body("data.status", equalTo(401));
        }
        assertThat(storedLock(post)).isEqualTo(carolsLock);
        // a missing post, the id 0, and a post of a type REST does not show (here one no module registered)
        long missing = post.value() + 100_000;
        PostId revision = PostFixtures.builder(site)
                .type("revision")
                .status("inherit")
                .author(carol)
                .parent(post)
                .insert(data);
        for (Response refused : List.of(
                as(carol).post(LOCKS + missing),
                as(carol).put(LOCKS + missing),
                as(carol).post(LOCKS + missing + "/takeover"),
                as(carol)
                        .contentType("application/json")
                        .body("{\"active_post_lock\":\"" + carolsLock + "\"}")
                        .delete(LOCKS + missing),
                as(carol).post(LOCKS + "0"),
                as(bob).post(LOCKS + revision.value()),
                as(bob).put(LOCKS + revision.value()))) {
            refused.then()
                    .statusCode(404)
                    .body("code", equalTo("rest_post_invalid_id"))
                    .body("message", equalTo("Invalid post ID."))
                    .body("data.status", equalTo(404));
        }
        // nobody learns whether a post exists: the token is checked first
        beacon(PostId.of(missing), carolsLock, "x".repeat(43))
                .then()
                .statusCode(401)
                .body("code", equalTo("rest_cannot_edit"));
        assertThat(storedLock(post)).isEqualTo(carolsLock);
        assertThat(storedLock(revision)).isNull();
    }

    @Test
    void anotherSitesPostIs404() {
        clock.set(NOW);
        SiteId other = TestData.site(data);
        TestData.member(data, other, UserId.of(bob), "[\"administrator\"]");
        PostId theirs = PostFixtures.builder(other)
                .status("draft")
                .author(bob)
                .title("Theirs")
                .publishedAt(null)
                .insert(data);
        for (Response refused : List.of(
                as(bob).post(LOCKS + theirs.value()),
                as(bob).put(LOCKS + theirs.value()),
                as(bob).post(LOCKS + theirs.value() + "/takeover"),
                as(bob).contentType("application/json")
                        .body("{\"active_post_lock\":\"" + NOW.getEpochSecond() + ":" + bob + "\"}")
                        .delete(LOCKS + theirs.value()))) {
            refused.then()
                    .statusCode(404)
                    .body("code", equalTo("rest_post_invalid_id"))
                    .body("message", equalTo("Invalid post ID."));
        }
        assertThat(storedLock(theirs)).isNull();
    }

    @Test
    void restWritesIgnoreLocks() {
        clock.set(NOW);
        PostId post = draftOf(carol);
        String bobsLock = as(bob).post(LOCKS + post.value()).jsonPath().getString("activePostLock");
        as(carol)
                .contentType("application/json")
                .body("{\"title\":\"Edited under a lock\"}")
                .put("/wp-json/wp/v2/posts/" + post.value())
                .then()
                .statusCode(200)
                .body("title.raw", equalTo("Edited under a lock"));
        assertThat(storedLock(post)).isEqualTo(bobsLock);
    }

    /** A request to the test site as the user with this id, or anonymous when null. */
    private static RequestSpecification as(Long user) {
        return Http.at(host, user == null ? null : "test-" + user);
    }

    /** What the editor's {@code sendBeacon} sends on leaving: a JSON blob posted without credentials. */
    private static Response beacon(PostId post, String lockValue, String token) {
        return as(null).contentType("application/json")
                .body("{\"active_post_lock\":\"" + lockValue + "\",\"release_token\":\"" + token + "\"}")
                .post(LOCKS + post.value() + "?_method=DELETE");
    }

    /** Sets the user's roles on the test site to {@code role} alone, past the services. */
    private static void roles(long user, String role) {
        Jdbc.update(
                data,
                "update qp_site_membership set roles = " + TestDb.json() + " where site_id = ? and user_id = ?",
                "[\"" + role + "\"]",
                site.value(),
                user);
    }

    private static PostId draftOf(long author) {
        return PostFixtures.builder(site)
                .status("draft")
                .author(author)
                .title("Locked post")
                .publishedAt(null)
                .insert(data);
    }

    /** The stored {@code _edit_lock} value, read past the services; null when the post has none. */
    private static String storedLock(PostId post) {
        String meta = Jdbc.strings(data, "select meta from qp_post where id = ?", post.value())
                .get(0);
        JsonNode lock = json(meta).path("_edit_lock").path(0);
        return lock.isMissingNode() ? null : lock.textValue();
    }

    /** WordPress's avatar URL on a site with the default image and rating options unset. */
    private static String avatar(String email, int size) {
        return GRAVATAR + sha256(email) + "?s=" + size + "&d=mm&r=g";
    }

    private static String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static JsonNode json(Response response) {
        return json(response.asString());
    }

    private static JsonNode json(String text) {
        try {
            return MAPPER.readTree(text);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
