package org.quarkpress.restwp.deployment;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

/** REST Assured requests to the test application, as the main site's host sees them. */
public final class Http {

    private Http() {}

    /**
     * A request with {@code Host: localhost} and, unless {@code bearer} is null, {@code Authorization: Bearer
     * <bearer>}; redirects are not followed.
     */
    public static RequestSpecification as(String bearer) {
        return at("localhost", bearer);
    }

    /** A request as {@link #as} makes it, with {@code Host: <host>}. */
    public static RequestSpecification at(String host, String bearer) {
        RequestSpecification spec =
                RestAssured.given().header("Host", host).redirects().follow(false);
        if (bearer != null) {
            spec.header("Authorization", "Bearer " + bearer);
        }
        return spec;
    }
}
