package org.quarkpress.restwp.runtime.oembed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.jboss.logging.Logger;
import org.quarkpress.outbound.FetchLimits;
import org.quarkpress.outbound.FetchResponse;
import org.quarkpress.outbound.SafeFetcher;
import org.quarkpress.restwp.runtime.PhpValues;
import org.quarkpress.restwp.runtime.RestJson;

/**
 * Asks a provider endpoint for a URL's oEmbed data the way WordPress does: the endpoint's query gains (or has
 * replaced) {@code maxwidth}, {@code maxheight}, {@code url} and {@code dnt=1}, then {@code format=json}; the safe
 * fetcher gets it within the configured time and size; any status is accepted when the trimmed body is a JSON object
 * (an error page carrying one is oEmbed data), except 501, which WordPress would answer by asking for XML, and
 * QuarkPress does not parse. Anything else is a failure.
 */
@ApplicationScoped
public class OEmbedFetch {

    private static final Logger LOG = Logger.getLogger(OEmbedFetch.class);

    @Inject
    SafeFetcher fetcher;

    @Inject
    OEmbedConfig config;

    /** The provider's object for {@code url}, or empty when the exchange failed or the body is not an object. */
    public Optional<ObjectNode> fetch(String endpoint, String url, long maxwidth, long maxheight) {
        String target = withQuery(endpoint, url, maxwidth, maxheight);
        URI uri;
        try {
            uri = new URI(target);
        } catch (URISyntaxException e) {
            LOG.debugf("oEmbed endpoint %s is not a URI: %s", target, e.getMessage());
            return Optional.empty();
        }
        FetchLimits limits = new FetchLimits(
                config.timeout(),
                fetcher.defaults().maxRedirects(),
                config.maxResponseSize().asLongValue());
        try (FetchResponse response = fetcher.get(uri, limits)) {
            if (response.status() == 501) {
                return Optional.empty();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            response.body().transferTo(bytes);
            String body = PhpValues.trim(bytes.toString(StandardCharsets.UTF_8));
            if (body.isEmpty()) {
                return Optional.empty();
            }
            JsonNode data = RestJson.READER.readTree(body);
            return data instanceof ObjectNode object ? Optional.of(object) : Optional.empty();
        } catch (IOException | IllegalArgumentException e) {
            LOG.debugf("oEmbed fetch of %s failed: %s", target, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * The endpoint with the query WordPress sends: each of {@code maxwidth}, {@code maxheight}, {@code url} and
     * {@code dnt} replaced in place when the endpoint names it, else appended, and then {@code format=json} the same
     * way; the fragment, if any, is dropped.
     */
    static String withQuery(String endpoint, String url, long maxwidth, long maxheight) {
        String base = endpoint;
        int hash = base.indexOf('#');
        if (hash >= 0) {
            base = base.substring(0, hash);
        }
        List<String> names = new ArrayList<>();
        List<String> values = new ArrayList<>();
        int mark = base.indexOf('?');
        if (mark >= 0) {
            String query = base.substring(mark + 1);
            int start = 0;
            while (start <= query.length()) {
                int amp = query.indexOf('&', start);
                String pair = query.substring(start, amp < 0 ? query.length() : amp);
                if (!pair.isEmpty()) {
                    int eq = pair.indexOf('=');
                    names.add(eq < 0 ? pair : pair.substring(0, eq));
                    values.add(eq < 0 ? "" : pair.substring(eq + 1));
                }
                if (amp < 0) {
                    break;
                }
                start = amp + 1;
            }
            base = base.substring(0, mark);
        }
        set(names, values, "maxwidth", Long.toString(maxwidth));
        set(names, values, "maxheight", Long.toString(maxheight));
        set(names, values, "url", urlencode(url));
        set(names, values, "dnt", "1");
        set(names, values, "format", "json");
        StringBuilder out = new StringBuilder(base);
        for (int i = 0; i < names.size(); i++) {
            out.append(i == 0 ? '?' : '&').append(names.get(i)).append('=').append(values.get(i));
        }
        return out.toString();
    }

    private static void set(List<String> names, List<String> values, String name, String value) {
        int at = names.indexOf(name);
        if (at >= 0) {
            values.set(at, value);
        } else {
            names.add(name);
            values.add(value);
        }
    }

    /** PHP's urlencode: letters, digits and {@code -_.} as they are, space as {@code +}, other bytes as %XX. */
    static String urlencode(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (byte b : text.getBytes(StandardCharsets.UTF_8)) {
            int c = b & 0xFF;
            if ((c >= 'a' && c <= 'z')
                    || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9')
                    || c == '-'
                    || c == '_'
                    || c == '.') {
                out.append((char) c);
            } else if (c == ' ') {
                out.append('+');
            } else {
                out.append('%').append("0123456789ABCDEF".charAt(c >> 4)).append("0123456789ABCDEF".charAt(c & 0xF));
            }
        }
        return out.toString();
    }
}
