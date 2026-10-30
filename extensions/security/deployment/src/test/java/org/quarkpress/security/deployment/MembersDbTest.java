package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.MemberColumn;
import org.quarkpress.security.MemberOrder;
import org.quarkpress.security.MemberPage;
import org.quarkpress.security.MemberQuery;
import org.quarkpress.security.Memberships;
import org.quarkpress.security.SiteRoles;
import org.quarkpress.security.UserProfile;
import org.quarkpress.security.UserProfiles;
import org.quarkpress.security.runtime.SiteRoleTables;

/** A site's members filtered, ordered, counted and paged in the database; allcaps and profiles by ids. */
class MembersDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.with(MembersDbTest.class);

    static final SiteId MAIN = SiteId.of(1);

    static final List<String> LEVELS =
            IntStream.rangeClosed(1, 10).mapToObj(n -> "level_" + n).toList();

    static final List<String> NICENAMES = List.of("alice", "bob", "carol", "dave", "erin", "frank", "outsider");

    @Inject
    Memberships memberships;

    @Inject
    Capabilities capabilities;

    @Inject
    UserProfiles profiles;

    @Inject
    SiteRoles siteRoles;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    final Map<UserId, String> nicenames = new HashMap<>();
    UserId aliceId;
    UserId bobId;
    UserId carolId;
    UserId daveId;
    UserId erinId;
    UserId frankId;
    UserId outsiderId;
    final SecurityIdentity anonymous = QuarkusSecurityIdentity.builder()
            .setPrincipal(new QuarkusPrincipal(""))
            .setAnonymous(true)
            .build();

    @BeforeEach
    void members() {
        // the main site has exactly these members, with the default role table
        Jdbc.update(dataSource, "delete from qp_site_membership where site_id = ?", MAIN.value());
        Jdbc.update(
                dataSource,
                "delete from qp_option where site_id = ? and name = ?",
                MAIN.value(),
                SiteRoleTables.OPTION);
        for (String nicename : NICENAMES) {
            Jdbc.update(
                    dataSource,
                    "delete from qp_user_profile where nicename = ? or subject = ?",
                    nicename,
                    nicename + "-subject");
        }
        aliceId = member("alice", "Alice Anders", "alice@quarkpress.test", "[\"administrator\"]");
        bobId = member("bob", "Bob Baker", "bob@quarkpress.test", "[\"editor\"]");
        carolId = member("carol", "Carol Chen", "carol@quarkpress.test", "[\"author\"]");
        daveId = member("dave", "Dave Diaz", "dave@quarkpress.test", "[\"contributor\"]");
        erinId = member("erin", "Erin Evans", "erin@quarkpress.test", "[\"subscriber\"]");
        frankId = member("frank", "Frank Fox", null, "[]");
        outsiderId = profile("outsider", "Outsider", "outsider@quarkpress.test");
    }

    private UserId profile(String nicename, String displayName, String email) {
        String subject = nicename + "-subject";
        Jdbc.update(
                dataSource,
                "insert into qp_user_profile (subject, display_name, nicename, email) values (?, ?, ?, ?)",
                subject,
                displayName,
                nicename,
                email);
        UserId id = UserId.of(Jdbc.count(dataSource, "select id from qp_user_profile where subject = ?", subject));
        nicenames.put(id, nicename);
        return id;
    }

    private UserId member(String nicename, String displayName, String email, String roles) {
        UserId id = profile(nicename, displayName, email);
        TestData.member(dataSource, MAIN, id, roles, "{}");
        return id;
    }

    private void setCapabilities(UserId user, String json) {
        Jdbc.update(
                dataSource,
                "update qp_site_membership set capabilities = " + TestDb.json() + " where site_id = ? and user_id = ?",
                json,
                MAIN.value(),
                user.value());
    }

    private void setRoles(UserId user, String json) {
        Jdbc.update(
                dataSource,
                "update qp_site_membership set roles = " + TestDb.json() + " where site_id = ? and user_id = ?",
                json,
                MAIN.value(),
                user.value());
    }

    private MemberPage members(MemberQuery query) {
        return memberships.members(MAIN, query);
    }

    private MemberPage search(String term, MemberColumn... columns) {
        return members(MemberQuery.builder()
                .search(term)
                .searchColumns(Set.of(columns))
                .limit(100)
                .build());
    }

    private List<String> names(MemberPage page) {
        return page.users().stream().map(nicenames::get).toList();
    }

    private static <T> T as(SecurityIdentity identity, Supplier<T> work) {
        return Identities.as(identity, work);
    }

    @Test
    void allMembersByDisplayName() {
        MemberPage page = members(MemberQuery.builder().limit(100).build());
        assertThat(names(page)).containsExactly("alice", "bob", "carol", "dave", "erin", "frank");
        assertThat(page.total()).isEqualTo(6);
    }

    @Test
    void rolesAndCapabilitiesAreAnyOf() {
        assertThat(names(members(
                        MemberQuery.builder().roles(List.of("author", "editor")).build())))
                .containsExactly("bob", "carol");
        assertThat(names(members(MemberQuery.builder().capabilities(LEVELS).build())))
                .containsExactly("alice", "bob", "carol", "dave");
    }

    @Test
    void aCustomRoleAGrantAndADenialAreStoredKeysThatMatch() {
        as(
                Identities.system(),
                () -> siteRoles.add(MAIN, "reviewer", "Reviewer", Map.of("read", true, "level_1", true)));
        setRoles(frankId, "[\"reviewer\"]");
        setCapabilities(erinId, "{\"level_1\":true}");
        setCapabilities(daveId, "{\"level_1\":false}");
        // as in WordPress, a stored key matches whatever its value: dave's denial does not remove him
        assertThat(names(members(MemberQuery.builder().capabilities(LEVELS).build())))
                .containsExactly("alice", "bob", "carol", "dave", "erin", "frank");
        setCapabilities(daveId, "{}");
        setRoles(daveId, "[\"subscriber\"]");
        assertThat(names(members(MemberQuery.builder().capabilities(LEVELS).build())))
                .containsExactly("alice", "bob", "carol", "erin", "frank");
    }

    @Test
    void aRoleKeyCountsAsARoleAndAsACapability() {
        // a role key stored among individual capabilities is a role, as WordPress keeps roles among capabilities
        setCapabilities(frankId, "{\"author\":true}");
        assertThat(names(members(
                        MemberQuery.builder().roles(List.of("author", "editor")).build())))
                .containsExactly("bob", "carol", "frank");
        assertThat(names(members(
                        MemberQuery.builder().capabilities(List.of("level_2")).build())))
                .containsExactly("alice", "bob", "carol", "frank");
        // a capability name matches a stored key of that name, whatever its value
        assertThat(names(members(
                        MemberQuery.builder().capabilities(List.of("editor")).build())))
                .containsExactly("bob");
        setCapabilities(bobId, "{\"editor\":false}");
        assertThat(names(members(
                        MemberQuery.builder().capabilities(List.of("editor")).build())))
                .containsExactly("bob");
        setCapabilities(erinId, "{\"moderate_things\":false}");
        // a capability no role grants is held only individually
        setCapabilities(daveId, "{\"moderate_things\":true}");
        assertThat(names(members(MemberQuery.builder()
                        .capabilities(List.of("moderate_things", "no_such_capability"))
                        .build())))
                .containsExactly("dave", "erin");
    }

    @Test
    void rolesAndCapabilitiesTogetherAreOneAnyOfList() {
        // WordPress merges capability__in and the roles granting it into role__in
        assertThat(names(members(MemberQuery.builder()
                        .roles(List.of("subscriber"))
                        .capabilities(List.of("edit_posts"))
                        .build())))
                .containsExactly("alice", "bob", "carol", "dave", "erin");
        assertThat(names(members(MemberQuery.builder()
                        .roles(List.of("subscriber"))
                        .capabilities(List.of("publish_posts"))
                        .build())))
                .containsExactly("alice", "bob", "carol", "erin");
    }

    @Test
    void searchOrderInclusionAndPaging() {
        assertThat(names(search("ARO", MemberColumn.LOGIN, MemberColumn.NICENAME, MemberColumn.DISPLAY_NAME)))
                .containsExactly("carol");
        assertThat(names(search(String.valueOf(daveId.value()), MemberColumn.ID)))
                .containsExactly("dave");
        assertThat(names(search("@quarkpress.test", MemberColumn.EMAIL)))
                .containsExactly("alice", "bob", "carol", "dave", "erin");
        assertThat(names(members(MemberQuery.builder()
                        .include(List.of(erinId, aliceId))
                        .orderBy(MemberOrder.INCLUDE)
                        .build())))
                .containsExactly("erin", "alice");
        assertThat(names(members(MemberQuery.builder()
                        .slugs(List.of("dave", "bob"))
                        .orderBy(MemberOrder.INCLUDE_SLUGS)
                        .build())))
                .containsExactly("dave", "bob");
        MemberPage second = members(MemberQuery.builder().offset(2).limit(2).build());
        assertThat(names(second)).containsExactly("carol", "dave");
        assertThat(second.total()).isEqualTo(6);
    }

    @Test
    void searchColumnsAreOnlyTheChosenOnes() {
        // "Fox" is frank's display name only; LOGIN and NICENAME are the nicename; URL matches nothing
        assertThat(names(search("fox", MemberColumn.DISPLAY_NAME))).containsExactly("frank");
        assertThat(names(search("fox", MemberColumn.LOGIN, MemberColumn.NICENAME, MemberColumn.EMAIL)))
                .isEmpty();
        assertThat(names(search("ank", MemberColumn.LOGIN))).containsExactly("frank");
        assertThat(names(search("ank", MemberColumn.NICENAME))).containsExactly("frank");
        assertThat(names(search("frank", MemberColumn.URL))).isEmpty();
        assertThat(names(search("x" + daveId.value(), MemberColumn.ID))).isEmpty();
        assertThat(names(search("99999999999999999999999", MemberColumn.ID))).isEmpty();
        assertThat(names(search("BOB@QUARKPRESS", MemberColumn.EMAIL))).containsExactly("bob");
        // the outsider (no membership) is never listed, whatever matches
        assertThat(names(search("outsider", MemberColumn.NICENAME, MemberColumn.EMAIL)))
                .isEmpty();
    }

    @Test
    void searchWildcardsAreLiteralAndNoIdentityIsNeeded() {
        assertThat(names(search("%", MemberColumn.LOGIN, MemberColumn.NICENAME, MemberColumn.DISPLAY_NAME)))
                .isEmpty();
        assertThat(names(search("_", MemberColumn.LOGIN, MemberColumn.NICENAME, MemberColumn.DISPLAY_NAME)))
                .isEmpty();
        assertThat(names(search("a\\", MemberColumn.DISPLAY_NAME))).isEmpty();
        assertThat(names(search("!", MemberColumn.DISPLAY_NAME))).isEmpty();
        Jdbc.update(
                dataSource,
                "update qp_user_profile set display_name = ? where id = ?",
                "Frank 100%_!x",
                frankId.value());
        assertThat(names(search("0%_!", MemberColumn.DISPLAY_NAME))).containsExactly("frank");
        // a backslash is literal on both databases (no LIKE escape of its own, no string escape)
        Jdbc.update(
                dataSource, "update qp_user_profile set display_name = ? where id = ?", "Erin a\\b", erinId.value());
        assertThat(names(search("a\\", MemberColumn.DISPLAY_NAME))).containsExactly("erin");
        assertThat(names(search("A\\B", MemberColumn.DISPLAY_NAME))).containsExactly("erin");
        assertThat(names(search("a\\%", MemberColumn.DISPLAY_NAME))).isEmpty();
        assertThat(names(search("\\b", MemberColumn.DISPLAY_NAME))).containsExactly("erin");
        assertThat(names(as(
                        anonymous,
                        () -> members(MemberQuery.builder().limit(100).build()))))
                .containsExactly("alice", "bob", "carol", "dave", "erin", "frank");
    }

    @Test
    void excludeAndSlugsAndOtherOrders() {
        assertThat(names(members(
                        MemberQuery.builder().exclude(List.of(bobId, frankId)).build())))
                .containsExactly("alice", "carol", "dave", "erin");
        assertThat(names(members(MemberQuery.builder()
                        .slugs(List.of("erin", "carol", "outsider"))
                        .build())))
                .containsExactly("carol", "erin");
        assertThat(names(members(MemberQuery.builder().slugs(List.of("bob ")).build())))
                .isEmpty();
        // as in WordPress, exclude is ignored when include is given
        assertThat(names(members(MemberQuery.builder()
                        .include(List.of(bobId, carolId))
                        .exclude(List.of(bobId))
                        .build())))
                .containsExactly("bob", "carol");
        // include positions follow the direction; slug positions are always ascending
        assertThat(names(members(MemberQuery.builder()
                        .include(List.of(erinId, aliceId, carolId))
                        .orderBy(MemberOrder.INCLUDE)
                        .ascending(false)
                        .build())))
                .containsExactly("carol", "alice", "erin");
        assertThat(names(members(MemberQuery.builder()
                        .slugs(List.of("dave", "bob", "erin"))
                        .orderBy(MemberOrder.INCLUDE_SLUGS)
                        .ascending(false)
                        .build())))
                .containsExactly("dave", "bob", "erin");
        // ordering by include without include orders by the nicename (WordPress's login), in the direction
        Jdbc.update(dataSource, "update qp_user_profile set display_name = ? where id = ?", "Aaron", frankId.value());
        assertThat(names(members(
                        MemberQuery.builder().orderBy(MemberOrder.INCLUDE).build())))
                .containsExactly("alice", "bob", "carol", "dave", "erin", "frank");
        assertThat(names(members(MemberQuery.builder()
                        .orderBy(MemberOrder.INCLUDE)
                        .ascending(false)
                        .build())))
                .containsExactly("frank", "erin", "dave", "carol", "bob", "alice");
        Jdbc.update(
                dataSource, "update qp_user_profile set display_name = ? where id = ?", "Frank Fox", frankId.value());
        // e-mail: absent first, then by address; descending reverses every key
        assertThat(names(
                        members(MemberQuery.builder().orderBy(MemberOrder.EMAIL).build())))
                .containsExactly("frank", "alice", "bob", "carol", "dave", "erin");
        assertThat(names(members(MemberQuery.builder()
                        .orderBy(MemberOrder.NAME)
                        .ascending(false)
                        .build())))
                .containsExactly("frank", "erin", "dave", "carol", "bob", "alice");
        Jdbc.update(dataSource, "update qp_user_profile set nicename = ? where id = ?", "zed", aliceId.value());
        Jdbc.update(
                dataSource, "update qp_user_profile set display_name = ? where id = ?", "bob baker", carolId.value());
        assertThat(names(members(MemberQuery.builder().orderBy(MemberOrder.SLUG).build())))
                .containsExactly("bob", "carol", "dave", "erin", "frank", "alice");
        // a tie in display name ("Bob Baker", "bob baker") breaks by id
        assertThat(names(members(MemberQuery.builder().limit(3).build()))).containsExactly("alice", "bob", "carol");
        assertThat(names(members(MemberQuery.builder()
                        .ascending(false)
                        .offset(3)
                        .limit(2)
                        .build())))
                .containsExactly("carol", "bob");
    }

    @Test
    void registeredAndUrlOrders() {
        String at = TestDb.kind().equals("postgresql") ? "cast(? as timestamp with time zone)" : "?";
        String[] times = {
            "2020-01-05 00:00:00",
            "2020-01-01 00:00:00",
            "2020-01-04 00:00:00",
            "2020-01-02 00:00:00",
            "2020-01-06 00:00:00",
            "2020-01-03 00:00:00"
        };
        List<UserId> ids = List.of(aliceId, bobId, carolId, daveId, erinId, frankId);
        for (int i = 0; i < ids.size(); i++) {
            Jdbc.update(
                    dataSource,
                    "update qp_user_profile set created_at = " + at + " where id = ?",
                    times[i],
                    ids.get(i).value());
        }
        assertThat(names(members(
                        MemberQuery.builder().orderBy(MemberOrder.REGISTERED).build())))
                .containsExactly("bob", "dave", "frank", "carol", "alice", "erin");
        assertThat(names(members(MemberQuery.builder().orderBy(MemberOrder.URL).build())))
                .containsExactly("alice", "bob", "carol", "dave", "erin", "frank");
        assertThat(names(members(MemberQuery.builder()
                        .orderBy(MemberOrder.ID)
                        .ascending(false)
                        .build())))
                .containsExactly("frank", "erin", "dave", "carol", "bob", "alice");
    }

    @Test
    void aQueryRefusesWhatCannotBeAnswered() {
        assertThatThrownBy(() -> MemberQuery.builder().limit(0).build()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> MemberQuery.builder().offset(-1).build()).isInstanceOf(IllegalArgumentException.class);
        MemberPage none =
                members(MemberQuery.builder().publishedPostTypes(List.of()).build());
        assertThat(none.users()).isEmpty();
        assertThat(none.total()).isZero();
    }

    @Test
    void allcapsAndProfilesByIds() {
        Map<String, Boolean> carols = capabilities.allCapabilities(carolId, MAIN);
        assertThat(carols)
                .containsEntry("author", true)
                .containsEntry("edit_posts", true)
                .containsEntry("level_2", true);
        assertThat(capabilities.allCapabilities(outsiderId, MAIN)).isEmpty();
        assertThat(profiles.findAll(List.of(carolId, UserId.of(987_654), aliceId)))
                .extracting(UserProfile::nicename)
                .containsExactly("carol", "alice");
        assertThat(profiles.findAll(List.of())).isEmpty();
        setCapabilities(daveId, "{\"edit_posts\":false}");
        assertThat(capabilities.allCapabilities(daveId, MAIN))
                .containsEntry("contributor", true)
                .containsEntry("edit_posts", false);
    }
}
