package org.quarkword.posts;

import java.util.Optional;
import java.util.OptionalInt;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;

/**
 * Front-end links of the current site, as WordPress's link template builds them. The posts module's default bean
 * writes plain links ({@code home/?p=7}) from the site's {@code home} and {@code siteurl} options; the front module
 * replaces it with one that follows the site's permalink structure. Every link is absolute and none ends with a
 * trailing slash unless the structure asks for one.
 */
public interface LinkBuilder {

    /** The home URL without trailing slash ({@code home_url()}). */
    String home();

    /** The application root without trailing slash ({@code site_url()}). */
    String siteUrl();

    /** {@code admin_url(path)}: the admin root followed by {@code path} without its leading slashes. */
    String admin(String path);

    /** The REST URL of {@code route} ({@code rest_url(route)}); an empty route or {@code /} is the index. */
    String rest(String route);

    /**
     * The permalink of {@code post} ({@code get_permalink}, {@code get_page_link}, {@code get_attachment_link} or
     * {@code get_post_permalink} by its type), plain when the post's status asks for a plain link.
     */
    String post(PostSnapshot post);

    /**
     * The sample permalink of {@code post} (the {@code leavename} form): {@code %postname%} or {@code %pagename%}
     * stays literal in a pretty link, for the editor's permalink template.
     */
    String postSample(PostSnapshot post);

    /** The link of the term {@code term} with {@code slug} in {@code taxonomy} ({@code get_term_link}). */
    String term(String taxonomy, TermId term, String slug);

    /** The archive of {@code author}'s posts ({@code get_author_posts_url}). */
    String author(UserId author);

    /** The archive of post type {@code type} ({@code get_post_type_archive_link}); empty when it has none. */
    Optional<String> postTypeArchive(String type);

    /** The archive of post format {@code format} ({@code get_post_format_link}): {@code aside}, {@code gallery}… */
    String postFormat(String format);

    /** The year, month or day archive ({@code get_year_link}, {@code get_month_link}, {@code get_day_link}). */
    String date(int year, OptionalInt month, OptionalInt day);

    /** The search page for {@code query} ({@code get_search_link}). */
    String search(String query);
}
