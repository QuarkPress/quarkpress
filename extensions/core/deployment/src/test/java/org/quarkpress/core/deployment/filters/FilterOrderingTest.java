package org.quarkpress.core.deployment.filters;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.arc.All;
import io.quarkus.arc.InstanceHandle;
import io.quarkus.test.QuarkusExtensionTest;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.ContentFilter;
import org.quarkpress.core.Filters;
import org.quarkpress.core.RenderContext;
import org.quarkpress.core.SiteId;

/** Pins the ordering convention: higher @Priority runs first, no @Priority counts as 0, ties by class name. */
class FilterOrderingTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = new QuarkusExtensionTest()
            .withApplicationRoot(jar -> jar.addClasses(
                    First.class, Second.class, Last.class, Zulu.class, Alpha.class, FilterOrderingTest.class));

    @ApplicationScoped
    @Priority(200)
    public static class First implements ContentFilter {
        @Override
        public String apply(String html, RenderContext ctx) {
            return html + "a";
        }
    }

    @ApplicationScoped
    @Priority(100)
    public static class Second implements ContentFilter {
        @Override
        public String apply(String html, RenderContext ctx) {
            return html + "b";
        }
    }

    @ApplicationScoped
    public static class Last implements ContentFilter {
        @Override
        public String apply(String html, RenderContext ctx) {
            return html + "c";
        }
    }

    @ApplicationScoped
    @Priority(50)
    public static class Zulu implements ContentFilter {
        @Override
        public String apply(String html, RenderContext ctx) {
            return html + "z";
        }
    }

    @ApplicationScoped
    @Priority(50)
    public static class Alpha implements ContentFilter {
        @Override
        public String apply(String html, RenderContext ctx) {
            return html + "y";
        }
    }

    @Inject
    @All
    List<InstanceHandle<ContentFilter>> filters;

    @Test
    void higherPriorityRunsFirst() {
        RenderContext ctx = () -> SiteId.of(1);
        assertThat(Filters.apply(Filters.ordered(filters), "x", (f, v) -> f.apply(v, ctx)))
                .isEqualTo("xabyzc");
    }
}
