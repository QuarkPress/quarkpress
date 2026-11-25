package org.quarkword.front.runtime;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkword.core.PostId;
import org.quarkword.core.SiteOptions;
import org.quarkword.core.UserId;
import org.quarkword.front.QueryVars;
import org.quarkword.front.Viewer;
import org.quarkword.posts.DateCondition;
import org.quarkword.posts.OrderKey;
import org.quarkword.posts.PostOrder;
import org.quarkword.posts.PostQuery;
import org.quarkword.posts.PostQuery.DateUnits;
import org.quarkword.security.UserProfile;
import org.quarkword.security.UserProfiles;

/**
 * The main query WordPress 7.1.3 builds from a request's variables, as {@code PostQuery} fields: every expectation
 * comes from the SQL {@code WP_Query::get_posts} wrote for the same variables (main-query.php / main-query.out in the
 * slice folder).
 */
class MainQueryTest {

    /** carol (3) is the only user found by nicename. */
    static final class FakeProfiles implements UserProfiles {
        @Override
        public Optional<UserProfile> find(UserId user) {
            return Optional.empty();
        }

        @Override
        public Optional<UserProfile> findBySubject(String subject) {
            return Optional.empty();
        }

        @Override
        public Optional<UserProfile> findByNicename(String nicename) {
            return nicename.equals("carol")
                    ? Optional.of(new UserProfile(
                            UserId.of(3),
                            "carol",
                            "Carol",
                            "carol",
                            Optional.empty(),
                            Optional.empty(),
                            Instant.EPOCH,
                            Optional.empty()))
                    : Optional.empty();
        }

        @Override
        public List<UserProfile> findAll(Collection<UserId> users) {
            return List.of();
        }
    }

    static MainQuery mainQuery() {
        MainQuery query = new MainQuery();
        query.profiles = new FakeProfiles();
        query.pages = FlagParserTest.cast();
        return query;
    }

    static PostQuery build(String query, SiteOptions options) {
        FlagParser.Parsed parsed = FlagParserTest.parse(query, options);
        return mainQuery()
                .build(parsed, options, FlagParserTest.REGISTRIES, Viewer.anonymous())
                .orElseThrow();
    }

    static PostQuery build(String query) {
        return build(query, FlagParserTest.BLOG_FRONT);
    }

    static PostOrder desc(OrderKey key) {
        return new PostOrder(key, false);
    }

    static PostOrder asc(OrderKey key) {
        return new PostOrder(key, true);
    }

    static LocalDateTime at(String text) {
        return LocalDateTime.parse(text);
    }

    @Test
    void searchCoversTheSearchableTypesAndRanksByRelevanceUnlessOrderedOrAFeed() {
        PostQuery search = build("s=red");
        // the test registry has the book type, which WordPress searches too (main-query.out: attachment, book,
        // page, post)
        assertThat(search.types()).containsExactly("post", "page", "attachment", "book");
        assertThat(search.search()).contains("red");
        assertThat(search.orderBy()).containsExactly(desc(OrderKey.RELEVANCE), desc(OrderKey.DATE));
        assertThat(build("s=red&orderby=title&order=asc").orderBy()).containsExactly(asc(OrderKey.TITLE));
        assertThat(build("feed=rss2&s=red").orderBy()).containsExactly(desc(OrderKey.DATE));
        assertThat(build("s=red&orderby=relevance").orderBy())
                .containsExactly(desc(OrderKey.RELEVANCE), desc(OrderKey.DATE));
        assertThat(build("s=red&order=asc").orderBy()).containsExactly(asc(OrderKey.RELEVANCE), asc(OrderKey.DATE));
        // an exact search builds no title rank
        PostQuery exact = build("s=red&exact=1");
        assertThat(exact.exactSearch()).isTrue();
        assertThat(exact.orderBy()).containsExactly(desc(OrderKey.DATE));
        assertThat(build("s=red%20car&sentence=1").sentence()).isTrue();
        // an empty search searches nothing but still lists the searchable types, newest first
        PostQuery empty = build("s=");
        assertThat(empty.types()).containsExactly("post", "page", "attachment", "book");
        assertThat(empty.searching()).isFalse();
        assertThat(empty.orderBy()).containsExactly(desc(OrderKey.DATE));
    }

    @Test
    void aSearchFromThePathIsDecodedOnce() {
        QueryVars fromPath = QueryVars.of(Map.of("s", "red%20car"));
        FlagParser.Parsed parsed = FlagParser.parse(
                fromPath,
                FlagParserTest.BLOG_FRONT,
                FlagParserTest.REGISTRIES,
                FlagParserTest.cast(),
                Structure.of(FlagParserTest.DEFAULT, "", ""));
        assertThat(mainQuery()
                        .build(parsed, FlagParserTest.BLOG_FRONT, FlagParserTest.REGISTRIES, Viewer.anonymous())
                        .orElseThrow()
                        .search())
                .contains("red car");
        QueryVars fromQuery = fromPath.fromQueryString(Set.of("s"));
        FlagParser.Parsed parsedQuery = FlagParser.parse(
                fromQuery,
                FlagParserTest.BLOG_FRONT,
                FlagParserTest.REGISTRIES,
                FlagParserTest.cast(),
                Structure.of(FlagParserTest.DEFAULT, "", ""));
        assertThat(mainQuery()
                        .build(parsedQuery, FlagParserTest.BLOG_FRONT, FlagParserTest.REGISTRIES, Viewer.anonymous())
                        .orElseThrow()
                        .search())
                .contains("red%20car");
    }

    @Test
    void orderKeysMapAsWordPressMapsThem() {
        assertThat(build("").orderBy()).containsExactly(desc(OrderKey.DATE));
        assertThat(build("orderby=title").orderBy()).containsExactly(desc(OrderKey.TITLE));
        assertThat(build("orderby=title%20date&order=asc").orderBy())
                .containsExactly(asc(OrderKey.TITLE), asc(OrderKey.DATE));
        assertThat(build("orderby=foo%20title").orderBy()).containsExactly(desc(OrderKey.TITLE));
        assertThat(build("orderby=foo").orderBy()).containsExactly(desc(OrderKey.DATE));
        assertThat(build("order=sideways").orderBy()).containsExactly(desc(OrderKey.DATE));
        assertThat(build("orderby=ID&order=ASC").orderBy()).containsExactly(asc(OrderKey.ID));
        assertThat(build("orderby=post_name").orderBy()).containsExactly(desc(OrderKey.NAME));
        assertThat(build("orderby=modified").orderBy()).containsExactly(desc(OrderKey.MODIFIED));
        assertThat(build("orderby=author").orderBy()).containsExactly(desc(OrderKey.AUTHOR));
        assertThat(build("orderby=parent").orderBy()).containsExactly(desc(OrderKey.PARENT));
        assertThat(build("orderby=type").orderBy()).containsExactly(desc(OrderKey.TYPE));
        assertThat(build("orderby=menu_order").orderBy()).containsExactly(desc(OrderKey.MENU_ORDER));
        assertThat(build("orderby=none").orderBy()).containsExactly(desc(OrderKey.NONE));
        // relevance without a search, and a meta key without meta_key, fall back to the date
        assertThat(build("orderby=relevance").orderBy()).containsExactly(desc(OrderKey.DATE));
        assertThat(build("orderby=meta_value").orderBy()).containsExactly(desc(OrderKey.DATE));
        // a list key without its list orders by the date with no direction: ascending (main-query.out)
        assertThat(build("orderby=post__in").orderBy()).containsExactly(asc(OrderKey.DATE));
    }

    @Test
    void datesBecomeOneRange() {
        List<DateCondition> month = build("year=2026&monthnum=5").dates();
        assertThat(month).hasSize(1);
        assertThat(month.get(0).from()).contains(at("2026-05-01T00:00"));
        assertThat(month.get(0).until()).contains(at("2026-06-01T00:00"));
        List<DateCondition> partial = build("m=20265").dates();
        assertThat(partial).hasSize(1);
        assertThat(partial.get(0).from()).contains(at("2026-01-01T00:00"));
        assertThat(partial.get(0).until()).contains(at("2027-01-01T00:00"));
        List<DateCondition> time = build("m=2026052008").dates();
        assertThat(time.get(0).from()).contains(at("2026-05-20T08:00"));
        assertThat(time.get(0).until()).contains(at("2026-05-20T09:00"));
        List<DateCondition> day =
                build("year=2026&monthnum=5&day=20&hour=8&minute=0&second=5").dates();
        assertThat(day).hasSize(1);
        assertThat(day.get(0).from()).contains(at("2026-05-20T08:00:05"));
        assertThat(day.get(0).until()).contains(at("2026-05-20T08:00:06"));
        // an hour and a second without the minute: WordPress drops the time (main-query.out)
        List<DateCondition> noMinute =
                build("year=2026&monthnum=5&day=20&hour=8&second=5").dates();
        assertThat(noMinute.get(0).from()).contains(at("2026-05-20T00:00"));
        assertThat(noMinute.get(0).until()).contains(at("2026-05-21T00:00"));
        assertThat(build("hour=8&second=5").dates()).isEmpty();
        // both m and year apply
        assertThat(build("year=2026&m=2025").dates()).hasSize(2);
        assertThat(build("year=2026&monthnum=5").units()).isEmpty();
        // a week is its own condition, numbered by start_of_week
        PostQuery week = build("w=21&year=2026", startOfWeek("1"));
        assertThat(week.week()).contains(new PostQuery.WeekCondition(21, 1));
        assertThat(week.dates()).hasSize(1);
        assertThat(build("w=21", startOfWeek("0")).week()).contains(new PostQuery.WeekCondition(21, 0));
        // (int) get_option('start_of_week'): '', 'x' and a missing option are 0 (main-query.out, _wp_mysql_week)
        assertThat(build("w=21", startOfWeek("")).week()).contains(new PostQuery.WeekCondition(21, 0));
        assertThat(build("w=21", startOfWeek("x")).week()).contains(new PostQuery.WeekCondition(21, 0));
        assertThat(build("w=21").week()).contains(new PostQuery.WeekCondition(21, 0));
        assertThat(build("w=21", startOfWeek("3")).week()).contains(new PostQuery.WeekCondition(21, 3));
        // a page_id replaces every condition built before it
        PostQuery page = build("page_id=20&year=2026");
        assertThat(page.id()).contains(PostId.of(20));
        assertThat(page.dates()).isEmpty();
    }

    /** Options of a blog front page with {@code start_of_week}. */
    static SiteOptions startOfWeek(String value) {
        Map<String, String> options = new LinkedHashMap<>();
        options.put("show_on_front", "posts");
        options.put("start_of_week", value);
        return FlagParserTest.options(options);
    }

    static DateUnits units(Integer year, Integer month, Integer day, Integer hour, Integer minute, Integer second) {
        return new DateUnits(unit(year), unit(month), unit(day), unit(hour), unit(minute), unit(second));
    }

    private static OptionalInt unit(Integer value) {
        return value == null ? OptionalInt.empty() : OptionalInt.of(value);
    }

    @Test
    void unitsWithoutTheUnitsAboveAreUnitEquality() {
        // main-query.out FQ13: MONTH( post_date ) = 5, DAYOFMONTH( post_date ) = 31, HOUR( post_date ) = 8, ...
        assertThat(build("monthnum=5").units()).contains(units(null, 5, null, null, null, null));
        assertThat(build("monthnum=5").dates()).isEmpty();
        assertThat(build("day=31").units()).contains(units(null, null, 31, null, null, null));
        assertThat(build("year=2026&day=20").units()).contains(units(2026, null, 20, null, null, null));
        assertThat(build("monthnum=5&day=20").units()).contains(units(null, 5, 20, null, null, null));
        assertThat(build("monthnum=2&day=31").units()).contains(units(null, 2, 31, null, null, null));
        assertThat(build("hour=8").units()).contains(units(null, null, null, 8, null, null));
        assertThat(build("hour=0").units()).contains(units(null, null, null, 0, null, null));
        assertThat(build("hour=24").units()).contains(units(null, null, null, 24, null, null));
        assertThat(build("minute=5").units()).contains(units(null, null, null, null, 5, null));
        assertThat(build("second=5").units()).contains(units(null, null, null, null, null, 5));
        assertThat(build("second=0").units()).contains(units(null, null, null, null, null, 0));
        assertThat(build("minute=60").units()).contains(units(null, null, null, null, 60, null));
        // DATE_FORMAT( post_date, '%H.%i' ) = 08.05 and DATE_FORMAT( post_date, '0.%i%s' ) = 0.0500
        assertThat(build("hour=8&minute=5").units()).contains(units(null, null, null, 8, 5, null));
        assertThat(build("minute=5&second=0").units()).contains(units(null, null, null, null, 5, 0));
        assertThat(build("hour=8&minute=5&second=0").units()).contains(units(null, null, null, 8, 5, 0));
        assertThat(build("day=20&hour=8").units()).contains(units(null, null, 20, 8, null, null));
        assertThat(build("year=2026&hour=8").units()).contains(units(2026, null, null, 8, null, null));
        assertThat(build("monthnum=5&minute=30").units()).contains(units(null, 5, null, null, 30, null));
        assertThat(build("year=2026&monthnum=5&day=20&minute=5").units()).contains(units(2026, 5, 20, null, 5, null));
        // a day of 0 is no day; an hour of 0 is an hour
        assertThat(build("day=0").units()).isEmpty();
        assertThat(build("day=0").dates()).isEmpty();
        assertThat(build("monthnum=0&hour=0").units()).contains(units(null, null, null, 0, null, null));
        // WEEK( post_date, 1 ) = 21 AND HOUR( post_date ) = 8
        PostQuery weekAndHour = build("w=21&hour=8", startOfWeek("1"));
        assertThat(weekAndHour.week()).contains(new PostQuery.WeekCondition(21, 1));
        assertThat(weekAndHour.units()).contains(units(null, null, null, 8, null, null));
        // an hour and a second without the minute: no time at all
        assertThat(build("hour=8&second=5").units()).isEmpty();
        assertThat(build("day=20&hour=8&second=5").units()).contains(units(null, null, 20, null, null, null));
    }

    @Test
    void authors() {
        PostQuery mixed = build("author=3,-2");
        assertThat(mixed.authorNotIn()).containsExactly(UserId.of(2));
        assertThat(build("author=3,2").authorIn()).containsExactlyInAnyOrder(UserId.of(2), UserId.of(3));
        assertThat(build("author_name=carol").authorIn()).containsExactly(UserId.of(3));
        assertThat(build("author_name=x/carol").authorIn()).containsExactly(UserId.of(3));
        // nobody: WordPress asks for post_author = 0, which no post has
        PostQuery nobody = build("author_name=nobody");
        assertThat(nobody.authorIn()).isEmpty();
        assertThat(nobody.dates()).containsExactly(MainQuery.NOTHING);
    }

    @Test
    void slugsAndIds() {
        PostQuery team = build("pagename=about/team");
        assertThat(team.id()).contains(PostId.of(23));
        assertThat(team.types()).containsExactly("page");
        // a path no page has is id 0: nothing
        PostQuery missing = build("pagename=team");
        assertThat(missing.id()).isEmpty();
        assertThat(missing.dates()).containsExactly(MainQuery.NOTHING);
        PostQuery postsPage = build("pagename=blog", FlagParserTest.STATIC_FRONT);
        assertThat(postsPage.id()).isEmpty();
        assertThat(postsPage.name()).isEmpty();
        assertThat(postsPage.types()).containsExactly("post");
        assertThat(postsPage.stickyFirst()).isTrue();
        PostQuery attachment = build("attachment=photo");
        assertThat(attachment.name()).contains("photo");
        assertThat(attachment.types()).containsExactly("attachment");
        PostQuery byId = build("attachment_id=31");
        assertThat(byId.id()).contains(PostId.of(31));
        assertThat(byId.types()).containsExactly("attachment");
        PostQuery both = build("p=10&page_id=20");
        assertThat(both.id()).contains(PostId.of(20));
        assertThat(both.types()).containsExactly("post");
        PostQuery named = build("name=hello-world&p=10");
        assertThat(named.name()).contains("hello-world");
        assertThat(named.id()).contains(PostId.of(10));
        // a pagename resolving to an attachment selects the attachment type
        PostQuery logo = build("pagename=logo");
        assertThat(logo.id()).contains(PostId.of(31));
        assertThat(logo.types()).containsExactly("attachment");
        assertThat(build("post_type=page").types()).containsExactly("page");
    }

    @Test
    void pagingAndStickies() {
        assertThat(build("paged=3").page()).isEqualTo(3);
        assertThat(build("").perPage()).hasValue(10);
        assertThat(build("").stickyFirst()).isTrue();
        assertThat(build("paged=2").stickyFirst()).isFalse();
        assertThat(build("paged=1").stickyFirst()).isTrue();
        assertThat(build("cat=1").stickyFirst()).isFalse();
        assertThat(build("s=red").stickyFirst()).isFalse();
        Map<String, String> three = new LinkedHashMap<>();
        three.put("show_on_front", "posts");
        three.put("posts_per_page", "3");
        assertThat(build("", FlagParserTest.options(three)).perPage()).hasValue(3);
    }

    @Test
    void pagedBeyondIntRange() {
        assertThat(build("paged=99999999999").page()).isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void aRequestThatIsAlreadyNotFoundBuildsNoQuery() {
        FlagParser.Parsed parsed = FlagParserTest.parse("p=-1", FlagParserTest.BLOG_FRONT);
        assertThat(mainQuery().build(parsed, FlagParserTest.BLOG_FRONT, FlagParserTest.REGISTRIES, Viewer.anonymous()))
                .isEmpty();
    }
}
