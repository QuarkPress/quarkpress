package org.quarkpress.restwp;

import java.util.Map;

/** The URLs of the current site's REST API, as WordPress's hrefs are built. */
public interface RestUrls {

    /** The API root: {@code http://<host><site path>wp-json/} with the configured base. */
    String root();

    /** {@code root()} followed by {@code path} without its leading slash. */
    String route(String path);

    /** {@link #route(String)} with {@code query} appended, keys and values URL-encoded as PHP's urlencode does. */
    String route(String path, Map<String, String> query);

    /** The site's home URL with a trailing slash, for plain front-end links. */
    String home();
}
