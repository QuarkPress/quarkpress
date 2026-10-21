package org.quarkpress.it.posts;

import java.util.List;

public record TermRequest(String name, String slug, String description, Long parent, List<String> names) {}
