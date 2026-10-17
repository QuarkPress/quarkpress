package org.quarkpress.lib.wpkses;

import java.net.URI;
import java.util.function.Predicate;

/** WordPress's {@code _wp_kses_allow_pdf_objects} test against a configured upload base URL. */
final class PdfObjects {

    private PdfObjects() {}

    /** The test for {@code uploadBaseUrl}; with null, no URL passes. */
    static Predicate<String> policy(String uploadBaseUrl) {
        if (uploadBaseUrl == null) {
            return url -> false;
        }
        URI uri = URI.create(uploadBaseUrl);
        String host = uri.getHost() == null ? "" : uri.getHost();
        String port = uri.getPort() < 0 ? "" : ":" + uri.getPort();
        String http = "http://" + host + port + "/";
        String https = "https://" + host + port + "/";
        return url -> !url.contains("?")
                && !url.contains("#")
                && url.endsWith(".pdf")
                && (url.startsWith(http) || url.startsWith(https));
    }
}
