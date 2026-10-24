package org.quarkpress.it.posts;

/** The fields of an autosave; null means not given. */
public record AutosaveRequest(String title, String content, String excerpt) {}
