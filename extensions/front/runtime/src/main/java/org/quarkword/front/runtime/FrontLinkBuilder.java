package org.quarkword.front.runtime;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Alternative;
import jakarta.inject.Inject;
import java.util.Optional;
import java.util.OptionalInt;
import org.quarkword.core.TermId;
import org.quarkword.core.UserId;
import org.quarkword.front.FrontUrls;
import org.quarkword.posts.LinkBuilder;
import org.quarkword.posts.PostSnapshot;
import org.quarkword.security.UserProfiles;
import org.quarkword.taxonomy.Taxonomies;
import org.quarkword.taxonomy.Terms;

/**
 * The posts module's links when the front module is present: the permalink structure's links in place of the plain
 * default. A term that no longer exists and an author without profile get the links WordPress gives them (the plain
 * term form, an empty author segment).
 */
@Alternative
@Priority(1)
@ApplicationScoped
public class FrontLinkBuilder implements LinkBuilder {

    @Inject
    FrontUrls urls;

    @Inject
    DefaultPermalinks permalinks;

    @Inject
    Terms terms;

    @Inject
    Taxonomies taxonomies;

    @Inject
    UserProfiles profiles;

    @Override
    public String home() {
        return urls.home();
    }

    @Override
    public String siteUrl() {
        return urls.siteUrl();
    }

    @Override
    public String admin(String path) {
        return urls.admin(path);
    }

    @Override
    public String rest(String route) {
        return urls.rest(route);
    }

    @Override
    public String post(PostSnapshot post) {
        return permalinks.post(post);
    }

    @Override
    public String postSample(PostSnapshot post) {
        return permalinks.postSample(post);
    }

    @Override
    public String term(String taxonomy, TermId term, String slug) {
        if (taxonomies.registered(taxonomy)) {
            Optional<String> link = terms.find(term, taxonomy).map(found -> permalinks.term(taxonomy, found));
            if (link.isPresent()) {
                return link.get();
            }
        }
        return permalinks.plainTerm(taxonomy, term.value(), slug);
    }

    @Override
    public String author(UserId author) {
        return permalinks.author(
                author.value(),
                profiles.find(author).map(profile -> profile.nicename()).orElse(""));
    }

    @Override
    public Optional<String> postTypeArchive(String type) {
        return permalinks.postTypeArchive(type);
    }

    @Override
    public String postFormat(String format) {
        return permalinks.postFormat(format);
    }

    @Override
    public String date(int year, OptionalInt month, OptionalInt day) {
        if (month.isEmpty()) {
            return permalinks.year(year);
        }
        return day.isEmpty()
                ? permalinks.month(year, month.getAsInt())
                : permalinks.day(year, month.getAsInt(), day.getAsInt());
    }

    @Override
    public String search(String query) {
        return permalinks.search(query);
    }
}
