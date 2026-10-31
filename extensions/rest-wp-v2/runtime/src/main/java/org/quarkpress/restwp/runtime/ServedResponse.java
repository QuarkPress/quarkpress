package org.quarkpress.restwp.runtime;

import java.util.List;
import java.util.Map;

/** What goes back over HTTP for a REST request: the status, the headers in the order they were set, and the body. */
public record ServedResponse(int status, Map<String, List<String>> headers, byte[] body) {}
