package org.quarkpress.lib.wxr;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** What has been read of one entity so far: leaf values by canonical name, item terms, meta, nested comments. */
final class Fields {

    final Map<String, String> values = new LinkedHashMap<>();
    final List<WxrItemTerm> terms = new ArrayList<>();
    final List<WxrMeta> meta = new ArrayList<>();
    final List<Fields> comments = new ArrayList<>();

    String get(String name) {
        return values.get(name);
    }

    Long integer(String name) {
        return Integers.parse(values.get(name));
    }
}
