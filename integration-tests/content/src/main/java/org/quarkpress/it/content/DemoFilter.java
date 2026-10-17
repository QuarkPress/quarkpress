package org.quarkpress.it.content;

import jakarta.enterprise.context.ApplicationScoped;
import org.quarkpress.core.ContentFilter;
import org.quarkpress.core.RenderContext;
import org.quarkpress.core.SiteFeature;

@ApplicationScoped
public class DemoFilter implements ContentFilter {

    @SiteFeature("demo")
    @Override
    public String apply(String html, RenderContext ctx) {
        return html + "+demo";
    }
}
