package org.quarkpress.blocks.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import java.util.List;
import java.util.Optional;
import org.quarkpress.blocks.BlockPatternCategory;
import org.quarkpress.blocks.BlockPatternCategoryProvider;

/**
 * WordPress's core block pattern categories, by slug and label in WordPress's order, registered before every other
 * provider's. The descriptions are QuarkPress's own.
 */
@ApplicationScoped
public class CoreBlockPatternCategories implements BlockPatternCategoryProvider {

    private static final List<BlockPatternCategory> CATEGORIES = List.of(
            category("banner", "Banners", "Large sections that open a page or carry one message."),
            category("buttons", "Buttons", "Buttons and calls to action."),
            category("columns", "Columns", "Layouts in several columns."),
            category("text", "Text", "Mostly text."),
            category("query", "Posts", "Lists and grids of posts."),
            category("featured", "Featured", "Patterns chosen to stand out."),
            category("call-to-action", "Call to action", "Sections that ask the reader to act."),
            category("team", "Team", "People and their roles."),
            category("testimonials", "Testimonials", "Quotes from customers and readers."),
            category("services", "Services", "What a business offers."),
            category("contact", "Contact", "Ways to get in touch."),
            category("about", "About", "An introduction to the site or its people."),
            category("portfolio", "Portfolio", "Work to show."),
            category("gallery", "Gallery", "Images in groups."),
            category("media", "Media", "Audio and video layouts."),
            category("videos", "Videos", "Video layouts."),
            category("audio", "Audio", "Audio layouts."),
            category("posts", "Posts", "Lists and grids of posts."),
            category("footer", "Footers", "The bottom of a page: links and site details."),
            category("header", "Headers", "The top of a page: site title and navigation."),
            category("navigation", "Navigation", "Menus and site navigation."));

    private static BlockPatternCategory category(String name, String label, String description) {
        return new BlockPatternCategory(name, Optional.of(label), Optional.of(description));
    }

    @Override
    public List<BlockPatternCategory> categories() {
        return CATEGORIES;
    }

    @Override
    public int order() {
        return 0;
    }
}
