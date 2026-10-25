package org.quarkpress.scheduling;

/**
 * The per-site feature that gates every job of this module: off, a site's scheduled posts wait and nothing of the
 * site is purged.
 */
public final class SchedulingFeature {

    public static final String KEY = "scheduling";

    private SchedulingFeature() {}
}
