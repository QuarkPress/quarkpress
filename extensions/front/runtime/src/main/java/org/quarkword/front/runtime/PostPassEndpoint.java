package org.quarkword.front.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.quarkword.core.QuarkWordClock;
import org.quarkword.core.SiteContext;
import org.quarkword.front.FrontAnswer;
import org.quarkword.front.FrontUrls;

/**
 * WordPress's {@code wp-login.php?action=postpass}: a request to {@code wp-login.php} on the site's path whose
 * {@code action} (the form's, else the query's) is {@code postpass}; WordPress runs it for any method, a read
 * ({@code GET}, {@code HEAD}) having no form. A string {@code post_password} field of a {@code POST} sets the
 * post-password cookie with a fresh hash of it (the password is not checked: a wrong one is noticed when the post is
 * shown again); then the visitor is sent with a 302 to the {@code redirect_to} field when the form has one, else to
 * the referrer, either way only to the site's own host, falling back to {@code admin_url()}. With neither a
 * {@code redirect_to} field nor a usable referrer the answer is a 200 without a body, as WordPress's. The cookie is
 * {@code secure} when that target is an {@code https} address. A plain-http request on a site whose login is https
 * ({@code force_ssl_admin}) is sent to the https address first, without a cookie. Every answer carries WordPress's
 * login headers (the no-cache set, {@code X-Frame-Options}, {@code Referrer-Policy}). Where WordPress fails with a PHP
 * error (a {@code redirect_to} array, a cookie path PHP refuses) the answer is a 500 without a body or cookie. The form
 * is read as PHP reads {@code $_POST}: at most {@code max_input_vars} + 1 pairs, and nothing of a body over
 * {@link #MAX_FORM_BYTES}, as of one over PHP's {@code post_max_size}. The submitted password never reaches a log or
 * an exception.
 */
@ApplicationScoped
public class PostPassEndpoint {

    /** The file name the endpoint answers on, below the site's path. */
    public static final String FILE = "wp-login.php";

    /**
     * The largest form body read, in bytes (64 KiB). WordPress's form posts {@code post_password} (hashed only up to
     * 4096 bytes, at most three times that encoded), {@code redirect_to} (a permalink) and {@code Submit}: a few
     * kilobytes. A larger body is not read at all, as PHP leaves {@code $_POST} empty for a body over its
     * {@code post_max_size} and runs the script on.
     */
    public static final int MAX_FORM_BYTES = 65_536;

    static final String ACTION = "postpass";

    private static final String FORM = "application/x-www-form-urlencoded";

    @Inject
    SiteContext site;

    @Inject
    FrontUrls urls;

    @Inject
    PostPassCookies cookies;

    @Inject
    QuarkWordClock clock;

    /**
     * The answer to {@code entry} with {@code body} (read only for a {@code POST}) of {@code contentType} and the
     * {@code referer} header; empty when it is no {@code postpass} request of the current site (another path, another
     * action), which the front end answers 404: QuarkWord serves no login page.
     */
    public Optional<FrontAnswer> answer(
            FrontRoute.Entry entry, byte[] body, Optional<String> contentType, Optional<String> referer) {
        if (!onSitePath(entry.rawPath(), site.site().path())) {
            return Optional.empty();
        }
        Map<String, Object> get = PhpUrls.parseStr(PhpUrls.bytes(entry.rawQuery()));
        Map<String, Object> post = entry.method().equals("POST")
                        && body.length <= MAX_FORM_BYTES
                        && contentType.map(PostPassEndpoint::isForm).orElse(false)
                ? PhpUrls.parsePost(new String(body, StandardCharsets.ISO_8859_1))
                : Map.of();
        Object action = post.containsKey("action") ? post.get("action") : get.get("action");
        if (!ACTION.equals(action) || get.containsKey("key") || get.containsKey("checkemail")) {
            return Optional.empty();
        }
        String requestUri = entry.target();
        String homeHost = PhpUrls.parseUrl(urls.home())
                .map(parts -> parts.host == null ? "" : parts.host)
                .orElse("");
        String fallback = urls.admin("");
        Map<String, List<String>> headers = loginHeaders();
        if (urls.forceSslAdmin() && !urls.isSsl()) {
            String https = "https://" + entry.host() + requestUri;
            return Optional.of(redirect(
                    headers, RedirectUrls.validate(RedirectUrls.sanitize(https), homeHost, requestUri, fallback)));
        }
        Optional<String> target;
        if (post.containsKey("redirect_to")) {
            if (!(post.get("redirect_to") instanceof String field)) {
                // WordPress fails on a redirect_to array before it sets anything
                return Optional.of(new FrontAnswer(500, headers, new byte[0]));
            }
            // $_POST holds the field slashed; WordPress uses it so
            target = Optional.of(addSlashes(PhpUrls.text(field)));
        } else {
            target = referer(post, get, referer, requestUri, homeHost);
        }
        boolean secure = target.filter(value -> !phpEmpty(value))
                .flatMap(PhpUrls::parseUrl)
                .map(parts -> "https".equals(parts.scheme))
                .orElse(false);
        if (post.get("post_password") instanceof String password) {
            Optional<String> cookie =
                    cookies.setCookie(PhpUrls.text(password), secure, cookies.cookiePath(), clock.now());
            if (cookie.isEmpty()) {
                // PHP's setcookie throws on a path it refuses (a home path with a space, say): WordPress fails
                return Optional.of(new FrontAnswer(500, headers, new byte[0]));
            }
            headers.put("Set-Cookie", List.of(cookie.get()));
        }
        String location = target.map(
                        value -> RedirectUrls.validate(RedirectUrls.sanitize(value), homeHost, requestUri, fallback))
                .orElse("");
        return Optional.of(redirect(headers, location));
    }

    /** Whether {@code rawPath} is {@code wp-login.php} right below the site's path (compared without case). */
    static boolean onSitePath(String rawPath, String sitePath) {
        if (!rawPath.endsWith("/" + FILE)) {
            return false;
        }
        String directory = rawPath.substring(0, rawPath.length() - FILE.length());
        String expected = sitePath.endsWith("/") ? sitePath : sitePath + "/";
        return directory.equalsIgnoreCase(expected);
    }

    /**
     * WordPress's {@code wp_get_referer}: the raw referrer ({@code _wp_http_referer} of the form, else of the query,
     * else the header), unless it is the request's own address, validated for the site's host; empty when none is
     * left.
     */
    private Optional<String> referer(
            Map<String, Object> post,
            Map<String, Object> get,
            Optional<String> header,
            String requestUri,
            String homeHost) {
        Object field =
                post.containsKey("_wp_http_referer") ? post.get("_wp_http_referer") : get.get("_wp_http_referer");
        Optional<String> raw = PostPassCookies.rawReferer(
                field instanceof String value ? Optional.of(PhpUrls.text(value)) : Optional.empty(), header);
        if (raw.isEmpty() || raw.get().equals(requestUri) || raw.get().equals(urls.home() + requestUri)) {
            return Optional.empty();
        }
        return Optional.ofNullable(RedirectUrls.validate(raw.get(), homeHost, requestUri, null));
    }

    /** A 302 to {@code location} as {@code wp_redirect} sends it, or a 200 without a body when it is empty or 0. */
    private static FrontAnswer redirect(Map<String, List<String>> headers, String location) {
        String sanitized = RedirectUrls.sanitize(location);
        if (phpEmpty(location)) {
            return new FrontAnswer(200, headers, new byte[0]);
        }
        headers.put("X-Redirect-By", List.of(FrontHeaders.REDIRECT_BY));
        headers.put("Location", List.of(sanitized));
        return new FrontAnswer(302, headers, new byte[0]);
    }

    /** The headers {@code wp-login.php} sends on every request: the no-cache set, the type, the framing and referrer policy. */
    private static Map<String, List<String>> loginHeaders() {
        Map<String, List<String>> headers = new LinkedHashMap<>();
        headers.put("Expires", List.of(FrontHeaders.EXPIRES));
        headers.put("Cache-Control", List.of(FrontHeaders.NO_CACHE));
        headers.put("Content-Type", List.of(FrontHeaders.HTML));
        headers.put("X-Frame-Options", List.of("SAMEORIGIN"));
        headers.put("Referrer-Policy", List.of("strict-origin-when-cross-origin"));
        return headers;
    }

    private static boolean isForm(String contentType) {
        int semicolon = contentType.indexOf(';');
        String type = (semicolon < 0 ? contentType : contentType.substring(0, semicolon)).strip();
        return type.toLowerCase(Locale.ROOT).equals(FORM);
    }

    /** PHP's {@code addslashes}: a backslash before every {@code '}, {@code "} and backslash, NUL as {@code \0}. */
    static String addSlashes(String text) {
        StringBuilder out = new StringBuilder(text.length() + 8);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '\'', '"', '\\' -> out.append('\\').append(c);
                case '\0' -> out.append("\\0");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean phpEmpty(String value) {
        return value.isEmpty() || value.equals("0");
    }
}
