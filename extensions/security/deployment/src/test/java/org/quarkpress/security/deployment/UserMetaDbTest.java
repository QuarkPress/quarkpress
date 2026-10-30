package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.UnauthorizedException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.UserMeta;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.ProfileProvisioning;

class UserMetaDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(UserMetaDbTest.class, TestClock.class);

    static final SiteId MAIN = SiteId.of(1);

    static final String LACKS_EDIT_USER = "the caller lacks the capability edit_user for this request";

    @Inject
    UserMeta meta;

    @Inject
    UserProfiles profiles;

    @Inject
    ProfileProvisioning provisioning;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    UserId carolId;
    UserId aliceId;
    UserId bobId;
    UserId adminId;
    SecurityIdentity carol;
    SecurityIdentity bob;
    SecurityIdentity admin;
    final SecurityIdentity anonymous = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(""))
            .setAnonymous(true)
            .build();

    @BeforeEach
    void users() {
        carolId = TestData.profile(dataSource, "carol");
        aliceId = TestData.profile(dataSource, "alice");
        bobId = TestData.profile(dataSource, "bob");
        adminId = TestData.profile(dataSource, "admin");
        TestData.member(dataSource, MAIN, carolId, "[\"author\"]", "{}");
        TestData.member(dataSource, MAIN, aliceId, "[\"editor\"]", "{}");
        TestData.member(dataSource, MAIN, bobId, "[\"editor\"]", "{}");
        TestData.member(dataSource, MAIN, adminId, "[\"administrator\"]", "{}");
        carol = Identities.user(carolId, TestData.subject(dataSource, carolId));
        bob = Identities.user(bobId, TestData.subject(dataSource, bobId));
        admin = Identities.user(adminId, TestData.subject(dataSource, adminId));
    }

    private static <T> T as(SecurityIdentity identity, Supplier<T> work) {
        return Identities.as(identity, work);
    }

    private static JsonNode json(String text) throws Exception {
        return new ObjectMapper().readTree(text);
    }

    @Test
    void preferencesRoundTripWithTheirKeyOrder() throws Exception {
        JsonNode value = json("{\"core\":{\"b\":1,\"a\":2},\"_modified\":\"2026-01-15T12:00:00.000Z\"}");
        as(carol, () -> {
            meta.set(carolId, meta.siteKey(MAIN, "persisted_preferences"), value);
            return null;
        });
        Optional<JsonNode> read = as(carol, () -> meta.get(carolId, "wp_persisted_preferences"));
        assertThat(read).contains(value);
        assertThat(read.orElseThrow().toString()).isEqualTo(value.toString());
        assertThat(meta.siteKey(MAIN, "persisted_preferences")).isEqualTo("wp_persisted_preferences");
        assertThat(meta.siteKey(SiteId.of(3), "persisted_preferences")).isEqualTo("wp_3_persisted_preferences");
        // a later write of another key keeps the first key in place; rewriting a key keeps its position
        as(carol, () -> {
            meta.set(carolId, "z", TextNode.valueOf("last"));
            meta.set(carolId, "wp_persisted_preferences", TextNode.valueOf("again"));
            return null;
        });
        assertThat(Jdbc.strings(dataSource, "select meta from qp_user_profile where id = ?", carolId.value()))
                .singleElement()
                .satisfies(raw -> assertThat(json(raw).toString())
                        .isEqualTo("{\"wp_persisted_preferences\":\"again\",\"z\":\"last\"}"));
        assertThat(as(carol, () -> meta.delete(carolId, "z"))).isTrue();
        assertThat(as(carol, () -> meta.delete(carolId, "z"))).isFalse();
        assertThat(meta.get(carolId, "z")).isEmpty();
        assertThat(meta.get(carolId, "wp_persisted_preferences")).contains(TextNode.valueOf("again"));
    }

    @Test
    void othersMetaNeedsEditUser() {
        assertThatThrownBy(() -> as(bob, () -> {
                    meta.set(aliceId, "k", TextNode.valueOf("v"));
                    return null;
                }))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(LACKS_EDIT_USER);
        assertThatThrownBy(() -> as(bob, () -> meta.delete(aliceId, "k")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(LACKS_EDIT_USER);
        assertThatThrownBy(() -> as(anonymous, () -> {
                    meta.set(carolId, "k", TextNode.valueOf("v"));
                    return null;
                }))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(LACKS_EDIT_USER);
        assertThatThrownBy(() -> as(anonymous, () -> meta.delete(carolId, "k")))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessage(LACKS_EDIT_USER);
        assertThat(meta.get(aliceId, "k")).isEmpty();
        assertThat(meta.get(carolId, "k")).isEmpty();
    }

    @Test
    void aWriteChangesOnlyTheNamedUsersMeta() {
        // an administrator (edit_users) writes another user's meta: that user's row, never the writer's
        as(admin, () -> {
            meta.set(aliceId, "k", TextNode.valueOf("for alice"));
            return null;
        });
        as(carol, () -> {
            meta.set(carolId, "k", TextNode.valueOf("for carol"));
            return null;
        });
        assertThat(meta.get(aliceId, "k")).contains(TextNode.valueOf("for alice"));
        assertThat(meta.get(carolId, "k")).contains(TextNode.valueOf("for carol"));
        assertThat(meta.get(adminId, "k")).isEmpty();
        assertThat(meta.get(bobId, "k")).isEmpty();
        // a permission on oneself is no permission on another user
        assertThatThrownBy(() -> as(carol, () -> meta.delete(aliceId, "k")))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(LACKS_EDIT_USER);
        assertThat(as(admin, () -> meta.delete(aliceId, "k"))).isTrue();
        assertThat(meta.get(aliceId, "k")).isEmpty();
        assertThat(meta.get(carolId, "k")).contains(TextNode.valueOf("for carol"));
    }

    @Test
    void keysHaveOneTo255CharactersAndAreNotBlank() {
        for (String bad : List.of("", " ", "\t")) {
            assertThatThrownBy(() -> as(carol, () -> {
                        meta.set(carolId, bad, TextNode.valueOf("v"));
                        return null;
                    }))
                    .as("key '%s'", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a user meta key must not be empty or blank");
            assertThatThrownBy(() -> meta.get(carolId, bad))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("a user meta key must not be empty or blank");
        }
        assertThatThrownBy(() -> as(carol, () -> meta.delete(carolId, "k".repeat(256))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a user meta key has at most 255 characters: 256");
        assertThatThrownBy(() -> as(carol, () -> {
                    meta.set(carolId, "k".repeat(256), TextNode.valueOf("v"));
                    return null;
                }))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a user meta key has at most 255 characters: 256");
        as(carol, () -> {
            meta.set(carolId, "k".repeat(255), TextNode.valueOf("v"));
            return null;
        });
        assertThat(meta.get(carolId, "k".repeat(255))).contains(TextNode.valueOf("v"));
    }

    @Test
    void concurrentWritesAndALoginRefreshNeverFail() throws Exception {
        String subject = TestData.subject(dataSource, carolId);
        String key = meta.siteKey(MAIN, "persisted_preferences");
        for (int round = 0; round < 10; round++) {
            // values of this round only: a login that wrote back what it read earlier would leave an older one
            JsonNode first = json("{\"tab\":1,\"round\":" + round + "}");
            JsonNode second = json("{\"tab\":2,\"round\":" + round + "}");
            String email = "carol-" + round + "@quarkpress.test";
            List<Callable<Object>> tasks = new ArrayList<>();
            tasks.add(() -> as(carol, () -> {
                meta.set(carolId, key, first);
                return "a";
            }));
            tasks.add(() -> as(carol, () -> {
                meta.set(carolId, key, second);
                return "b";
            }));
            tasks.add(() -> provisioning.provision(
                    new ProfileClaims(
                            subject, Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(email)),
                    true));
            List<Object> results = Races.run(tasks);
            for (Object result : results) {
                assertThat(result).as("round %d", round).isNotInstanceOf(Throwable.class);
            }
            assertThat(meta.get(carolId, key))
                    .as("round %d", round)
                    .hasValueSatisfying(value -> assertThat(value).isIn(first, second));
            assertThat(profiles.find(carolId).orElseThrow().email())
                    .as("round %d", round)
                    .contains(email);
        }
    }

    /**
     * Whether another session runs a locking statement on qp_user_profile, which, while the test holds the row, means it
     * waits for the lock. PostgreSQL shows lock waits to every user; MySQL shows lock waits only to a user with the
     * PROCESS privilege, but every user sees the statements its own sessions run.
     */
    private boolean loginWaitsForTheRow() {
        if (TestDb.kind().equals("postgresql")) {
            return Jdbc.count(
                            dataSource,
                            "select count(*) from pg_stat_activity where wait_event_type = 'Lock'"
                                    + " and query like '%qp_user_profile%'")
                    > 0;
        }
        return Jdbc.count(
                        dataSource,
                        "select count(*) from information_schema.processlist where id <> connection_id()"
                                + " and (info like 'update qp_user_profile%'"
                                + " or (info like '%from qp_user_profile%' and info like '%for update%'))")
                > 0;
    }

    @Test
    void aLoginThatWaitsForTheRowKeepsTheMetaWrittenMeanwhile() throws Exception {
        String subject = TestData.subject(dataSource, carolId);
        JsonNode written = json("{\"wp_persisted_preferences\":{\"meanwhile\":true}}");
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            // an uncommitted meta write holds the row; the login reads the row before it commits
            try (PreparedStatement update =
                    holder.prepareStatement("update qp_user_profile set meta = " + TestDb.json() + " where id = ?")) {
                update.setString(1, written.toString());
                update.setLong(2, carolId.value());
                update.executeUpdate();
            }
            Future<UserProfile> login = pool.submit(() -> provisioning.provision(
                    new ProfileClaims(
                            subject,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty(),
                            Optional.of("meanwhile@quarkpress.test")),
                    true));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            // the login has read the row (without a lock) and now waits for the lock to write it
            while (!loginWaitsForTheRow()) {
                assertThat(System.nanoTime()).as("the login waits for the row").isLessThan(deadline);
                assertThat(login.isDone()).as("the login waits for the row").isFalse();
                Thread.sleep(20);
            }
            assertThat(login.isDone()).as("the login waits for the row").isFalse();
            holder.commit();
            assertThat(login.get(60, TimeUnit.SECONDS).email()).contains("meanwhile@quarkpress.test");
        } finally {
            pool.shutdownNow();
        }
        assertThat(meta.get(carolId, "wp_persisted_preferences")).contains(json("{\"meanwhile\":true}"));
    }
}
