package org.quarkpress.posts.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.agroal.api.AgroalDataSource;
import io.quarkus.agroal.DataSource;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.PostId;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.UserId;
import org.quarkpress.posts.OrderKey;
import org.quarkpress.posts.PostChanges;
import org.quarkpress.posts.PostPage;
import org.quarkpress.posts.PostQuery;
import org.quarkpress.posts.PostSnapshot;
import org.quarkpress.posts.Posts;

class SearchDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = PostsApp.with(SearchDbTest.class);

    @Inject
    @DataSource("quarkpress")
    AgroalDataSource dataSource;

    @Inject
    Posts posts;

    SiteId site;
    int day;

    @BeforeEach
    void setUp() {
        site = TestData.site(dataSource);
        day = 0;
    }

    /** A published post, each newer than the one before. */
    private PostSnapshot post(String title, String excerpt, String content) throws Exception {
        return postOn(site, title, excerpt, content);
    }

    private PostSnapshot postOn(SiteId on, String title, String excerpt, String content) throws Exception {
        PostChanges changes = PostChanges.builder()
                .title(title)
                .excerpt(excerpt)
                .content(content)
                .status("publish")
                .dateGmt(Instant.parse("2020-01-01T00:00:00Z").plusSeconds(86_400L * ++day))
                .build();
        return Identities.onSite(on, Identities.system(), () -> posts.create(changes));
    }

    private List<PostId> search(SecurityIdentity identity, PostQuery.Builder query) throws Exception {
        return Identities.onSite(site, identity, () -> posts.query(query.build())).posts().stream()
                .map(PostSnapshot::id)
                .toList();
    }

    private List<PostId> search(String text) throws Exception {
        return search(Identities.anonymous(), PostQuery.builder().search(text));
    }

    private static String cp(int... codePoints) {
        return new String(codePoints, 0, codePoints.length);
    }

    @Test
    void everyTermMustAppearInOneOfTheThreeFields() throws Exception {
        PostSnapshot both = post("Hello world", "", "");
        PostSnapshot split = post("Hello", "", "the whole world");
        PostSnapshot excerpt = post("Greetings", "hello world", "");
        post("Hello", "", "nothing else");
        assertThat(search("hello world")).containsExactly(both.id(), split.id(), excerpt.id());
    }

    @Test
    void excludedTermsAndPhrasesFollowWordPress() throws Exception {
        PostSnapshot keep = post("Hello there", "", "");
        post("Hello draft", "", "");
        PostSnapshot phrase = post("Say hello world", "", "");
        PostSnapshot big = post("hello big world", "", "");
        post("Hello", "a draft", "");
        post("Hello", "", "draft text");
        assertThat(search("hello -draft")).containsExactly(big.id(), phrase.id(), keep.id());
        assertThat(search("\"hello world\"")).containsExactly(phrase.id());
        assertThat(search("\"-draft\" hello")).containsExactly(big.id(), phrase.id(), keep.id());
    }

    @Test
    void aSentenceSearchIsOneTerm() throws Exception {
        post("Hello there world", "", "");
        PostSnapshot phrase = post("Say hello world", "", "");
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().search("hello world").sentence(true)))
                .containsExactly(phrase.id());
    }

    @Test
    void matchingIgnoresCaseButNotAccentsAndTreatsWildcardsLiterally() throws Exception {
        PostSnapshot hello = post("hello", "", "");
        PostSnapshot sure = post("100% sure", "", "");
        post("1000 things", "", "");
        PostSnapshot cafe = post("Caf" + cp(0xE9), "", "");
        PostSnapshot under = post("a_b", "", "");
        post("axb", "", "");
        PostSnapshot bang = post("wow!x", "", "");
        post("wowx", "", "");
        assertThat(search("HELLO")).containsExactly(hello.id());
        assertThat(search("100%")).containsExactly(sure.id());
        assertThat(search("a_b")).containsExactly(under.id());
        assertThat(search("wow!x")).containsExactly(bang.id());
        assertThat(search("cafe")).isEmpty();
        assertThat(search("caf" + cp(0xE9))).containsExactly(cafe.id());
        assertThat(search("CAF" + cp(0xC9))).containsExactly(cafe.id());
    }

    @Test
    void stopwordsAreIgnored() throws Exception {
        PostSnapshot cat = post("A cat", "", "");
        assertThat(search("the cat")).containsExactly(cat.id());
    }

    @Test
    void protectedPostsAreHiddenFromLoggedOutSearchers() throws Exception {
        PostSnapshot locked = Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title("Secret recipe")
                        .password("pw")
                        .status("publish")
                        .build()));
        assertThat(search("recipe")).isEmpty();
        assertThat(search(Identities.system(), PostQuery.builder().search("recipe")))
                .isEmpty();
        UserId subscriber = TestData.memberOfMain(dataSource, "subscriber");
        assertThat(search(Identities.user(subscriber), PostQuery.builder().search("recipe")))
                .containsExactly(locked.id());
        assertThat(search(Identities.anonymous(), PostQuery.builder())).containsExactly(locked.id());
    }

    @Test
    void aSearchCoversEverySearchableType() throws Exception {
        PostSnapshot page = Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title("Findable page")
                        .type("page")
                        .status("publish")
                        .build()));
        PostSnapshot post = post("Findable post", "", "");
        assertThat(search("findable")).containsExactly(page.id(), post.id());
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().search("findable").types("post")))
                .containsExactly(post.id());
    }

    @Test
    void aSearchStaysOnItsSite() throws Exception {
        SiteId other = TestData.site(dataSource);
        postOn(other, "Local news", "", "");
        PostSnapshot here = post("Local news", "", "");
        assertThat(search("local")).containsExactly(here.id());
    }

    @Test
    void resultsAreRankedAsWordPressRanksThem() throws Exception {
        PostSnapshot whole = post("Red car for sale", "", "a red car");
        PostSnapshot all = post("Car painted red", "", "a red car");
        PostSnapshot any = post("Red bike", "", "next to a car");
        PostSnapshot content = post("Bikes", "", "a red car");
        PostSnapshot excerpt = post("Wheels", "a red car", "red car");
        assertThat(search("red car")).containsExactly(whole.id(), all.id(), any.id(), excerpt.id(), content.id());
        assertThat(search("red")).containsExactly(any.id(), all.id(), whole.id(), excerpt.id(), content.id());
        assertThat(search("red -blue")).containsExactly(any.id(), all.id(), whole.id(), excerpt.id(), content.id());
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().search("red car").orderBy(OrderKey.TITLE, true)))
                .containsExactly(content.id(), all.id(), any.id(), whole.id(), excerpt.id());
    }

    @Test
    void aLeadingRelevanceKeyRanksAndDropsTheKeysAfterIt() throws Exception {
        PostSnapshot whole = post("Red car for sale", "", "a red car");
        PostSnapshot all = post("Car painted red", "", "a red car");
        PostSnapshot any = post("Red bike", "", "next to a car");
        PostSnapshot content = post("Bikes", "", "a red car");
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder()
                                .search("red car")
                                .orderBy(OrderKey.RELEVANCE, false)
                                .orderBy(OrderKey.TITLE, true)))
                .containsExactly(whole.id(), all.id(), any.id(), content.id());
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().search("red").orderBy(OrderKey.RELEVANCE, true)))
                .containsExactly(whole.id(), all.id(), any.id(), content.id());
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder()
                                .search("red car")
                                .orderBy(OrderKey.TITLE, true)
                                .orderBy(OrderKey.RELEVANCE, false)))
                .containsExactly(content.id(), all.id(), any.id(), whole.id());
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().orderBy(OrderKey.RELEVANCE, false).orderBy(OrderKey.TITLE, true)))
                .containsExactly(content.id(), all.id(), any.id(), whole.id());
    }

    @Test
    void manyTermsWithAnExclusionRankByDateAlone() throws Exception {
        String words = "alpha bravo charlie delta echo foxtrot golf";
        PostSnapshot inTitle = post(words, "", "");
        PostSnapshot inContent = post("Words", "", words);
        assertThat(search(words)).containsExactly(inTitle.id(), inContent.id());
        assertThat(search(words + " -hotel")).containsExactly(inContent.id(), inTitle.id());
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().search(words + " -hotel").orderBy(OrderKey.RELEVANCE, true)))
                .containsExactly(inTitle.id(), inContent.id());
    }

    @Test
    void aSearchForZeroIsNotRanked() throws Exception {
        PostSnapshot title = post("Room 101", "", "");
        PostSnapshot content = post("Items", "", "10 items");
        assertThat(search("0")).containsExactly(content.id(), title.id());
        assertThat(search("0\n")).containsExactly(content.id(), title.id());
        assertThat(search("\r0")).containsExactly(content.id(), title.id());
    }

    @Test
    void anOverlongSearchIsIgnored() throws Exception {
        PostSnapshot post = post("Anything", "", "");
        assertThat(search("x".repeat(1601))).containsExactly(post.id());
        assertThat(search("x".repeat(1600))).isEmpty();
        assertThat(search(cp(0xE9).repeat(801))).containsExactly(post.id());
        assertThat(search(cp(0xE9).repeat(800))).isEmpty();
    }

    private PostSnapshot findablePage() throws Exception {
        return Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title("Findable page")
                        .type("page")
                        .status("publish")
                        .build()));
    }

    @Test
    void anEmptyOrOverlongSearchStillCoversEverySearchableType() throws Exception {
        PostSnapshot page = findablePage();
        PostSnapshot post = post("Findable post", "", "");
        assertThat(search("")).containsExactly(page.id(), post.id());
        assertThat(search("x".repeat(1601))).containsExactly(page.id(), post.id());
    }

    @Test
    void aSearchForOnePostCoversPostsOnly() throws Exception {
        PostSnapshot page = findablePage();
        PostSnapshot post = post("Findable post", "", "");
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().id(page.id()).search("findable")))
                .isEmpty();
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().id(post.id()).search("findable")))
                .containsExactly(post.id());
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().name(page.slug()).search("findable")))
                .isEmpty();
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().name(post.slug()).search("findable")))
                .containsExactly(post.id());
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().id(post.id()).search("missing")))
                .isEmpty();
    }

    @Test
    void aSearchOfExclusionsAloneFiltersAndOrdersByDate() throws Exception {
        PostSnapshot first = post("Clean one", "", "");
        post("Has draft", "", "");
        PostSnapshot spam = post("Has spam", "", "");
        PostSnapshot second = post("Clean two", "", "");
        Identities.onSite(
                site,
                Identities.system(),
                () -> posts.create(PostChanges.builder()
                        .title("Locked")
                        .password("pw")
                        .status("publish")
                        .dateGmt(Instant.parse("2020-02-01T00:00:00Z"))
                        .build()));
        assertThat(search("-draft")).containsExactly(second.id(), spam.id(), first.id());
        assertThat(search("-draft -spam")).containsExactly(second.id(), first.id());
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().search("-draft").orderBy(OrderKey.RELEVANCE, true)))
                .containsExactly(first.id(), spam.id(), second.id());
    }

    @Test
    void aSentenceSearchRanksTitleMatchesFirst() throws Exception {
        PostSnapshot title = post("Red car", "", "");
        PostSnapshot content = post("Vehicles", "", "a red car");
        PostSnapshot newer = post("A red car here", "", "");
        post("Car, red", "", "");
        assertThat(search(
                        Identities.anonymous(),
                        PostQuery.builder().search("red car").sentence(true)))
                .containsExactly(newer.id(), title.id(), content.id());
    }

    @Test
    void aBackslashMatchesItself() throws Exception {
        PostSnapshot path = post("C:\\path", "", "");
        post("C:path", "", "");
        post("C:\\\\path", "", "");
        assertThat(search("c:\\path")).containsExactly(path.id());
    }

    @Test
    void aPagedSearchCountsEveryMatch() throws Exception {
        PostSnapshot first = post("Red one", "", "");
        PostSnapshot second = post("Red two", "", "");
        post("Blue", "", "");
        PostSnapshot third = post("Plain", "", "red");
        PostPage page = Identities.onSite(
                site,
                Identities.anonymous(),
                () -> posts.query(PostQuery.builder().search("red").perPage(2).build()));
        assertThat(page.posts().stream().map(PostSnapshot::id).toList()).containsExactly(second.id(), first.id());
        assertThat(page.found()).isEqualTo(3);
        assertThat(page.maxPages()).isEqualTo(2);
        PostPage last = Identities.onSite(
                site,
                Identities.anonymous(),
                () -> posts.query(
                        PostQuery.builder().search("red").perPage(2).page(2).build()));
        assertThat(last.posts().stream().map(PostSnapshot::id).toList()).containsExactly(third.id());
    }
}
