package org.quarkpress.restwp.runtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Proxy;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.quarkpress.restwp.Paging;
import org.quarkpress.restwp.RestRequest;
import org.quarkpress.restwp.RestResponse;

/** The page a request asks for, and the paged answer built from it. */
class PagingTest {

    static final String THINGS = "http://localhost/wp-json/app/v1/things";

    @Test
    void offsetCountsOnlyWhenTheEndpointDeclaresIt() {
        Set<String> declared = Set.of("page", "per_page", "offset");
        assertThat(Paging.of(stub("per_page=10&page=3&offset=5", declared))).isEqualTo(new Paging(3, 10, 5));
        // absint, as WordPress's queries take it
        assertThat(Paging.of(stub("per_page=10&offset=-5", declared))).isEqualTo(new Paging(1, 10, 5));
        // 0 or absent: the page's own offset
        assertThat(Paging.of(stub("per_page=10&page=3&offset=0", declared))).isEqualTo(new Paging(3, 10, 20));
        assertThat(Paging.of(stub("per_page=10&page=3", declared))).isEqualTo(new Paging(3, 10, 20));
        // not declared: ignored, as WordPress's collections without the arg ignore it
        assertThat(Paging.of(stub("per_page=10&page=3&offset=5", Set.of("page", "per_page"))))
                .isEqualTo(new Paging(3, 10, 20));
        assertThat(Paging.of(stub("", Set.of()))).isEqualTo(new Paging(1, 10, 0));
        // a huge page is held at the int range instead of overflowing
        assertThat(Paging.of(stub("page=99999999999&per_page=100", Set.of())).offset())
                .isEqualTo(Integer.MAX_VALUE);
    }

    @Test
    void pagedNamesTheRouteAndReportsThePageTheOffsetFallsOn() {
        StubRequest request = stub("per_page=10&offset=25", Set.of("offset"));
        RestResponse response = RestResponse.paged(RestJson.NODES.arrayNode(), 40, Paging.of(request), request);
        assertThat(response.status()).isEqualTo(200);
        // offset 25 at 10 per page is page 4: prev 3, nothing after
        assertThat(response.headers().get("Link"))
                .containsExactly("<" + THINGS + "?per_page=10&offset=25&page=3>; rel=\"prev\"");
        assertThat(response.headers().get("X-WP-TotalPages")).containsExactly("4");
    }

    @Test
    void pagedNeedsARequestTheServerDispatched() {
        RestRequest plain = (RestRequest) Proxy.newProxyInstance(
                RestRequest.class.getClassLoader(), new Class<?>[] {RestRequest.class}, (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
        assertThatThrownBy(() -> RestResponse.paged(RestJson.NODES.arrayNode(), 1, new Paging(1, 10, 0), plain))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("not a request the REST server dispatched");
    }

    private static StubRequest stub(String query, Set<String> declared) {
        return new StubRequest(PhpQuery.parse(query), THINGS, declared);
    }
}
