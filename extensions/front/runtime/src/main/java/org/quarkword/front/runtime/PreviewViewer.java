package org.quarkword.front.runtime;

import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.security.runtime.QuarkusPrincipal;
import io.quarkus.security.runtime.QuarkusSecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.quarkword.core.UserId;
import org.quarkword.front.Viewer;
import org.quarkword.posts.PreviewSessions;
import org.quarkword.security.QuarkWordPrincipal;
import org.quarkword.security.UserProfiles;

/**
 * The viewer of a front-end page: the user of the {@code quarkword_preview} session cookie (set by the REST module's
 * preview session route), the only credential the front end reads. Of several such cookies (a browser sends every one
 * whose path covers the request: a parent site's, a stale one), the first whose session this site holds counts. Without
 * a session, or with an expired one, the viewer is anonymous whatever else the request carries. The viewer's identity
 * carries the user's principal, the shape the security module's tokens leave.
 */
@ApplicationScoped
public class PreviewViewer {

    /** The session cookie's name. */
    public static final String COOKIE = "quarkword_preview";

    /** The most session cookies of a request that are looked up. */
    static final int MAX_COOKIES = 10;

    @Inject
    PreviewSessions sessions;

    @Inject
    UserProfiles profiles;

    /** The viewer of a request of the current site with these {@code Cookie} headers. */
    public Viewer of(List<String> cookieHeaders) {
        for (String value : sessionCookies(cookieHeaders)) {
            Optional<UserId> user = sessions.user(value);
            if (user.isPresent()) {
                Optional<SecurityIdentity> identity = identity(user.get());
                if (identity.isPresent()) {
                    return new Viewer(user, identity.get());
                }
            }
        }
        return Viewer.anonymous();
    }

    /**
     * The values of the {@code quarkword_preview} pairs of the {@code Cookie} headers, in the order sent, at most
     * {@value #MAX_COOKIES} (the server's header limit bounds the text). A value in double quotes loses them.
     */
    static List<String> sessionCookies(List<String> headers) {
        List<String> values = new ArrayList<>();
        for (String header : headers) {
            if (values.size() == MAX_COOKIES) {
                break;
            }
            int start = 0;
            while (start <= header.length() && values.size() < MAX_COOKIES) {
                int end = header.indexOf(';', start);
                if (end < 0) {
                    end = header.length();
                }
                String pair = header.substring(start, end).strip();
                int equals = pair.indexOf('=');
                if (equals > 0 && pair.substring(0, equals).strip().equals(COOKIE)) {
                    String value = pair.substring(equals + 1).strip();
                    if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
                        value = value.substring(1, value.length() - 1);
                    }
                    if (!value.isEmpty()) {
                        values.add(value);
                    }
                }
                start = end + 1;
            }
        }
        return values;
    }

    /** The identity carrying {@code user}'s principal. */
    private Optional<SecurityIdentity> identity(UserId user) {
        return profiles.find(user)
                .map(profile -> QuarkusSecurityIdentity.builder()
                        .setPrincipal(new QuarkusPrincipal(profile.subject()))
                        .addAttribute(QuarkWordPrincipal.ATTRIBUTE, new QuarkWordPrincipal(user, profile.subject()))
                        .build());
    }
}
