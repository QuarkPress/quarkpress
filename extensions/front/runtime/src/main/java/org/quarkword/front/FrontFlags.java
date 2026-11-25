package org.quarkword.front;

import java.util.Objects;
import java.util.Optional;
import org.quarkword.core.SiteOptions;

/**
 * The conditional flags of a front-end request, as WordPress's main query exposes them ({@code is_single},
 * {@code is_home}, {@code is_404}, ...): every flag is false until the parser sets it. {@code isFrontPage} and
 * {@code isPrivacyPolicy} are not stored but computed from the options and the queried object, as WordPress computes
 * them. Immutable; the {@code with...} methods return changed copies.
 */
public record FrontFlags(
        boolean single,
        boolean page,
        boolean attachment,
        boolean singular,
        boolean home,
        boolean postsPage,
        boolean archive,
        boolean category,
        boolean tag,
        boolean tax,
        boolean author,
        boolean date,
        boolean year,
        boolean month,
        boolean day,
        boolean time,
        boolean search,
        boolean is404,
        boolean paged,
        boolean postTypeArchive,
        boolean feed,
        boolean commentFeed,
        boolean embed,
        boolean preview,
        boolean trackback,
        boolean robots,
        boolean favicon,
        boolean sitemap,
        boolean privacyPolicy) {

    private static final FrontFlags NONE = new FrontFlags(
            false, false, false, false, false, false, false, false, false, false, false, false, false, false, false,
            false, false, false, false, false, false, false, false, false, false, false, false, false, false);

    /** Every flag false: the state before parsing. */
    public static FrontFlags none() {
        return NONE;
    }

    /** Every flag false but {@code is404}: what a request marked not found ends up with. */
    public static FrontFlags notFound() {
        return NONE.withIs404(true);
    }

    /**
     * Whether the request is for the site's front page: the posts list when the posts are shown on the front, or the
     * configured front page when the queried object is that page.
     */
    public boolean isFrontPage(SiteOptions options, Optional<QueriedObject> queried) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(queried, "queried");
        String showOnFront = options.string("show_on_front", "posts");
        if (showOnFront.equals("posts")) {
            return home;
        }
        if (!showOnFront.equals("page")) {
            return false;
        }
        long pageOnFront = options.number("page_on_front", 0);
        return pageOnFront != 0 && isPage(pageOnFront, queried);
    }

    /** Whether the request is for the configured privacy policy page: it is the queried page. */
    public boolean isPrivacyPolicy(SiteOptions options, Optional<QueriedObject> queried) {
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(queried, "queried");
        long privacyPage = options.number("wp_page_for_privacy_policy", 0);
        return privacyPage != 0 && isPage(privacyPage, queried);
    }

    private boolean isPage(long id, Optional<QueriedObject> queried) {
        return page && queried.isPresent() && queried.get().id() == id;
    }

    /** These flags with {@code single} set to {@code value}. */
    public FrontFlags withSingle(boolean value) {
        return new FrontFlags(
                value,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code page} set to {@code value}. */
    public FrontFlags withPage(boolean value) {
        return new FrontFlags(
                single,
                value,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code attachment} set to {@code value}. */
    public FrontFlags withAttachment(boolean value) {
        return new FrontFlags(
                single,
                page,
                value,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code singular} set to {@code value}. */
    public FrontFlags withSingular(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                value,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code home} set to {@code value}. */
    public FrontFlags withHome(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                value,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code postsPage} set to {@code value}. */
    public FrontFlags withPostsPage(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                value,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code archive} set to {@code value}. */
    public FrontFlags withArchive(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                value,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code category} set to {@code value}. */
    public FrontFlags withCategory(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                value,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code tag} set to {@code value}. */
    public FrontFlags withTag(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                value,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code tax} set to {@code value}. */
    public FrontFlags withTax(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                value,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code author} set to {@code value}. */
    public FrontFlags withAuthor(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                value,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code date} set to {@code value}. */
    public FrontFlags withDate(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                value,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code year} set to {@code value}. */
    public FrontFlags withYear(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                value,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code month} set to {@code value}. */
    public FrontFlags withMonth(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                value,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code day} set to {@code value}. */
    public FrontFlags withDay(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                value,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code time} set to {@code value}. */
    public FrontFlags withTime(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                value,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code search} set to {@code value}. */
    public FrontFlags withSearch(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                value,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code is404} set to {@code value}. */
    public FrontFlags withIs404(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                value,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code paged} set to {@code value}. */
    public FrontFlags withPaged(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                value,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code postTypeArchive} set to {@code value}. */
    public FrontFlags withPostTypeArchive(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                value,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code feed} set to {@code value}. */
    public FrontFlags withFeed(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                value,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code commentFeed} set to {@code value}. */
    public FrontFlags withCommentFeed(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                value,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code embed} set to {@code value}. */
    public FrontFlags withEmbed(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                value,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code preview} set to {@code value}. */
    public FrontFlags withPreview(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                value,
                trackback,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code trackback} set to {@code value}. */
    public FrontFlags withTrackback(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                value,
                robots,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code robots} set to {@code value}. */
    public FrontFlags withRobots(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                value,
                favicon,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code favicon} set to {@code value}. */
    public FrontFlags withFavicon(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                value,
                sitemap,
                privacyPolicy);
    }

    /** These flags with {@code sitemap} set to {@code value}. */
    public FrontFlags withSitemap(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                value,
                privacyPolicy);
    }

    /** These flags with {@code privacyPolicy} set to {@code value}. */
    public FrontFlags withPrivacyPolicy(boolean value) {
        return new FrontFlags(
                single,
                page,
                attachment,
                singular,
                home,
                postsPage,
                archive,
                category,
                tag,
                tax,
                author,
                date,
                year,
                month,
                day,
                time,
                search,
                is404,
                paged,
                postTypeArchive,
                feed,
                commentFeed,
                embed,
                preview,
                trackback,
                robots,
                favicon,
                sitemap,
                value);
    }
}
