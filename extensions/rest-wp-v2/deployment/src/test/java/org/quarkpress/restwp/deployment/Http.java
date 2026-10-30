package org.quarkpress.restwp.deployment;

import io.restassured.RestAssured;
import io.restassured.specification.RequestSpecification;

/** REST Assured requests to the test application, as the main site's host sees them. */
public final class Http {

    private Http() {}

    /**
     * A request with {@code Host: localhost} and, unless {@code bearer} is null, {@code Authorization: Bearer
     * <bearer>}.
     */
    public static RequestSpecification as(String bearer) {
        RequestSpecification spec = RestAssured.given().header("Host", "localhost");
        if (bearer != null) {
            spec.header("Authorization", "Bearer " + bearer);
        }
        return spec;
    }
}
