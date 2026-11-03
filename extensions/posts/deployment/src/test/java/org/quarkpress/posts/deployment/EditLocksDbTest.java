package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.PostException;
import org.quarkpress.posts.PostLocks;
import org.quarkpress.posts.PostLocks.Lock;
import org.quarkpress.posts.PostLocks.Take;

/** The editor's post lock ({@code _edit_lock}): WordPress's freshness and ownership rules, take, refresh, release. */
class EditLocksDbTest {

    private static final long NOW = 1_000_000;

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(EditLocksDbTest.class, TestClock.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    PostLocks locks;

    @Inject
    TestClock clock;

    private SiteId site;
    private UserId bob;
    private UserId carol;
    private UserId dave;
    private UserId erin;
    private PostId post;

    @BeforeEach
    void fixture() {
        clock.set(Instant.ofEpochSecond(NOW));
        site = TestData.site(dataSource);
        bob = TestData.memberOfMain(dataSource, "editor");
        carol = TestData.memberOfMain(dataSource, "author");
        dave = TestData.memberOfMain(dataSource, "contributor");
        erin = TestData.memberOfMain(dataSource, "subscriber");
        post = TestData.post(dataSource, site, "post", "draft", "", "Locked");
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", carol.value(), post.value());
    }

    @Test
    void holderFollowsWordPressFreshnessAndOwnership() throws Exception {
        lock("999900:" + bob.value());
        assertThat(holder(carol)).contains(bob);
        assertThat(holder(bob)).isEmpty();
        lock("999850:" + bob.value());
        assertThat(holder(carol)).isEmpty(); // exactly 150 s old: expired
        lock("999900:77777");
        assertThat(holder(carol)).isEmpty(); // no such user
        lock("0:" + bob.value());
        assertThat(holder(carol)).isEmpty();
        lock("999900");
        editLast(dave);
        assertThat(holder(carol)).contains(dave);
        assertThat(Identities.onSite(site, Identities.anonymous(), () -> locks.holder(post, Optional.empty())))
                .contains(dave);
        assertThat(Identities.onSite(site, Identities.user(carol), () -> locks.current(post)))
                .isEmpty();
        lock("999900:" + bob.value());
        assertThat(Identities.onSite(site, Identities.user(carol), () -> locks.current(post)))
                .contains(new Lock(bob, Instant.ofEpochSecond(999_900)));
        assertThat(lockRows()).isZero();
    }

    @Test
    void takeReportsAnotherUsersFreshLockAndRefreshRetakesAnExpiredOne() throws Exception {
        Take carols = Identities.onSite(site, Identities.user(carol), () -> locks.take(post));
        assertThat(carols).isEqualTo(new Take.Taken(new Lock(carol, Instant.ofEpochSecond(NOW))));
        assertThat(stored()).isEqualTo(NOW + ":" + carol.value());

        Take bobs = Identities.onSite(site, Identities.user(bob), () -> locks.take(post));
        assertThat(bobs).isEqualTo(new Take.Held(carol));
        assertThat(stored()).isEqualTo(NOW + ":" + carol.value());

        clock.set(Instant.ofEpochSecond(NOW + 151));
        Take refreshed = Identities.onSite(site, Identities.user(bob), () -> locks.refresh(post));
        assertThat(refreshed).isEqualTo(new Take.Taken(new Lock(bob, Instant.ofEpochSecond(NOW + 151))));
        assertThat(stored()).isEqualTo((NOW + 151) + ":" + bob.value());

        Take refusedRefresh = Identities.onSite(site, Identities.user(carol), () -> locks.refresh(post));
        assertThat(refusedRefresh).isEqualTo(new Take.Held(bob));

        Lock taken = Identities.onSite(site, Identities.user(carol), () -> locks.takeOver(post));
        assertThat(taken).isEqualTo(new Lock(carol, Instant.ofEpochSecond(NOW + 151)));
        assertThat(stored()).isEqualTo((NOW + 151) + ":" + carol.value());
        assertThat(taken.value()).isEqualTo((NOW + 151) + ":" + carol.value());
        assertThat(Lock.parse(taken.value())).contains(taken);
        assertThat(Lock.parse("999900")).isEmpty();
        assertThat(Lock.parse("x:" + carol.value())).isEmpty();
        assertThat(Lock.parse("1:0")).isEmpty();
        assertThat(Lock.parse("99999999999:1"))
                .contains(new Lock(UserId.of(1), Instant.ofEpochSecond(99_999_999_999L)));
        assertThat(Lock.parse("999999999999:1")).isEmpty(); // twelve digits: beyond what a lock holds
        assertThat(Lock.parse("999999999999999999:1")).isEmpty(); // would overflow Instant
    }

    @Test
    void aLockOfOneSiteIsInvisibleAndUnreleasableThroughAnother() throws Exception {
        SiteId other = TestData.site(dataSource);
        Identities.onSite(site, Identities.user(carol), () -> locks.take(post));
        String held = NOW + ":" + carol.value();
        assertThat(Identities.onSite(other, Identities.user(bob), () -> locks.holder(post, Optional.of(bob))))
                .isEmpty();
        assertThat(Identities.onSite(other, Identities.user(carol), () -> locks.current(post)))
                .isEmpty();
        assertThatThrownBy(() -> Identities.onSite(other, Identities.user(carol), () -> locks.release(post, held)))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"));
        assertThatThrownBy(() -> Identities.onSite(other, Identities.user(bob), () -> locks.takeOver(post)))
                .isInstanceOfSatisfying(
                        PostException.class, e -> assertThat(e.code()).isEqualTo("invalid_post"));
        assertThat(stored()).isEqualTo(held);
    }

    @Test
    void releaseBackdatesOnlyTheExpectedValueOfTheCaller() throws Exception {
        List<String> before = versionAndModified();
        Identities.onSite(site, Identities.user(carol), () -> locks.take(post));
        String held = NOW + ":" + carol.value();
        assertThat(stored()).isEqualTo(held);
        Take own = Identities.onSite(site, Identities.user(carol), () -> locks.refresh(post));
        assertThat(own).isEqualTo(new Take.Taken(new Lock(carol, Instant.ofEpochSecond(NOW))));
        assertThat(stored()).isEqualTo(held);

        assertThat(Identities.onSite(
                        site, Identities.user(carol), () -> locks.release(post, "999999999999999999:" + carol.value())))
                .isFalse();
        assertThat(Identities.onSite(
                        site, Identities.user(carol), () -> locks.release(post, "999000:" + carol.value())))
                .isFalse();
        assertThat(stored()).isEqualTo(held);
        assertThat(Identities.onSite(site, Identities.user(bob), () -> locks.release(post, held)))
                .isFalse();
        assertThat(stored()).isEqualTo(held);
        assertThat(Identities.onSite(site, Identities.user(carol), () -> locks.release(post, held)))
                .isTrue();
        assertThat(stored()).isEqualTo((NOW - 150 + 5) + ":" + carol.value());
        assertThat(Identities.onSite(site, Identities.user(carol), () -> locks.release(post, held)))
                .isFalse();

        assertThat(versionAndModified()).isEqualTo(before);

        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(erin), () -> locks.take(post)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(erin), () -> locks.refresh(post)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(erin), () -> locks.takeOver(post)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.user(erin), () -> locks.release(post, held)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage(lacks("edit_post"));
        assertThatThrownBy(() -> Identities.onSite(site, Identities.system(), () -> locks.take(post)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the system identity cannot hold a post lock");
        assertThatThrownBy(() -> Identities.onSite(site, Identities.system(), () -> locks.takeOver(post)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("the system identity cannot hold a post lock");
        assertThat(stored()).isEqualTo((NOW - 150 + 5) + ":" + carol.value());
    }

    private Optional<UserId> holder(UserId acting) throws Exception {
        return Identities.onSite(site, Identities.user(acting), () -> locks.holder(post, Optional.of(acting)));
    }

    private void lock(String value) {
        meta("{\"_edit_lock\":[\"" + value + "\"]}");
    }

    private void editLast(UserId user) {
        meta("{\"_edit_lock\":[\"999900\"],\"_edit_last\":[" + user.value() + "]}");
    }

    private void meta(String json) {
        Jdbc.update(dataSource, "update qp_post set meta = " + TestDb.json() + " where id = ?", json, post.value());
    }

    private String stored() throws Exception {
        return Identities.onSite(site, Identities.system(), () -> locks.current(post))
                .map(Lock::value)
                .orElse("");
    }

    private List<String> versionAndModified() {
        return List.of(
                Jdbc.strings(dataSource, "select version from qp_post where id = ?", post.value())
                        .get(0),
                Jdbc.strings(dataSource, "select modified_at from qp_post where id = ?", post.value())
                        .get(0));
    }

    private long lockRows() {
        return Jdbc.count(dataSource, "select count(*) from qp_post_lock where site_id = ?", site.value());
    }

    private String lacks(String capability) {
        return "the caller lacks the capability " + capability + " on site " + site.value();
    }
}
