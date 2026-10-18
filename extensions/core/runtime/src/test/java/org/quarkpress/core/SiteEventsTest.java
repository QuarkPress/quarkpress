package org.quarkpress.core;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class SiteEventsTest {

    private static final Instant AT = Instant.parse("2026-10-17T12:00:00Z");

    private static SiteInfo site(long id, String domain, String path, boolean archived) {
        return new SiteInfo(SiteId.of(id), domain, path, "Site " + id, 1, archived, false, false, false);
    }

    @Test
    void eventsCarryTheirSite() {
        SiteInfo before = site(4, "a.example", "/", false);
        SiteInfo after = site(4, "a.example", "/", true);
        assertThat(new SiteCreated(SiteId.of(4), AT, before).created()).isEqualTo(before);
        assertThat(new SiteUpdated(SiteId.of(4), AT, before, after).after()).isEqualTo(after);
        assertThat(new SiteFlagsChanged(SiteId.of(4), AT, before, after).before())
                .isEqualTo(before);
        assertThat(new SiteDeleted(SiteId.of(4), AT, before).deleted()).isEqualTo(before);
        assertThat(new SiteDeleted(SiteId.of(4), AT, before).site()).isEqualTo(SiteId.of(4));
    }

    @Test
    void anEventRefusesARecordOfAnotherSite() {
        SiteInfo other = site(5, "b.example", "/", false);
        assertThatThrownBy(() -> new SiteCreated(SiteId.of(4), AT, other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an event about site 4 carries the record of site 5");
        assertThatThrownBy(() -> new SiteUpdated(SiteId.of(5), AT, site(4, "a.example", "/", false), other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an event about site 5 carries the record of site 4");
        assertThatThrownBy(() -> new SiteFlagsChanged(SiteId.of(4), AT, site(4, "a.example", "/", false), other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an event about site 4 carries the record of site 5");
    }

    @Test
    void everyRecordOfAnEventMustBelongToItsSite() {
        SiteInfo own = site(4, "a.example", "/", false);
        SiteInfo other = site(5, "b.example", "/", false);
        assertThatThrownBy(() -> new SiteUpdated(SiteId.of(4), AT, own, other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an event about site 4 carries the record of site 5");
        assertThatThrownBy(() -> new SiteFlagsChanged(SiteId.of(4), AT, own, other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an event about site 4 carries the record of site 5");
        assertThatThrownBy(() -> new SiteDeleted(SiteId.of(4), AT, other))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("an event about site 4 carries the record of site 5");
    }

    @Test
    void beforeAndAfterNullsAreNamed() {
        SiteInfo info = site(4, "a.example", "/", false);
        assertThatThrownBy(() -> new SiteUpdated(SiteId.of(4), AT, null, info))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("before");
        assertThatThrownBy(() -> new SiteUpdated(SiteId.of(4), AT, info, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("after");
        assertThatThrownBy(() -> new SiteFlagsChanged(SiteId.of(4), AT, null, info))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("before");
        assertThatThrownBy(() -> new SiteFlagsChanged(SiteId.of(4), AT, info, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("after");
        assertThatThrownBy(() -> new SiteCreated(SiteId.of(4), AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("created");
    }

    @Test
    void nullsAreRefused() {
        SiteInfo info = site(4, "a.example", "/", false);
        assertThatThrownBy(() -> new SiteCreated(null, AT, info))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("site");
        assertThatThrownBy(() -> new SiteCreated(SiteId.of(4), null, info))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("at");
        assertThatThrownBy(() -> new SiteDeleted(SiteId.of(4), AT, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessage("deleted");
    }

    @Test
    void aSiteUrlJoinsSchemeDomainAndPath() {
        assertThat(site(4, "blog.example", "/news/", false).url("https"))
                .isEqualTo(URI.create("https://blog.example/news/"));
        assertThat(site(1, "localhost", "/", false).url("http")).isEqualTo(URI.create("http://localhost/"));
        assertThat(SiteInfo.url("https", "alias.example", "/blog/"))
                .isEqualTo(URI.create("https://alias.example/blog/"));
        assertThatThrownBy(() -> site(1, "localhost", "/", false).url("ftp"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site URL uses http or https, not ftp");
        assertThatThrownBy(() -> SiteInfo.url("ftp", "localhost", "/"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("a site URL uses http or https, not ftp");
    }

    @Test
    void aDeniedCapabilityIsNamed() {
        CapabilityDeniedException denied = new CapabilityDeniedException(NetworkCapabilities.CREATE_SITES);
        assertThat(denied).isInstanceOf(SecurityException.class);
        assertThat(denied.capability()).isEqualTo("create_sites");
        assertThat(denied).hasMessage("the caller lacks the network capability create_sites");
        assertThat(NetworkCapabilities.DELETE_SITES).isEqualTo("delete_sites");
        assertThat(NetworkCapabilities.MANAGE_SITES).isEqualTo("manage_sites");
    }
}
