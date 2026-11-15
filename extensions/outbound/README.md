# QuarkPress Outbound

A safe HTTP GET for the URLs a QuarkPress server is asked to fetch on a user's behalf (the block editor's oEmbed
previews, media uploads from a URL): the URL is tested, its host resolved once and the connection pinned to an address
that passed, every redirect checked again, the body decompressed and cut at a byte cap. It stands for WordPress's safe
remote request (`wp_safe_remote_get`), with the rules WordPress's reject-unsafe-URLs check applies and a few stricter
ones. Reusable on its own: nothing in it knows about sites, posts or the REST API.

## Installation

```xml
<dependency>
  <groupId>org.quarkpress</groupId>
  <artifactId>quarkpress-outbound</artifactId>
</dependency>
```

The extension brings `quarkpress-core` (for its configuration keys only), `quarkus-vertx` (the HTTP client is Vert.x
core's) and `quarkus-tls-registry`. Public API lives in `org.quarkpress.outbound`; `org.quarkpress.outbound.runtime`
is implementation and may change.

## Using it

Inject `SafeFetcher`:

```java
@Inject
SafeFetcher fetcher;

boolean ok = fetcher.allowed("https://example.com/photo.jpg");      // the URL test alone, no request
try (FetchResponse response = fetcher.get(URI.create("https://example.com/photo.jpg"), fetcher.defaults())) {
    if (response.status() == 200) {
        Files.copy(response.body(), target);                        // cut at the byte cap: FetchException TOO_LARGE
    }
} catch (FetchException e) {
    switch (e.kind()) { ... }
}
```

- `boolean allowed(String url)` runs the URL test (below) without a request, resolving the host within
  `quarkpress.outbound.timeout`; `null` and a malformed URL are false.
- `FetchResponse get(URI url, FetchLimits limits)` fetches the URL. `FetchLimits(timeout, maxRedirects, maxBytes)` bounds
  the whole exchange (redirects and body included; more than zero), the redirects followed and the body's bytes after
  decompression; `fetcher.defaults()` is the configured `timeout`, `max-redirects` and `max-response-size`.
- `FetchResponse` (close it when done; an unread body closes the connection) has `status()`, `reason()`,
  `header(name)` (the values in order, name compared ignoring case; empty when absent), `url()` (the URL that
  answered: the one asked for, or the last redirect's target) and `body()`, an `InputStream` read as the bytes
  arrive, decompressed, with back-pressure (a slow reader holds at most 16 buffers; the response is paused meanwhile).
  Past the byte cap the read closes the connection and throws a `FetchException` of kind `TOO_LARGE`; past the
  deadline, one of kind `TIMEOUT`.
- `FetchException` (an `IOException`) carries a `Kind`: `REFUSED_URL` (the URL fails the test), `REFUSED_REDIRECT`
  (a redirect's target fails it), `DNS` (the host did not resolve), `CONNECT`, `TLS` (the handshake or the certificate
  failed), `TIMEOUT`, `TOO_LARGE`, `IO`. The two refusals carry WordPress's message "A valid URL was not provided."
  (`FetchException.REFUSED_MESSAGE`); the others the cause's.
- Both calls block (host resolution, the exchange) and refuse to run on an event loop thread with
  `IllegalStateException`: call them from a worker thread (a blocking REST method, `@Blocking`, an executor).

### The URL test

A URL may be fetched when all of these hold; a hop that fails any is refused before a connection is made:

| Rule | Refused |
|---|---|
| Scheme `http` or `https` | `ftp://`, `file://`, `javascript:`, a relative reference |
| A host, with no user information | `http://user@host/`, `http:///path` |
| The host is a name or an IPv4 literal | an IPv6 literal (`http://[::1]/`, as WordPress refuses them), a host holding `[`, `]`, `:`, `#` or `?` |
| The port (the URL's, else the scheme's 80 or 443) is in `quarkpress.outbound.allowed-ports` | `:22`, `:8443` by default |
| Every address the host resolves to is public (`AddressPolicy.isPublic`) | `http://127.0.0.1/`, a host with one private address among public ones (`203.0.113.9, 10.0.0.1`) |

`allow-private-addresses` lifts the address rule only; scheme, user information and port rules still apply.

The address rule refuses, for IPv4: `0.0.0.0/8` (this network), `10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`
(private), `100.64.0.0/10` (shared address space), `127.0.0.0/8` (loopback), `169.254.0.0/16` (link-local, the
cloud metadata address included), `192.0.0.0/24` (protocol assignments), `192.0.2.0/24`, `198.51.100.0/24`,
`203.0.113.0/24` (documentation), `192.88.99.0/24` (6to4 relays), `198.18.0.0/15` (benchmarking), `224.0.0.0/4`
(multicast) and `240.0.0.0/4` (reserved, broadcast included). For IPv6: `::/96` (unspecified, loopback and the
IPv4-compatible addresses), `2001::/23` (IETF protocol assignments, Teredo and the ORCHID ranges included),
`2001:db8::/32` (documentation), `100::/64` (discard-only), `fc00::/7` (unique local), `fe80::/10` (link-local),
`fec0::/10` (site-local), `ff00::/8` (multicast) and the local-use NAT64 prefix `64:ff9b:1::/48` outright (its
shorter prefixes place the IPv4 address anywhere inside it). IPv6 addresses that carry an IPv4 address are judged by
that address with the IPv4 rules: IPv4-mapped (`::ffff:0:0/96`, so `::ffff:10.0.0.1` is refused and `::ffff:8.8.8.8`
allowed), IPv4-translated (`::ffff:0:0:0/96`), the NAT64 well-known prefix (`64:ff9b::/96`) and 6to4 (`2002::/16`).

Operators whose network runs DNS64/NAT64 with a **network-specific** prefix (one of their own, not `64:ff9b::/96` or
`64:ff9b:1::/48`) must refuse it themselves: such addresses look like public unicast to the policy, and a resolver
can hand one out for any name. Refuse the prefix at the network (a firewall rule for the server's egress), or replace
`HostResolver` with one that filters them out; a name the resolver leaves without any address fails as unresolved
(`FetchException.Kind.DNS`), not as refused.

### Pinning, redirects, the cap

Each hop resolves the host **once** (through `HostResolver`) and connects to the first of the addresses the check
passed, with Vert.x's server address set to it while the `Host` header, TLS SNI and certificate host verification
use the URL's host: a host that resolves elsewhere a moment later (DNS rebinding) is not reached there. Redirects
(301, 302, 303, 307, 308 with a `Location`) are followed by hand, each target resolved against the current URL,
checked as a new URL (refusals are `REFUSED_REDIRECT`) and connected the same way; after `maxRedirects` redirects
the last 3xx answer is returned as it is, as WordPress returns it. A `Location` that is not a URI (`java.net.URI`
refuses it: a space, say) is `REFUSED_REDIRECT`, not followed.

Responses are decompressed (`gzip`, `deflate`; the client asks for them) and the byte cap counts the decompressed
bytes, so a small compressed body that inflates past the cap is `TOO_LARGE`. Past the cap the connection is closed
and the read fails, within one buffer of the cap: a 10 GB body costs the cap's worth of transfer, not the whole body.

Timeouts: `connect-timeout` to open a connection; the `FetchLimits` timeout for the whole exchange (host resolution
counted, so a resolver slower than the time left ends the fetch with `TIMEOUT`); `idle-timeout` for the longest
silence before the headers and between two reads. The idle timeout also runs while the reader is slow and the response
is paused by back-pressure: a reader that takes longer than `idle-timeout` between two reads of a large body gets
`TIMEOUT` although the server is fine (the fetcher's callers spool to disk, where this does not happen).

Every request sends `User-Agent: QuarkPress` (`user-agent`). HTTPS trusts the JVM's trust store, or the trust store
of the Quarkus TLS registry configuration named by `tls-configuration-name` (`quarkus.tls.<name>.*`); a name that
exists without a trust store, or that names no configuration, fails the start.

### Replacing the resolver

`HostResolver` (`List<InetAddress> resolve(String host)`) is the bean the fetcher asks once per hop; the default asks
the JDK (`InetAddress.getAllByName`). An application replaces it with an `@Alternative @Priority` bean, for example to
filter a network-specific NAT64 prefix or to pin test hosts to a local server. Resolutions run on a pool of 8 daemon
threads (`SafeFetcher.RESOLVER_THREADS`), so a caller stops waiting at its deadline even when the resolver does not;
with more than 8 fetches resolving at once the others queue, still bounded by their own deadlines. A server that
fetches many URLs at once with a slow resolver sees them wait on each other there: keep the system resolver fast (a
local caching resolver) rather than widen the pool.

## Configuration

Run time; checked at start-up with the other `quarkpress.*` keys (an unknown `quarkpress.outbound.*` key fails the
start).

| Key | Default | Meaning |
|---|---|---|
| `quarkpress.outbound.connect-timeout` | `5s` | How long a connection may take to open. |
| `quarkpress.outbound.timeout` | `5s` | How long a whole exchange may take, redirects and body included, unless the caller's `FetchLimits` says otherwise; also how long `allowed` waits for the host to resolve. |
| `quarkpress.outbound.idle-timeout` | `5s` | How long a connection may stay silent before the headers and between two reads (also while a slow reader pauses the response). |
| `quarkpress.outbound.max-redirects` | `5` | How many redirects are followed; the 3xx answer after them is returned as is. |
| `quarkpress.outbound.max-response-size` | `1M` | The largest body, after decompression, unless the caller's `FetchLimits` says otherwise; a larger one closes the connection (`TOO_LARGE`). |
| `quarkpress.outbound.allowed-ports` | `80,443,8080` | The ports a URL may name; a URL without a port uses its scheme's, which must be listed too. WordPress's list. |
| `quarkpress.outbound.allow-private-addresses` | `false` | Lets requests reach loopback, private, link-local and the other non-public addresses. **For tests only**: on, anyone who can make the server fetch a URL can reach its own network (the metadata service of a cloud host, say). The scheme, user information and port rules still apply, and the server logs at WARN when it starts: "quarkpress.outbound.allow-private-addresses is true: outbound requests may reach loopback and private addresses; use it for tests only". |
| `quarkpress.outbound.user-agent` | `QuarkPress` | The `User-Agent` header sent. |
| `quarkpress.outbound.tls-configuration-name` | unset: the JVM's trust store | The name of a Quarkus TLS registry configuration (`quarkus.tls.<name>.*`) whose trust store replaces the JVM's for HTTPS requests. An unknown name, or one without a trust store, fails the start. |

## Limits

- GET only, no request body, no custom headers: what oEmbed and URL uploads need.
- IPv6 literals are refused as WordPress refuses them; a host that resolves to IPv6 addresses is judged by the
  address rules above and reached over IPv6 when they pass.
- The policy refuses the ranges listed; a network-specific NAT64 prefix is the operator's to refuse (see above).
- Trust is all or nothing per request: the JVM's store or the named configuration's; there is no per-host pinning.
- A `Location` header that `java.net.URI` refuses is a refused redirect, where WordPress's HTTP API may encode it
  and follow.

## Decisions

- Vert.x core's `HttpClient`, since no maintained SSRF-safe Java client exists in the platform: its request options
  take a server address apart from the URL's host, which gives the pinning without a custom socket factory, and SNI and
  host verification are forced on.
- A host is refused when **any** of its addresses is inside, not only the first: a resolver that returns a public
  address first and a private one second would otherwise be a way in.
- No same-host exemption: WordPress lets a URL on its own host through its address check; QuarkPress does not (its
  callers document the difference, rest-wp-v2 D39), since a site on a loopback or private address is exactly what the
  check protects.
- The 3xx answer after the redirect budget is returned as it is, as WordPress returns it, rather than failing.
- Decompression is on and the cap counts decompressed bytes, so the cap bounds memory and disk, not only transfer.
- The module is a candidate for extraction as a standalone Quarkus extension (SiteNetSoft) once a second consumer
  outside QuarkPress needs it; nothing in it depends on QuarkPress beyond the configuration-key registration.
