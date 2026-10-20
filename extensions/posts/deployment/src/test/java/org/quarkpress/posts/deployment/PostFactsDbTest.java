package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.runtime.PostCapabilityFacts;
import org.quarkpress.posts.runtime.PostEntity;
import org.quarkpress.posts.runtime.PostRepository;
import org.quarkpress.security.Capabilities;
import org.quarkpress.security.PostFacts;

class PostFactsDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(PostFactsDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    PostRepository posts;

    @Inject
    PostCapabilityFacts facts;

    @Inject
    Capabilities capabilities;

    private PostFacts factsOf(SiteId site, PostId post) throws Exception {
        return Identities.onSite(
                site,
                Identities.system(),
                () -> facts.of(
                        posts.findPost(site, post).map(PostEntity::toSnapshot).orElseThrow()));
    }

    private PostId postBy(SiteId site, UserId author, String status) {
        PostId post = TestData.post(dataSource, site, "post", status, "", "By " + author.value());
        Jdbc.update(dataSource, "update qp_post set author_id = ? where id = ?", author.value(), post.value());
        return post;
    }

    @Test
    void securityAnswersPostCapabilitiesFromTheFacts() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        UserId author = TestData.memberOfMain(dataSource, "author");
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        PostId ownDraft = postBy(site, contributor, "draft");
        PostId ownPublished = postBy(site, contributor, "publish");
        PostId othersPrivate = postBy(site, editor, "private");

        assertThat(capabilities.userCan(contributor, TestData.MAIN, "edit_post", Optional.of(factsOf(site, ownDraft))))
                .isTrue();
        assertThat(capabilities.userCan(
                        contributor, TestData.MAIN, "edit_post", Optional.of(factsOf(site, ownPublished))))
                .isFalse();
        assertThat(capabilities.userCan(editor, TestData.MAIN, "edit_post", Optional.of(factsOf(site, ownPublished))))
                .isTrue();
        assertThat(capabilities.userCan(author, TestData.MAIN, "read_post", Optional.of(factsOf(site, othersPrivate))))
                .isFalse();
        assertThat(capabilities.userCan(editor, TestData.MAIN, "read_post", Optional.of(factsOf(site, othersPrivate))))
                .isTrue();
    }

    @Test
    void aTrashedPostIsCheckedWithItsStatusBeforeTrashing() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId author = TestData.memberOfMain(dataSource, "author");
        PostId trashed = postBy(site, author, "trash");
        Jdbc.update(
                dataSource,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_wp_trash_meta_status\":[\"publish\"]}",
                trashed.value());
        PostFacts facts = factsOf(site, trashed);
        assertThat(facts.statusBeforeTrash()).contains("publish");
        assertThat(capabilities.userCan(author, TestData.MAIN, "delete_post", Optional.of(facts)))
                .isTrue();
        UserId contributor = TestData.memberOfMain(dataSource, "contributor");
        PostId contributorsTrashed = postBy(site, contributor, "trash");
        Jdbc.update(
                dataSource,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_wp_trash_meta_status\":[\"publish\"]}",
                contributorsTrashed.value());
        assertThat(capabilities.userCan(
                        contributor, TestData.MAIN, "delete_post", Optional.of(factsOf(site, contributorsTrashed))))
                .isFalse();
    }

    @Test
    void anAttachmentReadsItsParentFromTheDatabase() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId author = TestData.memberOfMain(dataSource, "author");
        UserId editor = TestData.memberOfMain(dataSource, "editor");
        PostId parent = postBy(site, editor, "private");
        PostId attachment = TestData.post(dataSource, site, "attachment", "inherit", "", "file");
        Jdbc.update(dataSource, "update qp_post set parent_id = ? where id = ?", parent.value(), attachment.value());

        PostFacts inherited = factsOf(site, attachment);
        assertThat(inherited.effectiveStatus()).isEqualTo("private");
        assertThat(capabilities.userCan(author, TestData.MAIN, "read_post", Optional.of(inherited)))
                .isFalse();
        assertThat(capabilities.userCan(editor, TestData.MAIN, "read_post", Optional.of(inherited)))
                .isTrue();

        Jdbc.update(dataSource, "update qp_post set status = 'trash' where id = ?", parent.value());
        Jdbc.update(
                dataSource,
                "update qp_post set meta = " + TestDb.json() + " where id = ?",
                "{\"_wp_trash_meta_status\":[\"draft\"]}",
                parent.value());
        assertThat(factsOf(site, attachment).effectiveStatus()).isEqualTo("draft");
    }
}
