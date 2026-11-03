package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.MetaKeyDefinition;
import org.quarkpress.posts.MetaKeyProvider;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostMeta;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostTypes;
import org.quarkpress.posts.Posts;
import org.quarkpress.revisions.runtime.RevisionPolicy;

/** The revisioned meta keys come from the posts meta registry: the keys registered with revisions enabled. */
class RevisionMetaKeysDbTest {

    @Singleton
    static class NoteKeys implements MetaKeyProvider {
        @Override
        public List<MetaKeyDefinition> metaKeys(PostTypes types) {
            return List.of(
                    MetaKeyDefinition.builder("book_note")
                            .subtype("post")
                            .single()
                            .showInRest()
                            .revisions()
                            .build(),
                    MetaKeyDefinition.builder("plain_note")
                            .subtype("post")
                            .single()
                            .showInRest()
                            .build(),
                    MetaKeyDefinition.builder("any_note").single().revisions().build());
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(RevisionMetaKeysDbTest.class, NoteKeys.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    @Inject
    PostMeta meta;

    @Inject
    RevisionPolicy policy;

    @Inject
    TestClock clock;

    @Test
    void theRevisionedKeysAreTheRegisteredOnesWithRevisionsEnabled() throws Exception {
        SiteId site = TestData.site(dataSource);
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("post")))
                .containsExactly("any_note", "footnotes", "book_note");
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("page")))
                .containsExactly("any_note", "footnotes");
        // an every-type key is revisioned only where revisions are supported
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("attachment")))
                .isEmpty();
    }

    @Test
    void aRevisionCopiesTheRegisteredRevisionedKeysAndFootnotesButNotTheOthers() throws Exception {
        SiteId site = TestData.site(dataSource);
        UserId editor = TestData.profile(dataSource, "editor");
        TestData.member(dataSource, TestData.MAIN, editor, "[\"editor\"]");
        clock.set(Instant.parse("2026-01-15T12:00:00Z"));
        PostSnapshot post = Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.create(PostChanges.builder()
                        .title("T")
                        .content("c")
                        .status("publish")
                        .build()));
        Identities.onSite(site, Identities.user(editor), () -> {
            meta.update(post.id(), "plain_note", TextNode.valueOf("p"));
            meta.update(post.id(), "footnotes", TextNode.valueOf("[]"));
            meta.update(post.id(), "book_note", TextNode.valueOf("n"));
            meta.update(post.id(), "any_note", TextNode.valueOf("a"));
            return null;
        });
        clock.set(Instant.parse("2026-01-15T12:01:00Z"));
        Identities.onSite(
                site,
                Identities.user(editor),
                () -> posts.update(post.id(), PostChanges.builder().title("T2").build()));
        assertThat(Jdbc.strings(
                        dataSource,
                        "select meta from qp_revision where post_id = ? order by id",
                        post.id().value()))
                .containsExactly("{\"footnotes\":[\"[]\"],\"book_note\":[\"n\"],\"any_note\":[\"a\"]}");
    }
}
