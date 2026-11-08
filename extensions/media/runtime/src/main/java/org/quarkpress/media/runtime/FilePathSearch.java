package org.quarkpress.media.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.LinkedHashMap;
import java.util.Map;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.QuarkPressRuntimeConfig;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostSearchProvider;
import org.quarkpress.posts.TermCondition;
import org.quarkpress.posts.runtime.SearchRank;

/**
 * The attached file's path as a searchable column of post queries, {@value #KEY}: the path of the media row's main
 * file relative to the site's uploads folder (such as {@code 2026/11/photo.jpg}; the {@code sites/<id>/} folder of a
 * site other than the main one is not part of it), matched as the post columns are: holding the term, ignoring case,
 * or, for an exact search, being the whole term. A post without a media row matches no term.
 */
@ApplicationScoped
public class FilePathSearch implements PostSearchProvider {

    public static final String KEY = "quarkpress.media.file-path";

    @Inject
    QuarkPressRuntimeConfig runtimeConfig;

    @Override
    public String key() {
        return KEY;
    }

    @Override
    public TermCondition term(SiteId site, String term, boolean exact, String postId, String paramPrefix) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(paramPrefix + "site", TenantIds.param(site));
        params.put(paramPrefix + "path", SearchRank.pattern(term, exact));
        String path = "m.storageKey";
        if (site.value() != runtimeConfig.mainSiteId()) {
            // the key's characters after the site's folder: JPQL counts from 1
            params.put(paramPrefix + "start", ("sites/" + site.value() + "/").length() + 1);
            path = "substring(m.storageKey, :" + paramPrefix + "start)";
        }
        return new TermCondition.Jpql(
                "exists (select 1 from MediaEntity m where m.postId = " + postId + " and m.siteId = :" + paramPrefix
                        + "site and " + SearchRank.contains(path, ":" + paramPrefix + "path") + ")",
                params);
    }
}
