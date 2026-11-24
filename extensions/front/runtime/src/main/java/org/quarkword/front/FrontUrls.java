package org.quarkword.front;

/**
 * The current site's home, site, admin and REST URLs for the current request, composed as WordPress's
 * {@code home_url}, {@code site_url}, {@code admin_url}, {@code wp_login_url} and {@code get_rest_url} compose them
 * from the {@code home} and {@code siteurl} options: {@code home} is the root of everything a visitor sees and keeps
 * its stored scheme unless the request is https; {@code siteurl} is the root of the application and follows the
 * request's scheme, except that the admin and login URLs are https whenever {@link #forceSslAdmin} holds. No URL
 * ends with a trailing slash unless its path asks for one. On a single site the {@code quarkword.front.home} and
 * {@code quarkword.front.siteurl} settings override the stored options; on multisite the options always win.
 */
public interface FrontUrls {

    /** {@code home_url()}: {@code https} when the request is https, else the stored scheme; no trailing slash. */
    String home();

    /**
     * {@code home_url(path)}: {@link #home()} followed by {@code /} and {@code path} without its leading slashes; an
     * empty path adds nothing, so {@code home("")} is {@link #home()} and {@code home("/")} ends in exactly one slash.
     */
    String home(String path);

    /** The path of {@link #home()} without leading or trailing slashes: {@code ""} for a root site, {@code site2}. */
    String homePath();

    /** {@code site_url()}: {@code https} when the request is https, else {@code http}; no trailing slash. */
    String siteUrl();

    /** {@code site_url(path)}: {@link #siteUrl()} followed by {@code /} and {@code path} without leading slashes. */
    String siteUrl(String path);

    /**
     * {@code admin_url(path)}: {@code siteUrl("wp-admin/")} followed by {@code path} without its leading slashes,
     * {@code https} when the request is https or {@link #forceSslAdmin} holds.
     */
    String admin(String path);

    /** {@code wp_login_url()}: {@code {siteurl}/wp-login.php} in the admin scheme. */
    String login();

    /**
     * {@code get_rest_url(route)}, built on {@link #home()}: with a permalink structure {@code home/wp-json} + route
     * (an {@code index.php/} structure gives {@code home/index.php/wp-json} + route); with an empty structure
     * {@code home/index.php?rest_route=} + route. An empty route is {@code /}, the index.
     */
    String rest(String route);

    /** WordPress's {@code force_ssl_admin()}: {@code siteurl} is stored with https, or the setting turns it on. */
    boolean forceSslAdmin();

    /** Whether the request's effective scheme (after the proxy headers the server trusts) is https. */
    boolean isSsl();
}
