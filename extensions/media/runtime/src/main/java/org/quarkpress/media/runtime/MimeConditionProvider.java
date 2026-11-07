package org.quarkpress.media.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.quarkpress.core.SiteId;
import org.quarkpress.persistence.runtime.TenantIds;
import org.quarkpress.posts.PostConditionProvider;
import org.quarkpress.posts.TermCondition;

/**
 * The media condition of post queries, {@value #KEY}: a post matches when it has a media row on the site and, when
 * the argument (an array of texts read as {@link MimeFilter} reads them) restricts the type, the row's type matches one
 * of the patterns. With no text, or a filter dropped, only the media row is required. Types and patterns are compared
 * in lower case on every database, as WordPress's default collation compares them.
 */
@ApplicationScoped
public class MimeConditionProvider implements PostConditionProvider {

    public static final String KEY = "quarkpress.media.mime-type";

    @Override
    public String key() {
        return KEY;
    }

    /** @throws IllegalArgumentException when the argument is not an array of texts */
    @Override
    public TermCondition condition(SiteId site, JsonNode argument, String postId, String paramPrefix) {
        if (!argument.isArray()) {
            throw new IllegalArgumentException("the MIME types of a media query must be an array of texts");
        }
        List<String> values = new ArrayList<>(argument.size());
        for (JsonNode value : argument) {
            if (!value.isTextual()) {
                throw new IllegalArgumentException("the MIME types of a media query must be an array of texts");
            }
            values.add(value.textValue());
        }
        Optional<List<MimeFilter.MimeClause>> clauses = MimeFilter.parse(values);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put(paramPrefix + "site", TenantIds.param(site));
        String row = "exists (select 1 from MediaEntity m where m.postId = " + postId + " and m.siteId = :"
                + paramPrefix + "site";
        if (clauses.isEmpty()) {
            return new TermCondition.Jpql(row + ")", params);
        }
        List<String> matches = new ArrayList<>();
        int i = 0;
        for (MimeFilter.MimeClause clause : clauses.get()) {
            String name = paramPrefix + i++;
            params.put(name, clause.value().toLowerCase(Locale.ROOT));
            matches.add("lower(m.mimeType) " + (clause.like() ? "like" : "=") + " :" + name);
        }
        return new TermCondition.Jpql(row + " and (" + String.join(" or ", matches) + "))", params);
    }
}
