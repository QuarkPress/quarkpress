package org.quarkpress.multisite.deployment;

import static org.assertj.core.api.Assertions.assertThat;

import io.quarkus.test.QuarkusExtensionTest;
import jakarta.inject.Inject;
import java.net.URI;
import java.util.Locale;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteRequest;
import org.quarkpress.core.runtime.Binding;
import org.quarkpress.multisite.runtime.HttpResolution;
import org.quarkpress.multisite.runtime.RoutedSite;

class ResolutionDbTest {

    @RegisterExtension
    static final QuarkusExtensionTest app = MultisiteApp.with(ResolutionDbTest.class, Network.class);

    static final SiteId MAIN = SiteId.of(1);

    @Inject
    HttpResolution resolution;

    @Inject
    Network network;

    private Binding resolve(String host, String path) {
        return resolution.route(new SiteRequest("http", host, -1, path)).binding();
    }

    @Test
    void theNetworkDomainBelongsToTheMainSite() {
        assertThat(resolve("localhost", "/")).isEqualTo(Binding.site(MAIN));
        assertThat(resolve("LOCALHOST", "/anything")).isEqualTo(Binding.site(MAIN));
        assertThat(resolve("www.localhost", "/")).isEqualTo(Binding.site(MAIN));
    }

    @Test
    void aPathSiteAnswersUnderItsPath() {
        String slug = Network.unique("news");
        SiteId news = network.site("localhost", "/" + slug + "/");
        assertThat(resolve("localhost", "/" + slug + "/2026/x")).isEqualTo(Binding.site(news));
        assertThat(resolve("localhost", "/" + slug)).isEqualTo(Binding.site(news));
        assertThat(resolve("localhost", "/" + slug.toUpperCase(Locale.ROOT) + "/"))
                .isEqualTo(Binding.site(news));
    }

    @Test
    void pathsThatAreNotSitesBelongToTheMainSite() {
        assertThat(resolve("localhost", "/wp-json/wp/v2/posts")).isEqualTo(Binding.site(MAIN));
        assertThat(resolve("localhost", "/api/x")).isEqualTo(Binding.site(MAIN));
        assertThat(resolve("localhost", "")).isEqualTo(Binding.site(MAIN));
    }

    @Test
    void zeroAndEmptySegmentsAreSkipped() {
        String slug = Network.unique("zero");
        SiteId site = network.site("localhost", "/" + slug + "/");
        assertThat(resolve("localhost", "/0/" + slug + "/")).isEqualTo(Binding.site(site));
        assertThat(resolve("localhost", "//" + slug + "//x")).isEqualTo(Binding.site(site));
    }

    @Test
    void onlyTheFirstSegmentCountsByDefault() {
        String slug = Network.unique("one");
        SiteId outer = network.site("localhost", "/" + slug + "/");
        network.site("localhost", "/" + slug + "/b/");
        assertThat(resolve("localhost", "/" + slug + "/b/c")).isEqualTo(Binding.site(outer));
    }

    @Test
    void aSubdomainSiteAnswersOnItsHost() {
        String host = Network.unique("s") + ".localhost";
        SiteId site = network.site(host, "/");
        assertThat(resolve(host.toUpperCase(Locale.ROOT), "/x")).isEqualTo(Binding.site(site));
        assertThat(resolve("www." + host, "/")).isEqualTo(Binding.site(site));
    }

    @Test
    void aMappedDomainAnswersWithItsPaths() {
        String domain = Network.unique("d") + ".example";
        SiteId root = network.site(domain, "/");
        SiteId shop = network.site(domain, "/shop/");
        assertThat(resolve(domain, "/")).isEqualTo(Binding.site(root));
        assertThat(resolve(domain, "/shop/item")).isEqualTo(Binding.site(shop));
        assertThat(resolve("www." + domain, "/shop/")).isEqualTo(Binding.site(shop));
    }

    @Test
    void aWwwSiteWinsOverTheBareDomainForWwwRequests() {
        String domain = Network.unique("v") + ".example";
        SiteId bare = network.site(domain, "/");
        SiteId www = network.site("www." + domain, "/");
        assertThat(resolve("www." + domain, "/")).isEqualTo(Binding.site(www));
        assertThat(resolve(domain, "/")).isEqualTo(Binding.site(bare));
    }

    @Test
    void anAliasAnswersAndMayAskForARedirect() {
        String domain = Network.unique("p") + ".example";
        SiteId primary = network.site(domain, "/blog/");
        String redirecting = Network.unique("a") + ".example";
        network.alias(primary, redirecting, true);
        RoutedSite routed = resolution.route(new SiteRequest("https", redirecting, 443, "/x"));
        assertThat(routed.binding()).isEqualTo(Binding.site(primary));
        assertThat(routed.redirect()).contains(URI.create("https://" + domain + "/blog/"));
        String quiet = Network.unique("q") + ".example";
        network.alias(primary, quiet, false);
        RoutedSite stays = resolution.route(new SiteRequest("http", quiet, -1, "/"));
        assertThat(stays.binding()).isEqualTo(Binding.site(primary));
        assertThat(stays.redirect()).isEmpty();
        assertThat(resolve("www." + quiet, "/")).isEqualTo(Binding.site(primary));
    }

    @Test
    void anUnknownHostResolvesToNoSite() {
        assertThat(resolve(Network.unique("nobody") + ".example", "/")).isEqualTo(Binding.UNRESOLVED);
        assertThat(resolve("", "/")).isEqualTo(Binding.UNRESOLVED);
        assertThat(resolve("localhost.", "/")).isEqualTo(Binding.UNRESOLVED);
    }

    @Test
    void workWithoutARequestResolvesToNoSite() {
        assertThat(resolution.route(SiteRequest.none()).binding()).isEqualTo(Binding.UNRESOLVED);
        assertThat(resolution.route(SiteRequest.none()).redirect()).isEmpty();
    }

    @Test
    void flagsDoNotAffectResolution() {
        String host = Network.unique("archived") + ".example";
        SiteId site = network.site(host, "/");
        network.archive(site);
        assertThat(resolve(host, "/")).isEqualTo(Binding.site(site));
    }

    @Test
    void adminPathsAndBackslashesArePreparedLikeWordPress() {
        String slug = Network.unique("shop");
        SiteId shop = network.site("localhost", "/" + slug + "/");
        assertThat(resolve("localhost", "/" + slug + "/wp-admin/options.php")).isEqualTo(Binding.site(shop));
        String split = slug.substring(0, 2) + (char) 0x5C + slug.substring(2);
        assertThat(resolve("localhost", "/" + split + "/x")).isEqualTo(Binding.site(shop));
    }

    @Test
    void dotSegmentsResolveToNoSite() {
        String slug = Network.unique("dots");
        SiteId site = network.site("localhost", "/" + slug + "/");
        assertThat(resolve("localhost", "/" + slug + "/../y")).isEqualTo(Binding.UNRESOLVED);
        assertThat(resolve("localhost", "/" + slug + "/%2E%2E/y")).isEqualTo(Binding.UNRESOLVED);
        assertThat(resolve("localhost", "/" + slug + "/.hidden/")).isEqualTo(Binding.site(site));
    }

    @Test
    void dotSegmentsAfterAnAdminPathResolveToNoSite() {
        String slug = Network.unique("adm");
        SiteId site = network.site("localhost", "/" + slug + "/");
        assertThat(resolve("localhost", "/" + slug + "/wp-admin/../../b/x")).isEqualTo(Binding.UNRESOLVED);
        assertThat(resolve("localhost", "/" + slug + "/wp-admin/%2e%2E/b")).isEqualTo(Binding.UNRESOLVED);
        assertThat(resolve("localhost", "/" + slug + "/wp-admin/x?/../")).isEqualTo(Binding.site(site));
    }

    @Test
    void aLongerDomainBeatsALongerPath() {
        String domain = Network.unique("w") + ".example";
        network.site(domain, "/shop/");
        SiteId www = network.site("www." + domain, "/");
        assertThat(resolve("www." + domain, "/shop/")).isEqualTo(Binding.site(www));
    }

    @Test
    void aRedirectingAliasIsFoundThroughItsWwwFormAndKeepsTheRequestScheme() {
        String domain = Network.unique("r") + ".example";
        SiteId primary = network.site(domain, "/blog/");
        String alias = Network.unique("ra") + ".example";
        network.alias(primary, alias, true);
        RoutedSite viaWww = resolution.route(new SiteRequest("https", "www." + alias, -1, "/"));
        assertThat(viaWww.binding()).isEqualTo(Binding.site(primary));
        assertThat(viaWww.redirect()).contains(URI.create("https://" + domain + "/blog/"));
        RoutedSite plain = resolution.route(new SiteRequest("http", alias, -1, "/"));
        assertThat(plain.redirect()).contains(URI.create("http://" + domain + "/blog/"));
        RoutedSite odd = resolution.route(new SiteRequest("gopher", alias, -1, "/"));
        assertThat(odd.redirect()).contains(URI.create("http://" + domain + "/blog/"));
        RoutedSite none = resolution.route(new SiteRequest("", alias, -1, "/"));
        assertThat(none.redirect()).contains(URI.create("http://" + domain + "/blog/"));
    }

    @Test
    void aPortAndBackslashesInTheHostAreIgnored() {
        String host = Network.unique("port") + ".localhost";
        SiteId site = network.site(host, "/");
        assertThat(resolve("localhost:8080", "/")).isEqualTo(Binding.site(MAIN));
        assertThat(resolve(host + ":8443", "/x")).isEqualTo(Binding.site(site));
        assertThat(resolve("www." + host + ":80", "/")).isEqualTo(Binding.site(site));
        assertThat(resolve("local" + (char) 0x5C + "host", "/")).isEqualTo(Binding.site(MAIN));
        assertThat(resolve(host.substring(0, 3) + (char) 0x5C + host.substring(3), "/"))
                .isEqualTo(Binding.site(site));
        assertThat(resolve(":8080", "/")).isEqualTo(Binding.UNRESOLVED);
    }

    @Test
    void charactersNoSiteCanHaveResolveWithoutErrors() {
        String accented = new String(Character.toChars(0xE9));
        assertThat(resolve("localhost", "/" + accented + "/x")).isEqualTo(Binding.site(MAIN));
        assertThat(resolve("localhost", "/a" + (char) 0x5C + "0b/x")).isEqualTo(Binding.site(MAIN));
        assertThat(resolve("loc" + (char) 0 + "alhost", "/")).isEqualTo(Binding.UNRESOLVED);
        assertThat(resolve("loc" + accented + "alhost", "/")).isEqualTo(Binding.UNRESOLVED);
    }
}
