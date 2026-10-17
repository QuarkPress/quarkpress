package org.quarkpress.core.deployment.gate;

import jakarta.enterprise.event.Observes;
import org.quarkpress.core.ContentFilter;
import org.quarkpress.core.RenderContext;
import org.quarkpress.core.SiteFeature;

/** Classes the classifier test indexes. They are not beans. */
final class GateSamples {

    private GateSamples() {}

    record Ping(String text) {}

    static class Observer {
        @SiteFeature("demo")
        void on(@Observes Ping ping) {}
    }

    static class Filter implements ContentFilter {
        @SiteFeature("demo")
        @Override
        public String apply(String html, RenderContext ctx) {
            return html;
        }

        public String unrelated(String text, RenderContext ctx) {
            return text;
        }
    }

    static class LooseFilter implements ContentFilter {
        @SiteFeature("demo")
        @Override
        public String apply(String html, RenderContext ctx) {
            return html;
        }

        @SiteFeature("demo")
        public String apply(Object html, RenderContext ctx) {
            return String.valueOf(html);
        }
    }

    static class Base {
        public String inherited() {
            return "inherited";
        }

        public String overridden() {
            return "base";
        }

        protected String internal() {
            return "internal";
        }

        void onBase(@Observes Ping ping) {}
    }

    interface Defaults {
        default String fromDefault() {
            return "default";
        }

        String required();
    }

    @SiteFeature("demo")
    static class Service extends Base implements Defaults {
        @Override
        public String required() {
            return "required";
        }

        public String hello() {
            return "hello";
        }

        @SiteFeature("other")
        public String other() {
            return "other";
        }

        private String hidden() {
            return "hidden";
        }

        static String utility() {
            return "utility";
        }

        @Override
        public String overridden() {
            return "service";
        }

        @Override
        public boolean equals(Object other) {
            return other == this;
        }

        @Override
        public int hashCode() {
            return 1;
        }

        @Override
        public String toString() {
            return "service";
        }
    }

    static class ResourceBase {
        @jakarta.ws.rs.GET
        public String inheritedEndpoint() {
            return "inherited";
        }
    }

    @jakarta.ws.rs.Path("/samples")
    @SiteFeature("demo")
    static class Resource extends ResourceBase {
        @jakarta.ws.rs.GET
        public String get() {
            return "open";
        }
    }

    @jakarta.ws.rs.Path("/mixed")
    static class MixedResource {
        @SiteFeature("demo")
        @jakarta.ws.rs.GET
        public String gated() {
            return "gated";
        }
    }

    static class Signatures {
        void mixed(String text, int count, String[] names, long[][] grid) {}
    }
}
