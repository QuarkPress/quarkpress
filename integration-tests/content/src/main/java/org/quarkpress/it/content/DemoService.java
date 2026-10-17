package org.quarkpress.it.content;

import jakarta.enterprise.context.ApplicationScoped;
import org.quarkpress.core.SiteFeature;

@ApplicationScoped
@SiteFeature("demo")
public class DemoService {

    public String hello() {
        return "hello";
    }
}
