package org.quarkpress.posts.runtime;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteOptions;
import org.quarkpress.persistence.Options;
import org.quarkpress.posts.PostSnapshot;

/**
 * The reading settings of the current site that name a page: when that page is trashed or deleted, a front page
 * setting falls back to the latest posts and a posts page setting to none; deleting also clears the privacy policy
 * page setting. The options are written after the post rows, as the lock order requires.
 */
@ApplicationScoped
public class FrontPages {

    @Inject
    SiteContext site;

    @Inject
    Options options;

    /** Forgets {@code page} in the settings that name it; a post of another type changes nothing. */
    void forget(PostSnapshot page, boolean deleting) {
        if (!page.type().equals("page")) {
            return;
        }
        long id = page.id().value();
        SiteOptions current = site.options();
        if (phpInt(current, "page_on_front") == id) {
            options.set("show_on_front", TextNode.valueOf("posts"));
            options.set("page_on_front", IntNode.valueOf(0));
        }
        if (phpInt(current, "page_for_posts") == id) {
            options.set("page_for_posts", IntNode.valueOf(0));
        }
        if (deleting && phpInt(current, "wp_page_for_privacy_policy") == id) {
            options.set("wp_page_for_privacy_policy", IntNode.valueOf(0));
        }
    }

    /** The option cast to an integer as WordPress's {@code (int) get_option(...)} does; 0 when it is missing. */
    private static long phpInt(SiteOptions options, String name) {
        return options.get(name).map(PhpInts::of).orElse(0L);
    }
}
