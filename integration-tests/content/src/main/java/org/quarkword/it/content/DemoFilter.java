package org.quarkword.it.content;

import jakarta.enterprise.context.ApplicationScoped;
import org.quarkword.core.ContentFilter;
import org.quarkword.core.RenderContext;
import org.quarkword.core.SiteFeature;

@ApplicationScoped
public class DemoFilter implements ContentFilter {

    @SiteFeature("demo")
    @Override
    public String apply(String html, RenderContext ctx) {
        return html + "+demo";
    }
}
