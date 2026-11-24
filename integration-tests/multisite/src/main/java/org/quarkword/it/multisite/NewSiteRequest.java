package org.quarkword.it.multisite;

import java.util.Map;

public record NewSiteRequest(String domain, String path, String title, Map<String, Boolean> features) {}
