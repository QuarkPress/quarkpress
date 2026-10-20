package org.quarkpress.posts.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.quarkpress.core.PostId;

class UniqueSlugsTest {

    /** A site's posts in memory. */
    static final class Store implements UniqueSlugs.Lookup {
        record Row(long id, String type, String slug, long parent) {}

        final List<Row> rows = new ArrayList<>();
        long next = 1;

        Store add(String type, String slug, long parent) {
            rows.add(new Row(next++, type, slug, parent));
            return this;
        }

        @Override
        public boolean anyType(String slug, Optional<PostId> exclude) {
            return rows.stream().anyMatch(r -> r.slug().equals(slug) && !excluded(r, exclude));
        }

        @Override
        public boolean sameType(String slug, String type, Optional<PostId> exclude) {
            return rows.stream().anyMatch(r -> r.slug().equals(slug) && r.type().equals(type) && !excluded(r, exclude));
        }

        @Override
        public boolean sameTypeOrAttachmentUnder(
                String slug, String type, Optional<PostId> parent, Optional<PostId> exclude) {
            long parentId = parent.map(PostId::value).orElse(0L);
            return rows.stream()
                    .anyMatch(r -> r.slug().equals(slug)
                            && (r.type().equals(type) || r.type().equals("attachment"))
                            && r.parent() == parentId
                            && !excluded(r, exclude));
        }

        private static boolean excluded(Row row, Optional<PostId> exclude) {
            return exclude.map(id -> id.value() == row.id()).orElse(false);
        }
    }

    private static String flat(Store store, String type, String slug) {
        return UniqueSlugs.unique(slug, Optional.empty(), Optional.empty(), type, Optional.empty(), false, "", store);
    }

    private static String post(Store store, String slug, String structure) {
        return UniqueSlugs.unique(
                slug, Optional.empty(), Optional.empty(), "post", Optional.empty(), false, structure, store);
    }

    private static String page(Store store, String slug, long parent) {
        return UniqueSlugs.unique(
                slug,
                Optional.empty(),
                Optional.empty(),
                "page",
                parent == 0 ? Optional.empty() : Optional.of(PostId.of(parent)),
                true,
                "",
                store);
    }

    private static String attachment(Store store, String slug) {
        return UniqueSlugs.unique(
                slug, Optional.empty(), Optional.empty(), "attachment", Optional.empty(), false, "", store);
    }

    @Test
    void draftsPendingAndAutoDraftsAreExempt() {
        assertThat(UniqueSlugs.exempt("draft", "post")).isTrue();
        assertThat(UniqueSlugs.exempt("pending", "page")).isTrue();
        assertThat(UniqueSlugs.exempt("auto-draft", "post")).isTrue();
        assertThat(UniqueSlugs.exempt("inherit", "revision")).isTrue();
        assertThat(UniqueSlugs.exempt("publish", "user_request")).isTrue();
        assertThat(UniqueSlugs.exempt("trash", "post")).isFalse();
        assertThat(UniqueSlugs.exempt("inherit", "attachment")).isFalse();
    }

    @Test
    void flatTypesCheckOnlyTheirOwnType() {
        Store store = new Store()
                .add("page", "about", 0)
                .add("attachment", "photo", 0)
                .add("wp_block", "cta", 0);
        assertThat(flat(store, "post", "about")).isEqualTo("about");
        assertThat(flat(store, "post", "photo")).isEqualTo("photo");
        assertThat(flat(store, "wp_block", "cta")).isEqualTo("cta-2");
    }

    @Test
    void pagesCheckTheirTypeAndAttachmentsUnderTheSameParent() {
        Store store =
                new Store().add("page", "team", 0).add("attachment", "logo", 0).add("attachment", "mark", 9);
        assertThat(page(store, "team", 0)).isEqualTo("team-2");
        assertThat(page(store, "team", 5)).isEqualTo("team");
        assertThat(page(store, "logo", 0)).isEqualTo("logo-2");
        assertThat(page(store, "mark", 0)).isEqualTo("mark");
    }

    @Test
    void pageSlugsThatLookLikePaginationAreSuffixed() {
        Store store = new Store();
        assertThat(page(store, "2024", 0)).isEqualTo("2024-2");
        assertThat(page(store, "page3", 0)).isEqualTo("page3-2");
        assertThat(page(store, "404", 0)).isEqualTo("404-2");
        assertThat(page(store, "pages3", 0)).isEqualTo("pages3");
        assertThat(page(store, "0", 0)).isEqualTo("0-2");
    }

    @Test
    void attachmentsCheckEveryType() {
        Store store = new Store().add("page", "about", 3).add("post", "sunset", 0);
        assertThat(attachment(store, "about")).isEqualTo("about-2");
        assertThat(attachment(store, "sunset")).isEqualTo("sunset-2");
    }

    @Test
    void feedNamesAndEmbedAreReservedEverywhere() {
        Store store = new Store();
        assertThat(post(store, "feed", "")).isEqualTo("feed-2");
        assertThat(page(store, "rss", 0)).isEqualTo("rss-2");
        assertThat(attachment(store, "embed")).isEqualTo("embed-2");
        assertThat(post(store, "feeds", "")).isEqualTo("feeds");
        assertThat(post(store, "atom", "")).isEqualTo("atom-2");
        assertThat(post(store, "rdf", "")).isEqualTo("rdf-2");
        assertThat(post(store, "rss2", "")).isEqualTo("rss2-2");
    }

    @Test
    void numericPostSlugsConflictWithDateArchivesOfThePermalinkStructure() {
        Store store = new Store();
        assertThat(post(store, "2024", "/%postname%/")).isEqualTo("2024-2");
        assertThat(post(store, "007", "/%postname%/")).isEqualTo("007-2");
        assertThat(post(store, "0", "/%postname%/")).isEqualTo("0");
        assertThat(post(store, "12", "/%year%/%postname%/")).isEqualTo("12-2");
        assertThat(post(store, "13", "/%year%/%postname%/")).isEqualTo("13");
        assertThat(post(store, "31", "/%year%/%monthnum%/%postname%/")).isEqualTo("31-2");
        assertThat(post(store, "32", "/%year%/%monthnum%/%postname%/")).isEqualTo("32");
        assertThat(post(store, "5", "/%year%/%monthnum%/%day%/%postname%/")).isEqualTo("5");
        assertThat(post(store, "2024", "")).isEqualTo("2024");
        assertThat(UniqueSlugs.unique(
                        "2024",
                        Optional.of(PostId.of(99)),
                        Optional.of("2024"),
                        "post",
                        Optional.empty(),
                        false,
                        "/%postname%/",
                        store))
                .isEqualTo("2024");
        assertThat(flat(store, "wp_block", "2024")).isEqualTo("2024");
    }

    @Test
    void zeroSegmentsOfThePermalinkStructureAreIgnored() {
        Store store = new Store();
        assertThat(post(store, "13", "/0/%postname%/")).isEqualTo("13-2");
        assertThat(post(store, "12", "/%year%/0/%postname%/")).isEqualTo("12-2");
        assertThat(post(store, "13", "/%year%/0/%postname%/")).isEqualTo("13");
    }

    @Test
    void aPostKeepsItsOwnSlugInEveryScope() {
        Store store = new Store().add("attachment", "about", 0).add("page", "team", 0);
        assertThat(UniqueSlugs.unique(
                        "about",
                        Optional.of(PostId.of(1)),
                        Optional.of("about"),
                        "attachment",
                        Optional.empty(),
                        false,
                        "",
                        store))
                .isEqualTo("about");
        assertThat(UniqueSlugs.unique(
                        "team",
                        Optional.of(PostId.of(2)),
                        Optional.of("team"),
                        "page",
                        Optional.empty(),
                        true,
                        "",
                        store))
                .isEqualTo("team");
    }

    @Test
    void pagesIgnoreOtherTypesAndCheckSiblingsUnderAnyParent() {
        Store store = new Store().add("post", "team", 0).add("page", "crew", 5);
        assertThat(page(store, "team", 0)).isEqualTo("team");
        assertThat(page(store, "crew", 5)).isEqualTo("crew-2");
    }

    @Test
    void attachmentsCheckEveryTypeEvenWhenTheTypeIsHierarchical() {
        Store store = new Store().add("post", "sunset", 0);
        assertThat(UniqueSlugs.unique(
                        "sunset", Optional.empty(), Optional.empty(), "attachment", Optional.empty(), true, "", store))
                .isEqualTo("sunset-2");
    }

    @Test
    void menuItemsKeepTheirSlug() {
        Store store = new Store();
        assertThat(UniqueSlugs.unique(
                        "feed", Optional.empty(), Optional.empty(), "nav_menu_item", Optional.empty(), true, "", store))
                .isEqualTo("feed");
    }

    @Test
    void suffixesCountFromTwoAndAreCutFromTheOriginal() {
        Store store = new Store().add("post", "hello", 0).add("post", "hello-2", 0);
        assertThat(post(store, "hello", "")).isEqualTo("hello-3");
        Store onlySecond = new Store().add("post", "hello-2", 0);
        assertThat(post(onlySecond, "hello", "")).isEqualTo("hello");
        assertThat(post(onlySecond, "hello-2", "")).isEqualTo("hello-2-2");
    }

    @Test
    void longSlugsAreCutToFitTheSuffix() {
        String a200 = "a".repeat(200);
        Store store = new Store().add("post", a200, 0);
        assertThat(post(store, a200, "")).isEqualTo("a".repeat(198) + "-2");
        for (int n = 2; n <= 9; n++) {
            store.add("post", "a".repeat(198) + "-" + n, 0);
        }
        assertThat(post(store, a200, "")).isEqualTo("a".repeat(197) + "-10");

        String day = "%e6%97%a5".repeat(22);
        Store days = new Store().add("post", day, 0);
        assertThat(post(days, day, "")).isEqualTo(day + "-2");
        for (int n = 2; n <= 9; n++) {
            days.add("post", day + "-" + n, 0);
        }
        assertThat(post(days, day, "")).isEqualTo("%e6%97%a5".repeat(21) + "-10");

        String pairs = "ab-".repeat(66) + "ab";
        Store pairStore = new Store().add("post", pairs, 0);
        assertThat(post(pairStore, pairs, "")).isEqualTo("ab-".repeat(65) + "ab-2");
    }

    @Test
    void thePostItselfAndEmptyOrZeroSlugsNeverConflict() {
        Store store = new Store().add("post", "hello", 0).add("post", "", 0).add("post", "0", 0);
        assertThat(UniqueSlugs.unique(
                        "hello",
                        Optional.of(PostId.of(1)),
                        Optional.of("hello"),
                        "post",
                        Optional.empty(),
                        false,
                        "",
                        store))
                .isEqualTo("hello");
        assertThat(post(store, "", "")).isEmpty();
        assertThat(post(store, "0", "")).isEqualTo("0");
    }

    @Test
    void trashingAppendsTheSuffixOnce() {
        assertThat(UniqueSlugs.withTrashedSuffix("hello")).isEqualTo("hello__trashed");
        assertThat(UniqueSlugs.withTrashedSuffix("")).isEqualTo("__trashed");
        assertThat(UniqueSlugs.withTrashedSuffix("a".repeat(200))).isEqualTo("a".repeat(191) + "__trashed");
        assertThat(UniqueSlugs.withTrashedSuffix("%e6%97%a5".repeat(22)))
                .isEqualTo("%e6%97%a5".repeat(21) + "__trashed");
        assertThat(UniqueSlugs.isTrashedSlug("hello__trashed")).isTrue();
        assertThat(UniqueSlugs.isTrashedSlug("hello__trashed-2")).isFalse();
    }
}
