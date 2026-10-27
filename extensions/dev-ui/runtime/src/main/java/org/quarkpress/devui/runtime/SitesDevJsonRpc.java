package org.quarkpress.devui.runtime;

import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.devui.runtime.DevViews.CreateSiteResult;
import org.quarkpress.devui.runtime.DevViews.SiteView;
import org.quarkpress.multisite.NewSite;
import org.quarkpress.multisite.Sites;

/** The Dev UI's JSON-RPC methods of the multisite module: the sites of the network, and creating one. */
public class SitesDevJsonRpc {

    /** The most sites one call lists, the most {@code Sites.list} returns. */
    private static final int LIMIT = 1000;

    @Inject
    DevCalls calls;

    @Inject
    Sites sites;

    /** The network's sites in id order, the first thousand. */
    public List<SiteView> sites() {
        return calls.asSystem(() -> {
            List<SiteView> views = new ArrayList<>();
            for (SiteInfo site : sites.list(0, LIMIT)) {
                views.add(SiteView.of(site));
            }
            return views;
        });
    }

    /**
     * Creates a public site at {@code domain} and {@code path} titled {@code title}, as the system, and answers it. A
     * site the multisite module refuses (an address in use, a reserved path...) is answered as the error of the result,
     * with the module's message.
     */
    public CreateSiteResult createSite(String domain, String path, String title) {
        try {
            return new CreateSiteResult(
                    SiteView.of(calls.asSystem(() -> sites.create(NewSite.of(domain, path, title)))), null);
        } catch (IllegalArgumentException e) {
            return new CreateSiteResult(null, e.getMessage());
        }
    }
}
