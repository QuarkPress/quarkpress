package org.quarkword.front.runtime;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Callable;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.quarkword.core.SiteContext;
import org.quarkword.core.SiteId;
import org.quarkword.lib.wppassword.WpPasswords;
import org.quarkword.persistence.SiteScope;
import org.quarkword.persistence.runtime.QuarkWordRuntimeConfig;
import org.quarkword.posts.PostPasswords;
import org.quarkword.posts.PostSnapshot;

/**
 * WordPress's post-password cookie: its name, the {@code Set-Cookie} header the {@code postpass} action sends, how a
 * request's cookie is read and whether it unlocks a post.
 *
 * <p>The name is {@code wp-postpass_} followed by WordPress's {@code COOKIEHASH}: the lower-case hexadecimal MD5 of the
 * network's {@code siteurl}, which is the site's own {@code siteurl} option (or {@code quarkword.front.siteurl}) on a
 * single site and the main site's {@code siteurl} option followed by {@code /} on a multisite, and nothing at all when
 * that address is empty or {@code 0}. The value is a portable phpass hash ({@code $P$B}, 34 characters) of the
 * password as entered, URL-encoded as PHP's {@code setcookie} writes it; a cookie unlocks every post whose password it
 * hashes. The cookie lives ten days, on the {@code home} path of a single site and on the network's path (the main
 * site's) on a multisite, and is
 * neither {@code HttpOnly} nor {@code SameSite}, as WordPress sets it. Cookie values never reach a log or an
 * exception.
 */
@ApplicationScoped
public class PostPassCookies {

    /** The start of the cookie's name. */
    public static final String PREFIX = "wp-postpass_";

    /** The cookie's lifetime, in seconds: ten days. */
    public static final long MAX_AGE = Duration.ofDays(10).toSeconds();

    /** The hash's iteration count, as WordPress's {@code PasswordHash(8, true)}: count character {@code B}. */
    static final int LOG2_ROUNDS = 8;

    /** Every hash WordPress accepts starts so. */
    static final String HASH_START = "$P$B";

    /** The longest password WordPress hashes; it writes {@code *} for longer ones. */
    static final int MAX_PASSWORD_BYTES = 4096;

    /** PHP reads at most this many cookies of a request ({@code max_input_vars}). */
    static final int MAX_PAIRS = 1000;

    /** Characters PHP refuses in a cookie's path; it then sets no cookie. */
    private static final String PATH_REFUSED = ",; \t\r\n\u000B\u000C";

    private static final DateTimeFormatter EXPIRES = DateTimeFormatter.ofPattern(
                    "EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.ENGLISH)
            .withZone(ZoneOffset.UTC);

    private static final SecureRandom RANDOM = new SecureRandom();

    @Inject
    SiteContext site;

    @Inject
    FrontConfig config;

    @Inject
    SiteScope scopes;

    @Inject
    QuarkWordRuntimeConfig runtime;

    /** The key the multisite module gates its beans on, as {@link RequestFrontUrls} reads it. */
    @ConfigProperty(name = "quarkword.multisite.enabled", defaultValue = "false")
    boolean multisite;

    /** The cookie's name on the current site. */
    public String cookieName() {
        return name(hashInput(multisite, siteurl()));
    }

    /**
     * The cookie's path on the current site: {@code home}'s path with a trailing slash on a single site, the network's
     * path (the main site's, {@code /} for a network at the root) on a multisite.
     */
    public String cookiePath() {
        if (multisite) {
            return onMainSite(() -> site.site().path());
        }
        String home = config.home()
                .map(value -> withoutTrailingSlashes(value.trim()))
                .orElseGet(() -> site.options().string("home", ""));
        return cookiePathOf(home);
    }

    /** The decoded value of the current site's cookie in {@code cookieHeaders}, as PHP's {@code $_COOKIE} holds it. */
    public Optional<String> value(List<String> cookieHeaders) {
        return value(cookieHeaders, cookieName());
    }

    /** Whether {@code post} needs no password, or the request's cookie holds the hash of its password. */
    public boolean unlocked(PostSnapshot post, List<String> cookieHeaders) {
        Objects.requireNonNull(post, "post");
        if (!PostPasswords.required(post)) {
            return true;
        }
        return unlocked(post, value(cookieHeaders));
    }

    /**
     * The {@code Set-Cookie} value for {@code password}, with a fresh salt; empty when PHP would refuse
     * {@code cookiePath} (its {@code setcookie} throws).
     */
    public Optional<String> setCookie(String password, boolean secure, String cookiePath, Instant now) {
        return setCookie(cookieName(), password, secure, cookiePath, now, RANDOM);
    }

    // ---- the rules, without the site ------------------------------------------------------------------------------

    /** The network {@code siteurl} that names the cookie: the main site's with a slash on a multisite. */
    static String hashInput(boolean multisite, String siteurl) {
        return multisite ? siteurl + "/" : siteurl;
    }

    /** {@code wp-postpass_} and the MD5 of {@code hashInput}, or nothing after the prefix when PHP reads it as false. */
    static String name(String hashInput) {
        if (hashInput.isEmpty() || hashInput.equals("0")) {
            return PREFIX;
        }
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(hashInput.getBytes(StandardCharsets.UTF_8));
            return PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("the JDK's MD5 is unavailable", e);
        }
    }

    /**
     * WordPress's {@code COOKIEPATH} of a single site: {@code home} followed by {@code /}, with every {@code http://} or
     * {@code https://} and the host after it (in any case) removed.
     */
    static String cookiePathOf(String home) {
        String path = home + "/";
        StringBuilder out = new StringBuilder(path.length());
        int i = 0;
        while (i < path.length()) {
            int hostStart = schemeEnd(path, i);
            if (hostStart > 0 && hostStart < path.length() && path.charAt(hostStart) != '/') {
                int hostEnd = path.indexOf('/', hostStart);
                i = hostEnd < 0 ? path.length() : hostEnd;
                continue;
            }
            out.append(path.charAt(i));
            i++;
        }
        return out.toString();
    }

    /** The index after {@code http://} or {@code https://} (any case) starting at {@code at}, or -1. */
    private static int schemeEnd(String text, int at) {
        for (String scheme : new String[] {"https://", "http://"}) {
            if (text.regionMatches(true, at, scheme, 0, scheme.length())) {
                return at + scheme.length();
            }
        }
        return -1;
    }

    /**
     * The value PHP's {@code $_COOKIE[name]} holds for these {@code Cookie} headers: pairs are split at {@code ;}, a
     * name loses its leading whitespace and has its spaces and dots turned into underscores, a value is percent-decoded
     * ({@code +} stays), a pair without {@code =} has an empty value, and the first pair of a name counts. A name with
     * a bracket makes PHP's value an array, whatever came before; that reads as an empty value here (present, and no
     * hash). The value is a byte string (one character per byte).
     */
    static Optional<String> value(List<String> headers, String name) {
        Optional<String> found = Optional.empty();
        int pairs = 0;
        for (String header : headers) {
            int start = 0;
            while (start <= header.length() && pairs < MAX_PAIRS) {
                int end = header.indexOf(';', start);
                if (end < 0) {
                    end = header.length();
                }
                String pair = header.substring(start, end);
                start = end + 1;
                int from = 0;
                while (from < pair.length() && " \t\r\n".indexOf(pair.charAt(from)) >= 0) {
                    from++;
                }
                int equals = pair.indexOf('=', from);
                if (from == pair.length() || equals == from) {
                    // PHP skips a pair without a name before it counts it
                    continue;
                }
                pairs++;
                String rawName = equals < 0 ? pair.substring(from) : pair.substring(from, equals);
                int bracket = rawName.indexOf('[');
                String base = phpName(bracket < 0 ? rawName : rawName.substring(0, bracket));
                if (!base.equals(name)) {
                    continue;
                }
                if (bracket >= 0) {
                    return Optional.of("");
                }
                if (found.isEmpty()) {
                    String raw = equals < 0 ? "" : pair.substring(equals + 1);
                    found = Optional.of(PhpUrls.rawurldecode(PhpUrls.bytes(raw)));
                }
            }
        }
        return found;
    }

    /** A cookie name as PHP registers it: spaces and dots as underscores. */
    private static String phpName(String name) {
        return name.replace(' ', '_').replace('.', '_');
    }

    /**
     * Whether {@code post} needs no password, or {@code cookie} is a {@code $P$B} hash of its password. A value with a
     * byte outside ASCII is no hash WordPress writes and unlocks nothing.
     */
    static boolean unlocked(PostSnapshot post, Optional<String> cookie) {
        if (!PostPasswords.required(post)) {
            return true;
        }
        if (cookie.isEmpty()) {
            return false;
        }
        String hash = cookie.get();
        if (!hash.startsWith(HASH_START) || !ascii(hash)) {
            return false;
        }
        return WpPasswords.check(post.password(), hash);
    }

    private static boolean ascii(String text) {
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) > 0x7f) {
                return false;
            }
        }
        return true;
    }

    /**
     * The {@code Set-Cookie} value PHP's {@code setcookie(name, hash, now + 10 days, cookiePath, '', secure)} writes:
     * {@code name=<hash, raw-URL-encoded>; expires=<date>; Max-Age=864000; path=<cookiePath>}, then {@code ; secure}
     * when asked; the hash is {@code *} for a password over 4096 bytes. Empty when the path holds a character PHP
     * refuses there ({@code ,; } space, tab, CR, LF, VT, FF), for which its {@code setcookie} throws.
     */
    static Optional<String> setCookie(
            String name, String password, boolean secure, String cookiePath, Instant now, SecureRandom random) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(password, "password");
        Objects.requireNonNull(cookiePath, "cookiePath");
        Objects.requireNonNull(now, "now");
        for (int i = 0; i < cookiePath.length(); i++) {
            if (PATH_REFUSED.indexOf(cookiePath.charAt(i)) >= 0) {
                return Optional.empty();
            }
        }
        String hash = password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES
                ? "*"
                : WpPasswords.hashPortable(password, LOG2_ROUNDS, random);
        StringBuilder cookie = new StringBuilder(160)
                .append(name)
                .append('=')
                .append(PhpUrls.rawurlencode(hash))
                .append("; expires=")
                .append(EXPIRES.format(now.plusSeconds(MAX_AGE)))
                .append("; Max-Age=")
                .append(MAX_AGE)
                .append("; path=")
                .append(cookiePath);
        if (secure) {
            cookie.append("; secure");
        }
        return Optional.of(cookie.toString());
    }

    /**
     * Whether the form shows WordPress's invalid-password notice: the request's referrer is the post's permalink and
     * the request carries the cookie (whatever it holds).
     */
    static boolean invalid(Optional<String> rawReferer, String permalink, boolean cookiePresent) {
        return cookiePresent && rawReferer.isPresent() && rawReferer.get().equals(permalink);
    }

    /**
     * WordPress's {@code wp_get_raw_referer}: the request's {@code _wp_http_referer} field when it is neither empty nor
     * {@code 0}, else the {@code Referer} header on the same condition.
     */
    static Optional<String> rawReferer(Optional<String> field, Optional<String> header) {
        if (field.isPresent() && !phpEmpty(field.get())) {
            return field;
        }
        return header.filter(value -> !phpEmpty(value));
    }

    private static boolean phpEmpty(String value) {
        return value.isEmpty() || value.equals("0");
    }

    // ---- the site's addresses -------------------------------------------------------------------------------------

    /**
     * The {@code siteurl} the name hashes: the setting or the option of a single site, the main site's option on a
     * multisite (read on the main site when the request is another site's).
     */
    private String siteurl() {
        if (!multisite) {
            return config.siteurl()
                    .map(value -> withoutTrailingSlashes(value.trim()))
                    .orElseGet(() -> site.options().string("siteurl", ""));
        }
        return onMainSite(() -> site.options().string("siteurl", ""));
    }

    /** {@code read} on the main site: in the current request when it is the main site's, else in the main site's scope. */
    private String onMainSite(Callable<String> read) {
        SiteId main = SiteId.of(runtime.mainSiteId());
        try {
            if (site.isResolved() && site.site().id().equals(main)) {
                return read.call();
            }
            return scopes.call(main, read);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("the main site's address could not be read", e);
        }
    }

    private static String withoutTrailingSlashes(String url) {
        int end = url.length();
        while (end > 0 && url.charAt(end - 1) == '/') {
            end--;
        }
        return url.substring(0, end);
    }
}
