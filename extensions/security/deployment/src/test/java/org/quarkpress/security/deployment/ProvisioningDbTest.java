package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.control.RequestContextController;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.persistence.PersistenceException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.security.ProfileClaims;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfileCreated;
import org.quarkpress.security.UserProfileProvisioner;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.UserProfileEntity;
import org.quarkpress.security.runtime.UserProfileRepository;

class ProvisioningDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(ProvisioningDbTest.class, TestClock.class, Created.class);

    @ApplicationScoped
    public static class Created {
        final List<UserProfileCreated> seen = new CopyOnWriteArrayList<>();

        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger failures = new AtomicInteger();
        volatile Supplier<RuntimeException> failure;

        void on(@Observes UserProfileCreated event) {
            calls.incrementAndGet();
            Supplier<RuntimeException> fail = failure;
            if (fail != null && failures.getAndDecrement() > 0) {
                throw fail.get();
            }
            seen.add(event);
        }

        /** Makes the next {@code times} notifications throw what {@code what} makes. */
        void failNext(int times, Supplier<RuntimeException> what) {
            failures.set(times);
            failure = what;
        }

        int calls() {
            return calls.get();
        }

        void stopFailing() {
            failure = null;
        }

        List<UserProfileCreated> seen() {
            return seen;
        }
    }

    @Inject
    UserProfileProvisioner provisioner;

    @Inject
    UserProfiles profiles;

    @Inject
    TestClock clock;

    @Inject
    UserProfileRepository repository;

    @Inject
    RequestContextController requestContext;

    @Inject
    Created created;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    /** A lower-case word no other test uses, so nicenames do not meet other tests' profiles. */
    private static String word() {
        StringBuilder word = new StringBuilder("w");
        for (char c : UUID.randomUUID().toString().toCharArray()) {
            if (c >= 'a' && c <= 'f') {
                word.append(c);
            } else if (c >= '0' && c <= '9') {
                word.append((char) ('g' + (c - '0')));
            }
            if (word.length() == 12) {
                break;
            }
        }
        return word.toString();
    }

    private static ProfileClaims claims(String username, String given, String family) {
        return new ProfileClaims(
                "sub-" + UUID.randomUUID(),
                Optional.ofNullable(username),
                Optional.ofNullable(given),
                Optional.ofNullable(family));
    }

    @Test
    void aFirstLoginCreatesAProfileWithWordPressNames() {
        String base = word();
        int before = created.seen().size();
        UserProfile profile = provisioner.provision(claims(base + ".Smith", "Ann", "Smith"));
        assertThat(profile.nicename()).isEqualTo(base + "-smith");
        assertThat(profile.displayName()).isEqualTo("Ann Smith");
        assertThat(profile.createdAt()).isEqualTo(clock.now());
        assertThat(profile.lastLoginAt()).contains(clock.now());
        assertThat(profile.primarySite()).isEmpty();
        assertThat(created.seen()).hasSize(before + 1);
        assertThat(created.seen().get(before).profile()).isEqualTo(profile);
        assertThat(profiles.findBySubject(profile.subject())).contains(profile);
        assertThat(profiles.find(profile.id())).contains(profile);
        assertThat(profiles.findByNicename(profile.nicename())).contains(profile);
    }

    @Test
    void aLaterLoginReturnsTheProfileUnchangedAndRefreshesTheLoginTimeAtMostEveryFifteenMinutes() {
        ProfileClaims first = claims(word(), "Ann", "Lee");
        UserProfile profile = provisioner.provision(first);
        Instant start = clock.now();
        int before = created.seen().size();
        try {
            ProfileClaims renamed =
                    new ProfileClaims(first.subject(), Optional.of("other"), Optional.of("Bob"), Optional.empty());
            clock.set(start.plus(Duration.ofMinutes(5)));
            UserProfile again = provisioner.provision(renamed);
            assertThat(again.id()).isEqualTo(profile.id());
            assertThat(again.displayName()).isEqualTo("Ann Lee");
            assertThat(again.nicename()).isEqualTo(profile.nicename());
            assertThat(again.lastLoginAt()).contains(start);
            clock.set(start.plus(Duration.ofMinutes(16)));
            assertThat(provisioner.provision(renamed).lastLoginAt()).contains(start.plus(Duration.ofMinutes(16)));
            assertThat(created.seen()).hasSize(before);
        } finally {
            clock.set(start);
        }
    }

    @Test
    void collidingNicenamesGetWordPressSuffixes() {
        String base = word();
        assertThat(provisioner.provision(claims(base, null, null)).nicename()).isEqualTo(base);
        assertThat(provisioner
                        .provision(claims(base.toUpperCase(java.util.Locale.ROOT), null, null))
                        .nicename())
                .isEqualTo(base + "-2");
        assertThat(provisioner.provision(claims(base + ".", null, null)).nicename())
                .isEqualTo(base + "-3");
        String fifty = base + "x".repeat(50 - base.length());
        assertThat(provisioner.provision(claims(fifty, null, null)).nicename()).isEqualTo(fifty);
        assertThat(provisioner.provision(claims(fifty + "yy", null, null)).nicename())
                .isEqualTo(fifty.substring(0, 48) + "-2");
    }

    @Test
    void anUnusableUsernameFallsBackToTheId() {
        String cyrillic = new String(new int[] {0x418, 0x432, 0x430, 0x43D}, 0, 4);
        UserProfile fromCyrillic = provisioner.provision(claims(cyrillic, null, null));
        assertThat(fromCyrillic.nicename())
                .isEqualTo("user-" + fromCyrillic.id().value());
        assertThat(fromCyrillic.displayName()).isEqualTo(cyrillic);
        UserProfile missing = provisioner.provision(claims(null, null, null));
        assertThat(missing.nicename()).isEqualTo("user-" + missing.id().value());
        assertThat(missing.displayName()).isEqualTo(missing.subject());
        UserProfile dots = provisioner.provision(claims("...", "Dot", null));
        assertThat(dots.nicename()).isEqualTo("user-" + dots.id().value());
        assertThat(dots.displayName()).isEqualTo("Dot");
    }

    @Test
    void aTakenFallbackGetsASuffix() {
        // Profiles whose nicenames are the next ids' fallbacks, as a login "user 12" would produce.
        List<String> subjects = new ArrayList<>();
        try {
            for (int i = 0; i < 20; i++) {
                String subject = "poison-" + UUID.randomUUID();
                subjects.add(subject);
                Jdbc.update(
                        dataSource,
                        "insert into qp_user_profile (subject, display_name, nicename) values (?, 'p', ?)",
                        subject,
                        "poison-" + UUID.randomUUID().toString().substring(0, 20));
            }
            long top = Jdbc.count(dataSource, "select max(id) from qp_user_profile");
            for (int i = 0; i < subjects.size(); i++) {
                Jdbc.update(
                        dataSource,
                        "update qp_user_profile set nicename = ? where subject = ?",
                        "user-" + (top + 1 + i),
                        subjects.get(i));
            }
            UserProfile profile = provisioner.provision(claims(null, null, null));
            assertThat(profile.id().value()).isBetween(top + 1, top + 20);
            assertThat(profile.nicename()).isEqualTo("user-" + profile.id().value() + "-2");
        } finally {
            // the poisoned nicenames would collide with later tests' fallbacks whatever the test order
            for (String subject : subjects) {
                Jdbc.update(
                        dataSource,
                        "update qp_user_profile set nicename = ? where subject = ?",
                        "cleaned-" + UUID.randomUUID().toString().substring(0, 20),
                        subject);
            }
        }
    }

    @Test
    void aZeroUsernameGivesTheFallbackNicename() {
        UserProfile profile = provisioner.provision(claims("0", null, null));
        assertThat(profile.nicename()).isEqualTo("user-" + profile.id().value());
        assertThat(profile.displayName()).isEqualTo("0");
    }

    @Test
    void concurrentFirstLoginsWithoutAUsernameCreateOneProfile() throws Exception {
        ProfileClaims claims = claims(null, null, null);
        int before = created.seen().size();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> provisioner.provision(claims));
        }
        List<Object> results = Races.run(tasks);
        Set<Object> ids = new HashSet<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(UserProfile.class);
            UserProfile profile = (UserProfile) result;
            ids.add(profile.id());
            assertThat(profile.nicename()).isEqualTo("user-" + profile.id().value());
        }
        assertThat(ids).hasSize(1);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile where subject = ?", claims.subject()))
                .isEqualTo(1);
        assertThat(created.seen()).hasSize(before + 1);
    }

    @Test
    void aSubjectWithSurroundingWhitespaceIsRefusedAndNeverResolvesToAnotherProfile() {
        UserProfile abc = provisioner.provision(ProfileClaims.subjectOnly("abc-" + UUID.randomUUID()));
        for (String padded :
                List.of(abc.subject() + " ", " " + abc.subject(), abc.subject() + "\t", abc.subject() + "\n")) {
            assertThatThrownBy(() -> ProfileClaims.subjectOnly(padded))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("an OIDC subject must not start or end with whitespace");
            // MySQL's PAD SPACE collation would match the unpadded row; the lookup must not
            assertThat(profiles.findBySubject(padded)).isEmpty();
        }
        assertThat(profiles.findBySubject(abc.subject())).contains(abc);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile where subject = ?", abc.subject()))
                .isEqualTo(1);
    }

    @Test
    void aFailureThatIsNoRaceIsNotRetried() {
        int calls = created.calls();
        created.failNext(100, () -> new PersistenceException("boom"));
        try {
            assertThatThrownBy(() -> provisioner.provision(claims(word(), null, null)))
                    .hasStackTraceContaining("boom")
                    .satisfies(failure -> assertThat(failure.getSuppressed()).isEmpty());
            assertThat(created.calls()).isEqualTo(calls + 1);
        } finally {
            created.stopFailing();
        }
    }

    @Test
    void aRaceFailureIsRetriedAndTheEarlierFailuresAreSuppressed() {
        int calls = created.calls();
        created.failNext(100, () -> new PersistenceException("dup", new SQLException("duplicate", "23505")));
        try {
            assertThatThrownBy(() -> provisioner.provision(claims(word(), null, null)))
                    .satisfies(failure -> assertThat(failure.getSuppressed()).hasSize(2));
            assertThat(created.calls()).isEqualTo(calls + 3);
        } finally {
            created.stopFailing();
        }
        created.failNext(1, () -> new PersistenceException("deadlock", new SQLException("deadlock", "x", 1213)));
        try {
            int before = created.calls();
            assertThat(provisioner.provision(claims(word(), null, null))).isNotNull();
            assertThat(created.calls()).isEqualTo(before + 2);
        } finally {
            created.stopFailing();
        }
    }

    /**
     * Whether a login of the subject has inserted its row and waits for a lock. PostgreSQL shows lock waits to every
     * user; MySQL does so only to a user with the PROCESS privilege, so there the test finds the login's uncommitted
     * row, which a no-wait locking read refuses.
     */
    private boolean loginIsWaiting(String subject) {
        if (TestDb.kind().equals("postgresql")) {
            return Jdbc.count(dataSource, "select count(*) from pg_stat_activity where wait_event_type = 'Lock'") > 0;
        }
        try {
            Jdbc.count(dataSource, "select count(*) from qp_user_profile where subject = ? for update nowait", subject);
            return false;
        } catch (IllegalStateException locked) {
            return true;
        }
    }

    @Test
    void aFallbackTakenBetweenTheCheckAndTheUpdateIsRetriedWithANewId() throws Exception {
        // the id the next profile will get: insert and delete a probe row, then reserve the one after it
        Jdbc.update(
                dataSource,
                "insert into qp_user_profile (subject, display_name, nicename) values (?, 'p', ?)",
                "probe-" + UUID.randomUUID(),
                "probe-" + UUID.randomUUID().toString().substring(0, 20));
        long probe = Jdbc.count(dataSource, "select max(id) from qp_user_profile");
        Jdbc.update(dataSource, "delete from qp_user_profile where id = ?", probe);
        String holderSubject = "holder-" + UUID.randomUUID();
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            // takes id probe + 1 and, uncommitted, the nicename of the id after it
            try (PreparedStatement insert = holder.prepareStatement(
                    "insert into qp_user_profile (subject, display_name, nicename) values (?, 'h', ?)")) {
                insert.setString(1, holderSubject);
                insert.setString(2, "user-" + (probe + 2));
                insert.executeUpdate();
            }
            ProfileClaims claims = claims(null, null, null);
            Future<UserProfile> login = pool.submit(() -> provisioner.provision(claims));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            while (!loginIsWaiting(claims.subject())) {
                assertThat(System.nanoTime()).as("the login reaches the update").isLessThan(deadline);
                assertThat(login.isDone()).as("the login waits for the holder").isFalse();
                Thread.sleep(50);
            }
            if (!TestDb.kind().equals("postgresql")) {
                Thread.sleep(500); // MySQL: the login has inserted its row; give it time to reach the update
            }
            holder.commit();
            UserProfile profile = login.get(60, TimeUnit.SECONDS);
            assertThat(profile.id().value()).isGreaterThan(probe + 2);
            assertThat(profile.nicename()).isEqualTo("user-" + profile.id().value());
            assertThat(Jdbc.count(
                            dataSource, "select count(*) from qp_user_profile where subject = ?", profile.subject()))
                    .isEqualTo(1);
        } finally {
            pool.shutdownNow();
            Jdbc.update(
                    dataSource,
                    "update qp_user_profile set nicename = ? where subject = ?",
                    "cleaned-" + UUID.randomUUID().toString().substring(0, 20),
                    holderSubject);
        }
    }

    @Test
    void concurrentFirstLoginsOfOneSubjectCreateOneProfile() throws Exception {
        ProfileClaims claims = claims(word(), "Con", "Current");
        int before = created.seen().size();
        List<Callable<Object>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> provisioner.provision(claims));
        }
        List<Object> results = Races.run(tasks);
        Set<Object> ids = new HashSet<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(UserProfile.class);
            ids.add(((UserProfile) result).id());
        }
        assertThat(ids).hasSize(1);
        assertThat(Jdbc.count(dataSource, "select count(*) from qp_user_profile where subject = ?", claims.subject()))
                .isEqualTo(1);
        assertThat(created.seen()).hasSize(before + 1);
    }

    @Test
    void concurrentLoginsWithCollidingNicenamesGetSuffixes() throws Exception {
        String base = word();
        List<String> usernames = List.of(
                base + ".lee",
                base + " lee",
                base + "-lee",
                base + " Lee",
                base + "..lee",
                base.toUpperCase(java.util.Locale.ROOT) + " LEE");
        List<Callable<Object>> tasks = new ArrayList<>();
        for (String username : usernames) {
            tasks.add(() -> provisioner.provision(claims(username, null, null)));
        }
        List<Object> results = Races.run(tasks);
        Set<String> nicenames = new HashSet<>();
        for (Object result : results) {
            assertThat(result).isInstanceOf(UserProfile.class);
            nicenames.add(((UserProfile) result).nicename());
        }
        assertThat(nicenames)
                .containsExactlyInAnyOrder(
                        base + "-lee",
                        base + "-lee-2",
                        base + "-lee-3",
                        base + "-lee-4",
                        base + "-lee-5",
                        base + "-lee-6");
    }

    @Test
    void claimsAreChecked() {
        assertThatThrownBy(() -> new ProfileClaims(" ", Optional.empty(), Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an OIDC subject must not be blank");
        assertThatThrownBy(() -> ProfileClaims.subjectOnly("s".repeat(256)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an OIDC subject has at most 255 characters: 256");
        assertThat(ProfileClaims.subjectOnly("abc").preferredUsername()).isEmpty();
    }

    @Test
    void metaRoundTripsThroughTheDatabaseKeepingKeyOrder() throws Exception {
        UserProfile profile = provisioner.provision(claims(word(), null, null));
        JsonNode meta = new ObjectMapper().readTree("{\"z\":1,\"a\":{\"n\":[1,2,\"x\"]},\"m\":true}");
        requestContext.activate();
        try {
            QuarkusTransaction.requiringNew().run(() -> {
                UserProfileEntity entity = repository.findById(profile.id().value());
                assertThat(entity.meta.toString()).isEqualTo("{}");
                entity.meta = meta;
            });
            JsonNode read = QuarkusTransaction.requiringNew()
                    .call(() -> repository.findById(profile.id().value()).meta);
            assertThat(read).isEqualTo(meta);
            assertThat(read.toString()).isEqualTo("{\"z\":1,\"a\":{\"n\":[1,2,\"x\"]},\"m\":true}");
        } finally {
            requestContext.deactivate();
        }
        assertThat(Jdbc.strings(
                        dataSource,
                        "select meta from qp_user_profile where id = ?",
                        profile.id().value()))
                .singleElement()
                .satisfies(raw -> assertThat(new ObjectMapper().readTree(raw)).isEqualTo(meta));
    }
}
