package org.quarkpress.security.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.ForbiddenException;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.security.UserMeta;

class UserMetaMultisiteDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = SecurityApp.multisite(UserMetaMultisiteDbTest.class);

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    UserMeta meta;

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    private SecurityIdentity identity(UserId user) {
        return Identities.user(user, TestData.subject(dataSource, user));
    }

    private void set(SecurityIdentity identity, UserId user, String value) {
        Identities.as(identity, () -> {
            meta.set(user, "k", TextNode.valueOf(value));
            return null;
        });
    }

    @Test
    void inANetworkOnlyAUserManagerOfTheNetworkEditsAnotherUsersMeta() {
        UserId member = TestData.profile(dataSource, "member");
        TestData.member(dataSource, MAIN, member, "[\"author\"]", "{}");
        UserId siteAdmin = TestData.profile(dataSource, "siteadmin");
        TestData.member(dataSource, MAIN, siteAdmin, "[\"administrator\"]", "{}");
        UserId networkAdmin = TestData.profile(dataSource, "networkadmin");
        TestData.networkAdmin(dataSource, networkAdmin);

        // a site administrator (edit_users on the site) lacks manage_network_users
        assertThatThrownBy(() -> set(identity(siteAdmin), member, "by site admin"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability edit_user for this request");
        assertThat(meta.get(member, "k")).isEmpty();
        // a network administrator manages the network's users
        set(identity(networkAdmin), member, "by network admin");
        assertThat(meta.get(member, "k")).contains(TextNode.valueOf("by network admin"));
        // and oneself always
        set(identity(member), member, "by oneself");
        assertThat(meta.get(member, "k")).contains(TextNode.valueOf("by oneself"));
        // a site administrator may not edit a network administrator's meta either
        assertThatThrownBy(() -> set(identity(siteAdmin), networkAdmin, "x"))
                .isInstanceOf(ForbiddenException.class)
                .hasMessage("the caller lacks the capability edit_user for this request");
        assertThat(meta.get(networkAdmin, "k")).isEmpty();
    }
}
