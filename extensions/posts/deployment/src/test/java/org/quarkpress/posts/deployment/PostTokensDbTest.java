package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.Callable;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.persistence.Options;
import org.quarkpress.posts.LockReleaseTokens;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PreviewSessions;
import org.quarkpress.posts.PreviewTokens;

/**
 * Preview tokens (computed from the site's secret, never stored), preview sessions and one-time lock release tokens
 * (stored as hashes in {@code qp_post_token}). In multisite mode, so that a user who is not a member exists.
 */
class PostTokensDbTest {

    /** Half the default preview lifetime: the span of one preview tick. */
    private static final long TICK_SECONDS = 6 * 3600;

    /** One second past a tick boundary. */
    private static final long NOW = TICK_SECONDS * 50_000 + 1;

    private static final String SECRET = "quarkpress_preview_secret";

    /** Valid base64 of 31 bytes: one short of a secret. */
    private static final String SHORT_BASE64 = Base64.getEncoder().encodeToString(new byte[31]);

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.multisite(PostTokensDbTest.class, TestClock.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    PreviewTokens tokens;

    @Inject
    PreviewSessions sessions;

    @Inject
    LockReleaseTokens releases;

    @Inject
    Options options;

    @Inject
    TestClock clock;

    private SiteId site;
    private UserId bob;
    private UserId carol;
    private UserId erin;
    private UserId frank;
    private PostId post;
    private PostId otherPost;

    @BeforeEach
    void fixture() {
        clock.set(Instant.ofEpochSecond(NOW));
        site = TestData.site(dataSource);
        bob = member("editor");
        carol = member("author");
        erin = member("subscriber");
        frank = TestData.profile(dataSource, "nobody");
        post = draftOf(carol, "Preview me");
        otherPost = draftOf(carol, "Another");
    }

    @Test
    void previewTokensAreBoundToPostViewerAndTheViewersRights() throws Exception {
        long rows = tokenRows(site);
        assertThat(secretRows()).isZero();
        String token = as(carol, () -> tokens.issue(post, carol));
        assertThat(token).hasSize(27).matches(text -> text.chars().allMatch(PostTokensDbTest::base64url));
        assertThat(secretRows()).isEqualTo(1);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_option where site_id = ? and name = ? and autoload = false",
                        site.value(),
                        SECRET))
                .isEqualTo(1);
        assertThat(token).isEqualTo(expectedPreview(site, post, carol, NOW / TICK_SECONDS));
        assertThat(as(carol, () -> tokens.verify(post, token, carol))).isTrue();
        assertThat(as(carol, () -> tokens.verify(post, token, carol))).isTrue();
        assertThat(as(carol, () -> tokens.issue(post, carol))).isEqualTo(token);
        clock.plus(Duration.ofHours(6));
        String next = as(carol, () -> tokens.issue(post, carol));
        assertThat(next).isNotEqualTo(token).hasSize(27);
        assertThat(as(carol, () -> tokens.verify(post, token, carol))).isTrue();
        clock.plus(Duration.ofHours(6));
        assertThat(as(carol, () -> tokens.verify(post, token, carol))).isFalse();
        assertThat(as(carol, () -> tokens.verify(post, next, carol))).isTrue();
        assertThat(as(carol, () -> tokens.verify(otherPost, next, carol))).isFalse();
        assertThat(as(bob, () -> tokens.verify(post, next, bob))).isFalse();
        assertThat(as(carol, () -> tokens.verify(post, next + "A", carol))).isFalse();
        assertThat(as(carol, () -> tokens.verify(post, "", carol))).isFalse();
        assertThat(as(carol, () -> tokens.verify(PostId.of(post.value() + 100_000), next, carol)))
                .isFalse();
        demote(carol, "subscriber");
        assertThat(as(carol, () -> tokens.verify(post, next, carol))).isFalse();
        assertThat(tokenRows(site)).isEqualTo(rows);
        assertThat(secretRows()).isEqualTo(1);
        assertThatThrownBy(() -> as(bob, () -> tokens.issue(post, carol)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller is not user " + carol.value());
        assertThatThrownBy(() -> as(erin, () -> tokens.issue(post, erin)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability edit_post on site " + site.value());
        assertThatThrownBy(() -> as(bob, () -> tokens.issue(PostId.of(post.value() + 100_000), bob)))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"));
    }

    @Test
    void aRotatedSecretInvalidatesEveryPreviewToken() throws Exception {
        SiteId other = TestData.site(dataSource);
        TestData.member(dataSource, other, carol, "[\"author\"]");
        PostId elsewhere = TestData.post(dataSource, other, "post", "draft", "", "Elsewhere");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", carol.value(), elsewhere.value());
        String token = as(carol, () -> tokens.issue(post, carol));
        assertThat(as(carol, () -> tokens.verify(post, token, carol))).isTrue();
        assertThat(Identities.onSite(other, Identities.user(carol), () -> tokens.verify(elsewhere, token, carol)))
                .isFalse();
        assertThat(Identities.onSite(other, Identities.user(carol), () -> tokens.issue(elsewhere, carol)))
                .isNotEqualTo(token);

        assertThat(Identities.onSite(site, Identities.system(), () -> options.delete(SECRET)))
                .isTrue();
        assertThat(secretRows()).isZero();
        assertThat(as(carol, () -> tokens.verify(post, token, carol))).isFalse();
        assertThat(secretRows()).isZero();
        String fresh = as(carol, () -> tokens.issue(post, carol));
        assertThat(secretRows()).isEqualTo(1);
        assertThat(fresh).isNotEqualTo(token).hasSize(27);
        assertThat(as(carol, () -> tokens.verify(post, fresh, carol))).isTrue();
        assertThat(as(carol, () -> tokens.verify(post, token, carol))).isFalse();
    }

    @Test
    void aMalformedSecretRefusesEveryPreviewInsteadOfWeakeningIt() throws Exception {
        String token = as(carol, () -> tokens.issue(post, carol));
        for (String malformed : new String[] {"\"x\"", "\"not base64!\"", "\"\"", "7", "\"" + SHORT_BASE64 + "\""}) {
            TestData.siteOption(dataSource, site, SECRET, malformed);
            assertThatThrownBy(() -> as(carol, () -> tokens.verify(post, token, carol)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SECRET);
            assertThatThrownBy(() -> as(carol, () -> tokens.issue(post, carol)))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining(SECRET);
        }
        assertThat(secretRows()).isEqualTo(1);
        Identities.onSite(site, Identities.system(), () -> options.delete(SECRET));
        assertThat(as(carol, () -> tokens.issue(post, carol))).hasSize(27).isNotEqualTo(token);
    }

    @Test
    void sessionsAreSiteScopedAndRevocable() throws Exception {
        PreviewSessions.Session session = as(carol, () -> sessions.open(carol));
        assertThat(session.token()).hasSize(43).matches(text -> text.chars().allMatch(PostTokensDbTest::base64url));
        assertThat(session.lifetime()).isEqualTo(Duration.ofHours(12));
        assertThat(tokenRows(site)).isEqualTo(1);
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(kind, '|', user_id, '|', length(token_hash)) from qp_post_token"
                                + " where site_id = ?",
                        site.value()))
                .containsExactly("preview_session|" + carol.value() + "|64");
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post_token where site_id = ? and token_hash = ?",
                        site.value(),
                        session.token()))
                .isZero();
        assertThat(as(carol, () -> sessions.user(session.token()))).contains(carol);
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> sessions.user(session.token())))
                .contains(carol);
        SiteId other = TestData.site(dataSource);
        assertThat(Identities.onSite(other, Identities.anonymous(), () -> sessions.user(session.token())))
                .isEmpty();
        assertThat(as(carol, () -> sessions.user(session.token() + "A"))).isEmpty();
        assertThat(as(carol, () -> sessions.user(""))).isEmpty();

        Identities.onSite(other, Identities.anonymous(), () -> {
            sessions.close(session.token());
            return null;
        });
        assertThat(as(carol, () -> sessions.user(session.token()))).contains(carol);
        Identities.onSite(site, Identities.anonymous(), () -> {
            sessions.close(session.token());
            return null;
        });
        assertThat(as(carol, () -> sessions.user(session.token()))).isEmpty();
        assertThat(tokenRows(site)).isZero();
        Identities.onSite(site, Identities.anonymous(), () -> {
            sessions.close(session.token());
            return null;
        });

        PreviewSessions.Session expiring = as(carol, () -> sessions.open(carol));
        clock.plus(Duration.ofHours(12).minusSeconds(1));
        assertThat(as(carol, () -> sessions.user(expiring.token()))).contains(carol);
        clock.plus(Duration.ofSeconds(1)); // exactly the lifetime later: expired
        assertThat(as(carol, () -> sessions.user(expiring.token()))).isEmpty();

        PreviewSessions.Session erins = as(erin, () -> sessions.open(erin));
        assertThat(as(erin, () -> sessions.user(erins.token()))).contains(erin);
        assertThatThrownBy(() -> as(frank, () -> sessions.open(frank)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller is not a member of site " + site.value());
        assertThatThrownBy(() -> as(bob, () -> sessions.open(carol)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller is not user " + carol.value());
        assertThatThrownBy(() -> Identities.onSite(site, Identities.system(), () -> sessions.open(carol)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller is not user " + carol.value());
        UserId admin = TestData.profile(dataSource, "network-admin");
        Jdbc.update(dataSource, "insert into qp_network_admin (user_id) values (?)", admin.value());
        PreviewSessions.Session admins = as(admin, () -> sessions.open(admin));
        assertThat(as(admin, () -> sessions.user(admins.token()))).contains(admin);
    }

    @Test
    void aUserKeepsOnlyTheNewestTenSessionsOfTheSite() throws Exception {
        List<String> carols = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            carols.add(as(carol, () -> sessions.open(carol)).token());
        }
        String erins = as(erin, () -> sessions.open(erin)).token();
        // carol's other site keeps its own sessions
        SiteId other = TestData.site(dataSource);
        TestData.member(dataSource, other, carol, "[\"author\"]");
        String elsewhere = Identities.onSite(other, Identities.user(carol), () -> sessions.open(carol))
                .token();
        // opening the eleventh and the twelfth closed the two oldest
        assertThat(as(carol, () -> sessions.user(carols.get(0)))).isEmpty();
        assertThat(as(carol, () -> sessions.user(carols.get(1)))).isEmpty();
        for (String kept : carols.subList(2, 12)) {
            assertThat(as(carol, () -> sessions.user(kept))).contains(carol);
        }
        assertThat(sessionRows(site, carol)).isEqualTo(10);
        assertThat(as(erin, () -> sessions.user(erins))).contains(erin);
        assertThat(Identities.onSite(other, Identities.anonymous(), () -> sessions.user(elsewhere)))
                .contains(carol);
        // release tokens are not sessions: they never count
        String lock = NOW + ":" + carol.value();
        as(carol, () -> releases.issue(post, carol, lock));
        as(carol, () -> sessions.open(carol));
        assertThat(sessionRows(site, carol)).isEqualTo(10);
        assertThat(as(carol, () -> sessions.user(carols.get(2)))).isEmpty();
    }

    @Test
    void releaseTokensAreOneTimeAndBoundToTheLockValue() throws Exception {
        String lock = NOW + ":" + carol.value();
        String token = as(carol, () -> releases.issue(post, carol, lock));
        assertThat(token).hasSize(43).matches(text -> text.chars().allMatch(PostTokensDbTest::base64url));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select concat(kind, '|', post_id, '|', user_id, '|', lock_value, '|', length(token_hash))"
                                + " from qp_post_token where site_id = ?",
                        site.value()))
                .containsExactly("lock_release|" + post.value() + "|" + carol.value() + "|" + lock + "|64");
        assertThat(as(carol, () -> releases.consume(post, carol, token, (NOW + 1) + ":" + carol.value())))
                .isFalse();
        assertThat(tokenRows(site)).isZero();
        assertThat(as(carol, () -> releases.consume(post, carol, token, lock))).isFalse();

        String second = as(carol, () -> releases.issue(post, carol, lock));
        assertThat(second).isNotEqualTo(token);
        assertThat(as(carol, () -> releases.consume(otherPost, carol, second, lock)))
                .isFalse();
        assertThat(as(bob, () -> releases.consume(post, bob, second, lock))).isFalse();
        assertThatThrownBy(() -> as(bob, () -> releases.consume(post, carol, second, lock)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller is not user " + carol.value());
        assertThatThrownBy(() ->
                        Identities.onSite(site, Identities.system(), () -> releases.consume(post, carol, second, lock)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller is not user " + carol.value());
        assertThat(tokenRows(site)).isEqualTo(1);
        assertThat(as(carol, () -> releases.consume(post, carol, second, lock))).isTrue();
        assertThat(as(carol, () -> releases.consume(post, carol, second, lock))).isFalse();
        assertThat(tokenRows(site)).isZero();

        String stale = as(carol, () -> releases.issue(post, carol, lock));
        clock.plus(Duration.ofHours(12).plusSeconds(1));
        assertThat(as(carol, () -> releases.consume(post, carol, stale, lock))).isFalse();
        assertThat(tokenRows(site)).isZero();

        assertThatThrownBy(() -> as(bob, () -> releases.issue(post, carol, lock)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller is not user " + carol.value());
        assertThatThrownBy(() -> as(erin, () -> releases.issue(post, erin, lock)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability edit_post on site " + site.value());
        assertThatThrownBy(() -> as(carol, () -> releases.issue(post, carol, "x".repeat(41))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(tokenRows(site)).isZero();
    }

    @Test
    void issuingAgainKeepsOneReleaseTokenPerUserAndPost() throws Exception {
        String lock = NOW + ":" + carol.value();
        String newer = (NOW + 1) + ":" + carol.value();
        String bobs = NOW + ":" + bob.value();
        as(carol, () -> sessions.open(carol));
        String first = as(carol, () -> releases.issue(post, carol, lock));
        String second = as(carol, () -> releases.issue(post, carol, newer));
        String bobsToken = as(bob, () -> releases.issue(post, bob, bobs));
        String other = as(carol, () -> releases.issue(otherPost, carol, lock));
        // the session, bob's token, carol's token of the other post and carol's newest token of this post
        assertThat(tokenRows(site)).isEqualTo(4);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post_token where site_id = ? and kind = 'lock_release'"
                                + " and post_id = ? and user_id = ?",
                        site.value(),
                        post.value(),
                        carol.value()))
                .isEqualTo(1);
        assertThat(as(carol, () -> releases.consume(post, carol, first, lock))).isFalse();
        assertThat(as(carol, () -> releases.consume(post, carol, second, newer)))
                .isTrue();
        assertThat(as(bob, () -> releases.consume(post, bob, bobsToken, bobs))).isTrue();
        assertThat(as(carol, () -> releases.consume(otherPost, carol, other, lock)))
                .isTrue();
        assertThat(tokenRows(site)).isEqualTo(1);
    }

    @Test
    void deletingThePostDeletesItsReleaseTokens() throws Exception {
        String lock = NOW + ":" + carol.value();
        as(carol, () -> releases.issue(post, carol, lock));
        as(carol, () -> releases.issue(otherPost, carol, lock));
        as(carol, () -> sessions.open(carol));
        assertThat(tokenRows(site)).isEqualTo(3);
        Jdbc.update(dataSource, "delete from qp_post where id = ?", post.value());
        assertThat(tokenRows(site)).isEqualTo(2);
        assertThat(Jdbc.count(
                        dataSource,
                        "select count(*) from qp_post_token where site_id = ? and post_id = ?",
                        site.value(),
                        post.value()))
                .isZero();
        Jdbc.update(dataSource, "delete from qp_site where id = ?", site.value());
        assertThat(tokenRows(site)).isZero();
    }

    @Test
    void kindsNeverCrossOver() throws Exception {
        String lock = NOW + ":" + carol.value();
        String preview = as(carol, () -> tokens.issue(post, carol));
        String session = as(carol, () -> sessions.open(carol)).token();
        String release = as(carol, () -> releases.issue(post, carol, lock));
        assertThat(as(carol, () -> sessions.user(preview))).isEmpty();
        assertThat(as(carol, () -> sessions.user(release))).isEmpty();
        assertThat(as(carol, () -> tokens.verify(post, session, carol))).isFalse();
        assertThat(as(carol, () -> tokens.verify(post, release, carol))).isFalse();
        assertThat(as(carol, () -> releases.consume(post, carol, session, lock)))
                .isFalse();
        assertThat(as(carol, () -> releases.consume(post, carol, preview, lock)))
                .isFalse();
        assertThat(tokenRows(site)).isEqualTo(2);
        assertThat(as(carol, () -> sessions.user(session))).contains(carol);
        assertThat(as(carol, () -> releases.consume(post, carol, release, lock)))
                .isTrue();
        assertThat(as(carol, () -> tokens.verify(post, preview, carol))).isTrue();
    }

    @Test
    void expiredRowsArePurgedInBoundedBatches() throws Exception {
        SiteId other = TestData.site(dataSource);
        UserId stale = TestData.profile(dataSource, "stale");
        clock.set(Instant.now().plus(Duration.ofDays(1)));
        for (int i = 0; i < 1200; i++) {
            expiredSession(site, stale, i);
        }
        for (int i = 0; i < 3; i++) {
            expiredSession(other, stale, 5000 + i);
        }
        assertThat(staleRows(site)).isEqualTo(1200);
        as(carol, () -> sessions.open(carol));
        assertThat(staleRows(site)).isEqualTo(700);
        assertThat(staleRows(other)).isEqualTo(3);
        String lock = NOW + ":" + carol.value();
        as(carol, () -> releases.issue(post, carol, lock));
        assertThat(staleRows(site)).isEqualTo(200);
        as(carol, () -> sessions.open(carol));
        assertThat(staleRows(site)).isZero();
        assertThat(tokenRows(site)).isEqualTo(3);
        assertThat(staleRows(other)).isEqualTo(3);
    }

    private void expiredSession(SiteId site, UserId user, int number) {
        String hash = String.format("%064x", number);
        Jdbc.update(
                dataSource,
                "insert into qp_post_token (site_id, kind, post_id, user_id, token_hash, lock_value, expires_at,"
                        + " created_at) values (?, 'preview_session', null, ?, ?, null, " + TestDb.now() + ", "
                        + TestDb.now() + ")",
                site.value(),
                user.value(),
                hash);
    }

    private long staleRows(SiteId site) {
        return Jdbc.count(
                dataSource,
                "select count(*) from qp_post_token where site_id = ? and kind = 'preview_session'"
                        + " and token_hash like '0000%'",
                site.value());
    }

    private <T> T as(UserId user, Callable<T> work) throws Exception {
        return Identities.onSite(site, Identities.user(user), work);
    }

    private UserId member(String role) {
        UserId user = TestData.profile(dataSource, role);
        TestData.member(dataSource, site, user, "[\"" + role + "\"]");
        return user;
    }

    private void demote(UserId user, String role) {
        Jdbc.update(
                dataSource,
                "update qp_site_membership set roles = " + TestDb.json() + " where site_id = ? and user_id = ?",
                "[\"" + role + "\"]",
                site.value(),
                user.value());
    }

    private PostId draftOf(UserId author, String title) {
        PostId draft = TestData.post(dataSource, site, "post", "draft", "", title);
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), draft.value());
        return draft;
    }

    private long sessionRows(SiteId site, UserId user) {
        return Jdbc.count(
                dataSource,
                "select count(*) from qp_post_token where site_id = ? and kind = 'preview_session' and user_id = ?",
                site.value(),
                user.value());
    }

    private long tokenRows(SiteId site) {
        return Jdbc.count(dataSource, "select count(*) from qp_post_token where site_id = ?", site.value());
    }

    /**
     * The token as WordPress's nonce counterpart is defined: the first 20 bytes of HMAC-SHA256 (the stored secret) over
     * {@code "preview|<site>|<post>|<user>|<tick>"}, base64url without padding.
     */
    private String expectedPreview(SiteId site, PostId post, UserId user, long tick) throws Exception {
        String stored = Jdbc.strings(
                        dataSource, "select value from qp_option where site_id = ? and name = ?", site.value(), SECRET)
                .get(0);
        byte[] secret = Base64.getDecoder().decode(stored.substring(1, stored.length() - 1));
        assertThat(secret).hasSize(32);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret, "HmacSHA256"));
        String message = "preview|" + site.value() + "|" + post.value() + "|" + user.value() + "|" + tick;
        byte[] full = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
        return Base64.getUrlEncoder().withoutPadding().encodeToString(Arrays.copyOf(full, 20));
    }

    private long secretRows() {
        return Jdbc.count(
                dataSource, "select count(*) from qp_option where site_id = ? and name = ?", site.value(), SECRET);
    }

    private static boolean base64url(int c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }
}
