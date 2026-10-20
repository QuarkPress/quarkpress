package org.quarkpress.testing.wpscenario;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PostRecordsTest {

    @Test
    void datesInsideTheRunShowAsNow() {
        RunWindow window = new RunWindow(Instant.parse("2026-10-20T10:00:00Z"), Instant.parse("2026-10-20T10:00:05Z"));
        assertThat(window.show(Instant.parse("2026-10-20T10:00:03Z"), "2026-10-20 12:00:03"))
                .isEqualTo("now");
        assertThat(window.show(Instant.parse("2026-10-20T09:59:59Z"), "x")).isEqualTo("now");
        assertThat(window.show(Instant.parse("2020-05-05T08:00:00Z"), "2020-05-05 10:00:00"))
                .isEqualTo("2020-05-05 10:00:00");
        assertThat(PostRecords.utc("2020-05-05 08:00:00")).isEqualTo(Instant.parse("2020-05-05T08:00:00Z"));
    }

    @Test
    void ownIdsInSlugsAndIdsInGuidsAreNamedByIndex() {
        assertThat(PostRecords.slug("123", 123, Map.of())).isEqualTo("#self");
        assertThat(PostRecords.slug("123-2", 123, Map.of())).isEqualTo("#self-2");
        assertThat(PostRecords.slug("1234", 123, Map.of())).isEqualTo("1234");
        assertThat(PostRecords.slug("hello", 123, Map.of())).isEqualTo("hello");
        Map<Long, Integer> index = Map.of(57L, 3);
        assertThat(PostRecords.guid("http://localhost/?p=57", "http://localhost", index))
                .isEqualTo("home/?p=p3");
        assertThat(PostRecords.guid("http://localhost/?page_id=57", "http://localhost", index))
                .isEqualTo("home/?page_id=p3");
        assertThat(PostRecords.guid("http://localhost/?post_type=wp_block&p=57", "http://localhost", index))
                .isEqualTo("home/?post_type=wp_block&p=p3");
        assertThat(PostRecords.guid("http://elsewhere/2020/x/", "http://localhost", index))
                .isEqualTo("http://elsewhere/2020/x/");
    }

    @Test
    void referencesNameUsersAndPostsByIndex() {
        assertThat(PostRecords.postRef(0, Map.of())).isEqualTo("none");
        assertThat(PostRecords.postRef(57, Map.of(57L, 3))).isEqualTo("p3");
        assertThat(PostRecords.postRef(58, Map.of(57L, 3))).isEqualTo("#?58");
        assertThat(PostRecords.userRef(0, Map.of(), 1)).isEqualTo("none");
        assertThat(PostRecords.userRef(1, Map.of(), 1)).isEqualTo("admin");
        assertThat(PostRecords.userRef(9, Map.of(9L, 2), 1)).isEqualTo("u2");
    }

    @Test
    void frontPageOptionsNameTheirPostByIndex() {
        Map<Long, Integer> index = Map.of(57L, 3);
        assertThat(PostRecords.optionText("page_on_front", "57", index)).isEqualTo("p3");
        assertThat(PostRecords.optionText("page_for_posts", "0", index)).isEqualTo("none");
        assertThat(PostRecords.optionText("page_for_posts", "58", index)).isEqualTo("#?58");
        assertThat(PostRecords.optionText("posts_per_page", "57", index)).isEqualTo("57");
        assertThat(PostRecords.optionText("page_on_front", "", index)).isEqualTo("");
    }

    @Test
    void datesAreReadStrictly() {
        assertThat(PostRecords.parse("2020-02-29 23:59:59"))
                .isEqualTo(java.time.LocalDateTime.of(2020, 2, 29, 23, 59, 59));
        assertThat(PostRecords.format(java.time.LocalDateTime.of(1999, 1, 2, 3, 4, 5)))
                .isEqualTo("1999-01-02 03:04:05");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> PostRecords.parse("2021-02-29 10:00:00"))
                .isInstanceOf(java.time.format.DateTimeParseException.class);
    }

    @Test
    void guidsCompareOnlyForPostsInsertedUnderPlainPermalinks() {
        String insert = "{\"op\": \"insert_post\", \"as\": 0, \"fields\": {}}";
        String plain = "{\"op\": \"site_option\", \"name\": \"permalink_structure\", \"value\": \"\"}";
        String pretty = "{\"op\": \"site_option\", \"name\": \"permalink_structure\", \"value\": \"/%postname%/\"}";
        // WordPress's network setup gives the main site a pretty structure
        assertThat(PostRecords.includeGuid(scenario(insert))).isFalse();
        assertThat(PostRecords.includeGuid(scenario(plain, insert, pretty))).isTrue();
        assertThat(PostRecords.includeGuid(scenario(plain, insert, pretty, insert)))
                .isFalse();
        assertThat(PostRecords.includeGuid(scenario(pretty, insert))).isFalse();
    }

    private static Scenario scenario(String... ops) {
        return ScenarioJson.parse("{\"id\": \"g\", \"rule\": \"POST1\", \"variant\": \"subdirectory\", \"ops\": ["
                + String.join(", ", ops) + "]}");
    }

    @Test
    void localTextsWithinFourteenHoursOfTheRunShowAsNow() {
        RunWindow window = new RunWindow(Instant.parse("2026-10-20T10:00:00Z"), Instant.parse("2026-10-20T10:00:05Z"));
        assertThat(window.showLocal("2026-10-20 23:59:59")).isEqualTo("now");
        assertThat(window.showLocal("2026-10-19 20:00:00")).isEqualTo("now");
        assertThat(window.showLocal("2026-10-21 00:00:06")).isEqualTo("2026-10-21 00:00:06");
        assertThat(window.showLocal("2020-05-05 10:00:00")).isEqualTo("2020-05-05 10:00:00");
        assertThat(window.showLocal(PostRecords.ZERO_DATE)).isEqualTo(PostRecords.ZERO_DATE);
    }

    @Test
    void slugTokensThatAreIdsAreNamedByIndex() {
        Map<Long, Integer> index = Map.of(123L, 1, 57L, 3);
        assertThat(PostRecords.slug("123", 123, index)).isEqualTo("#self");
        assertThat(PostRecords.slug("123-2", 123, index)).isEqualTo("#self-2");
        assertThat(PostRecords.slug("123__trashed", 123, index)).isEqualTo("#self__trashed");
        assertThat(PostRecords.slug("123-2__trashed", 123, index)).isEqualTo("#self-2__trashed");
        assertThat(PostRecords.slug("57-3", 123, index)).isEqualTo("#p3-3");
        // a typed slug that only starts with the id is left alone
        assertThat(PostRecords.slug("123-things", 123, index)).isEqualTo("123-things");
        assertThat(PostRecords.slug("123-", 123, index)).isEqualTo("123-");
        assertThat(PostRecords.slug("0123", 123, index)).isEqualTo("0123");
        assertThat(PostRecords.slug("58", 123, index)).isEqualTo("58");
        assertThat(PostRecords.slug("hello__trashed", 123, index)).isEqualTo("hello__trashed");
        assertThat(PostRecords.pageUri("57/123-2/x", index)).isEqualTo("#p3/#p1-2/x");
        assertThat(PostRecords.pageUri("hello/", index)).isEqualTo("hello/");
        assertThat(PostRecords.pageUri("", index)).isEqualTo("");
    }

    @Test
    void anUnorderedQueryIsRecordedAsASortedSet() {
        assertThat(PostRecords.sortedRefs(java.util.List.of("p10", "#?7", "p2", "p1")))
                .containsExactly("p1", "p2", "p10", "#?7");
    }

    @Test
    void theOffsetOfALocalTextIsInMinutes() {
        assertThat(PostRecords.offsetMinutes("2020-01-01 00:00:00", "2019-12-31 23:00:00"))
                .isEqualTo("60");
        assertThat(PostRecords.offsetMinutes("2020-01-01 00:00:00", "2020-01-01 05:30:00"))
                .isEqualTo("-330");
        assertThat(PostRecords.offsetMinutes("2020-01-01 00:00:00", PostRecords.ZERO_DATE))
                .isEqualTo("zero");
    }
}
