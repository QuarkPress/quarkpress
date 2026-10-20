package org.quarkpress.it.posts;

/** Fields of a post to create or update; null means not given. */
public record PostRequest(
        String type, String status, String title, String content, String slug, Long parent, Integer version) {}
