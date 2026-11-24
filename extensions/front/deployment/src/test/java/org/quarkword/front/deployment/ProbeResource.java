package org.quarkword.front.deployment;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.quarkword.front.FrontUrls;
import org.quarkword.front.runtime.FrontConfig;

/** The current site's URLs as {@link FrontUrls} builds them for the request, {@code |}-separated. */
@Path("/probe")
@Produces(MediaType.TEXT_PLAIN)
public class ProbeResource {

    @Inject
    FrontUrls urls;

    @Inject
    FrontConfig config;

    /** {@code home()|home("/")|home("?p=1")|siteUrl()|admin("")|login()|rest("/wp/v2/posts")|homePath()}. */
    @GET
    public String urls() {
        return String.join(
                "|",
                urls.home(),
                urls.home("/"),
                urls.home("?p=1"),
                urls.siteUrl(),
                urls.admin(""),
                urls.login(),
                urls.rest("/wp/v2/posts"),
                urls.homePath());
    }

    /** {@code rest("/")|rest("")|forceSslAdmin()|isSsl()|siteUrl("/")|admin("/post.php")|home("")|home("//x")}. */
    @GET
    @Path("/more")
    public String more() {
        return String.join(
                "|",
                urls.rest("/"),
                urls.rest(""),
                Boolean.toString(urls.forceSslAdmin()),
                Boolean.toString(urls.isSsl()),
                urls.siteUrl("/"),
                urls.admin("/post.php"),
                urls.home(""),
                urls.home("//x"));
    }

    /** The {@code cache-control} setting, or {@code -}. */
    @GET
    @Path("/cache-control")
    public String cacheControl() {
        return config.cacheControl().orElse("-");
    }
}
