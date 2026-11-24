package org.quarkword.it.content;

import jakarta.enterprise.context.ApplicationScoped;
import org.quarkword.core.SiteFeature;

@ApplicationScoped
@SiteFeature("demo")
public class DemoService {

    public String hello() {
        return "hello";
    }
}
