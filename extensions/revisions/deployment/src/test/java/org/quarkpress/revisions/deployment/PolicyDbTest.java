package org.quarkpress.revisions.deployment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.TextNode;
import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import java.time.Instant;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.RevisionId;
import org.quarkpress.core.SiteId;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.PostType;
import org.quarkpress.revisions.RevisionChangeFilter;
import org.quarkpress.revisions.RevisionField;
import org.quarkpress.revisions.RevisionFieldsFilter;
import org.quarkpress.revisions.RevisionMetaKeysFilter;
import org.quarkpress.revisions.RevisionSnapshot;
import org.quarkpress.revisions.RevisionsToKeepFilter;
import org.quarkpress.revisions.runtime.RevisionPolicy;

class PolicyDbTest {

    /** A type with an editor but no custom fields: autosaved, revisioned, no revisioned meta. */
    @PostType(
            key = "note",
            supports = {"title", "editor", "revisions"})
    static class Note {}

    /** A type without revisions support. */
    @PostType(
            key = "card",
            supports = {"title", "editor"})
    static class Card {}

    /** Custom fields and revisions, but no editor. */
    @PostType(
            key = "sheet",
            supports = {"title", "custom-fields", "revisions"})
    static class Sheet {}

    /** Editor and custom fields, but no revisions. */
    @PostType(
            key = "memo",
            supports = {"title", "editor", "custom-fields"})
    static class Memo {}

    /** Runs first: five for pages, and leaves the rest alone. */
    @ApplicationScoped
    @Priority(20)
    static class PagesKeepFive implements RevisionsToKeepFilter {
        @Override
        public int apply(int keep, PostSnapshot post) {
            return post.type().equals("page") ? 5 : keep;
        }
    }

    /** Runs second: re-enables cards (as WordPress's generic filter can) and records what it saw. */
    @Singleton
    @Priority(10)
    static class CardsKeepThree implements RevisionsToKeepFilter {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public int apply(int keep, PostSnapshot post) {
            seen.add(post.type() + "=" + keep);
            return post.type().equals("card") ? 3 : keep;
        }
    }

    @ApplicationScoped
    static class NoExcerpt implements RevisionFieldsFilter {
        @Override
        public Set<RevisionField> apply(Set<RevisionField> fields, PostSnapshot post) {
            Set<RevisionField> narrowed = EnumSet.noneOf(RevisionField.class);
            narrowed.addAll(fields);
            if (post.title().equals("no excerpt")) {
                narrowed.remove(RevisionField.EXCERPT);
            }
            return narrowed;
        }
    }

    @ApplicationScoped
    static class ColorKey implements RevisionMetaKeysFilter {
        @Override
        public Set<String> apply(Set<String> keys, String type) {
            Set<String> added = new HashSet<>(keys);
            if (type.equals("page")) {
                added.add("color");
            }
            return added;
        }
    }

    @Singleton
    static class TitleForcesChange implements RevisionChangeFilter {
        final List<String> seen = new CopyOnWriteArrayList<>();

        @Override
        public boolean apply(boolean changed, RevisionSnapshot last, PostSnapshot current) {
            seen.add(last.id().value() + ":" + changed);
            if (current.title().equals("force")) {
                return true;
            }
            if (current.title().equals("deny")) {
                return false;
            }
            return changed;
        }
    }

    @RegisterExtension
    static final QuarkusExtensionTest app = RevisionsApp.with(
            PolicyDbTest.class,
            Note.class,
            Card.class,
            Sheet.class,
            Memo.class,
            PagesKeepFive.class,
            CardsKeepThree.class,
            NoExcerpt.class,
            ColorKey.class,
            TitleForcesChange.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    RevisionPolicy policy;

    @Inject
    CardsKeepThree cards;

    @Inject
    TitleForcesChange changes;

    static final Instant AT = Instant.parse("2026-01-15T12:00:00Z");

    static PostSnapshot post(
            SiteId site, String type, String title, Map<String, List<com.fasterxml.jackson.databind.JsonNode>> meta) {
        return new PostSnapshot(
                PostId.of(7),
                site,
                type,
                "publish",
                "s",
                title,
                "c",
                "e",
                Optional.empty(),
                Optional.empty(),
                0,
                Optional.of(AT),
                AT,
                AT,
                "open",
                "open",
                "",
                false,
                "g",
                meta,
                1);
    }

    static RevisionSnapshot last(
            SiteId site, String title, Map<String, List<com.fasterxml.jackson.databind.JsonNode>> meta) {
        return new RevisionSnapshot(
                RevisionId.of(8), site, PostId.of(7), Optional.empty(), title, "c", "e", meta, false, AT);
    }

    @Test
    void keepStartsFromTheConfigurationThenTheTypeThenTheFiltersThenTheSiteFeature() throws Exception {
        SiteId site = TestData.site(dataSource);
        cards.seen.clear();
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.keep(post(site, "post", "t", Map.of()))))
                .isEqualTo(-1);
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.keep(post(site, "page", "t", Map.of()))))
                .isEqualTo(5);
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> policy.keep(post(site, "attachment", "t", Map.of()))))
                .isZero();
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.keep(post(site, "card", "t", Map.of()))))
                .isEqualTo(3);
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.keep(post(site, "note", "t", Map.of()))))
                .isEqualTo(-1);
        assertThat(cards.seen).containsExactly("post=-1", "page=5", "attachment=0", "card=0", "note=-1");
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> policy.enabled(post(site, "card", "t", Map.of()))))
                .isTrue();
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> policy.enabled(post(site, "attachment", "t", Map.of()))))
                .isFalse();
        TestData.featureOff(dataSource, site, "revisions");
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.keep(post(site, "post", "t", Map.of()))))
                .isZero();
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.keep(post(site, "card", "t", Map.of()))))
                .isZero();
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> policy.enabled(post(site, "page", "t", Map.of()))))
                .isFalse();
        SiteId other = TestData.site(dataSource);
        assertThat(Identities.onSite(other, Identities.system(), () -> policy.keep(post(other, "post", "t", Map.of()))))
                .isEqualTo(-1);
    }

    @Test
    void revisionedFieldsAreTheThreeUnlessAFilterNarrowsThem() throws Exception {
        SiteId site = TestData.site(dataSource);
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.fields(post(site, "post", "t", Map.of()))))
                .containsExactly(RevisionField.TITLE, RevisionField.CONTENT, RevisionField.EXCERPT);
        assertThat(Identities.onSite(
                        site, Identities.system(), () -> policy.fields(post(site, "post", "no excerpt", Map.of()))))
                .containsExactly(RevisionField.TITLE, RevisionField.CONTENT);
    }

    @Test
    void revisionedMetaKeysAreFootnotesForTypesWithEditorCustomFieldsAndRevisions() throws Exception {
        SiteId site = TestData.site(dataSource);
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("post")))
                .containsExactly("footnotes");
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("wp_block")))
                .containsExactly("footnotes");
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("page")))
                .containsExactlyInAnyOrder("footnotes", "color");
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("attachment")))
                .isEmpty();
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("note")))
                .isEmpty();
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("sheet")))
                .isEmpty();
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("memo")))
                .isEmpty();
        assertThat(Identities.onSite(site, Identities.system(), () -> policy.metaKeys("unknown")))
                .isEmpty();
    }

    @Test
    void theReturnedSetsCannotBeModified() throws Exception {
        SiteId site = TestData.site(dataSource);
        Set<RevisionField> fields =
                Identities.onSite(site, Identities.system(), () -> policy.fields(post(site, "post", "t", Map.of())));
        assertThatThrownBy(() -> fields.remove(RevisionField.TITLE)).isInstanceOf(UnsupportedOperationException.class);
        Set<String> keys = Identities.onSite(site, Identities.system(), () -> policy.metaKeys("post"));
        assertThatThrownBy(() -> keys.add("x")).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void theChangeDecisionComparesFieldsThenMetaThenAsksTheFilter() throws Exception {
        SiteId site = TestData.site(dataSource);
        changes.seen.clear();
        List<com.fasterxml.jackson.databind.JsonNode> one = List.of(TextNode.valueOf("[1]"));
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> policy.changed(
                                last(site, "t", Map.of("footnotes", one)),
                                post(site, "post", "t", Map.of("footnotes", one)))))
                .isFalse();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> policy.changed(last(site, "t", Map.of()), post(site, "post", "t ", Map.of()))))
                .isFalse();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> policy.changed(last(site, "t", Map.of()), post(site, "post", "t2", Map.of()))))
                .isTrue();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> policy.changed(
                                last(site, "t", Map.of()), post(site, "post", "t", Map.of("footnotes", one)))))
                .isTrue();
        // a type without revisioned meta ignores a meta difference
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> policy.changed(
                                last(site, "t", Map.of()), post(site, "note", "t", Map.of("footnotes", one)))))
                .isFalse();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> policy.changed(last(site, "force", Map.of()), post(site, "post", "force", Map.of()))))
                .isTrue();
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> policy.changed(last(site, "x", Map.of()), post(site, "post", "deny", Map.of()))))
                .isFalse();
        // only the excerpt differs: ignored when a filter leaves the excerpt out, a change otherwise
        RevisionSnapshot oldExcerpt = new RevisionSnapshot(
                RevisionId.of(8), site, PostId.of(7), Optional.empty(), "no excerpt", "c", "old", Map.of(), false, AT);
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> policy.changed(oldExcerpt, post(site, "post", "no excerpt", Map.of()))))
                .isFalse();
        RevisionSnapshot plainOldExcerpt = new RevisionSnapshot(
                RevisionId.of(8), site, PostId.of(7), Optional.empty(), "plain", "c", "old", Map.of(), false, AT);
        assertThat(Identities.onSite(
                        site,
                        Identities.system(),
                        () -> policy.changed(plainOldExcerpt, post(site, "post", "plain", Map.of()))))
                .isTrue();
        assertThat(changes.seen)
                .containsExactly(
                        "8:false", "8:false", "8:true", "8:true", "8:false", "8:false", "8:true", "8:false", "8:true");
    }
}
