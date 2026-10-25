package org.quarkpress.it.posts;

/** Fields of a post to create or update; null means not given. {@code dateGmt} is an ISO-8601 instant. */
public record PostRequest(
        String type,
        String status,
        String title,
        String content,
        String slug,
        Long parent,
        Integer version,
        String dateGmt) {}
