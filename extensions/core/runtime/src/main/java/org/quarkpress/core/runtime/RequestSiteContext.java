package org.quarkpress.core.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import java.net.URI;
import java.time.ZoneId;
import java.util.Optional;
import org.quarkpress.core.SiteContext;
import org.quarkpress.core.SiteDirectory;
import org.quarkpress.core.SiteFeatures;
import org.quarkpress.core.SiteId;
import org.quarkpress.core.SiteInfo;
import org.quarkpress.core.SiteNotResolvedException;
import org.quarkpress.core.SiteOptions;

/** The site context of the current request; it only reads the request's binding and never binds it. */
@RequestScoped
public class RequestSiteContext implements SiteContext {

    @Inject
    SiteBinding binding;

    @Inject
    SiteDirectory directory;

    @Inject
    RequestOptionCache options;

    @Inject
    FeatureResolver features;

    @Override
    public boolean isResolved() {
        return binding.current().kind() == Binding.Kind.SITE;
    }

    @Override
    public SiteId siteId() {
        Binding current = binding.current();
        return switch (current.kind()) {
            case SITE -> current.site();
            case NETWORK -> throw new SiteNotResolvedException("the network scope has no current site");
            case UNRESOLVED -> throw new SiteNotResolvedException("no site is resolved for this request");
        };
    }

    @Override
    public SiteInfo site() {
        SiteId id = siteId();
        return directory
                .find(id)
                .orElseThrow(() -> new SiteNotResolvedException("site " + id.value() + " does not exist"));
    }

    @Override
    public SiteFeatures features() {
        Binding current = binding.current();
        return current.kind() == Binding.Kind.SITE ? features.forSite(current.site()) : features.noSite();
    }

    @Override
    public SiteOptions options() {
        return new CachedSiteOptions(siteId(), options);
    }

    @Override
    public ZoneId zone() {
        SiteOptions siteOptions = options();
        return SiteZones.zoneOf(text(siteOptions.get("timezone_string")), text(siteOptions.get("gmt_offset")));
    }

    @Override
    public Optional<URI> redirect() {
        return Optional.empty();
    }

    /**
     * An option as the text {@link SiteZones} reads: text as is, a number exactly (PHP's {@code (float)} of a float is
     * the float itself), a boolean as PHP's {@code (float)} sees it ({@code true} is 1), anything else as no value.
     */
    private static String text(Optional<JsonNode> value) {
        return value.map(node -> {
                    if (node.isTextual()) {
                        return node.textValue();
                    }
                    if (node.isNumber()) {
                        return node.decimalValue().toPlainString();
                    }
                    if (node.isBoolean()) {
                        return node.booleanValue() ? "1" : "";
                    }
                    return "";
                })
                .orElse("");
    }
}
