package org.quarkpress.testing.wporacle;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.json.JsonMapper;

/**
 * The JSON mapper for oracle files and case files. Limits are raised: a PHP value nested a few hundred levels
 * becomes a typed tree three times as deep, and strings can be whole documents.
 */
public final class OracleJson {

    public static final JsonMapper MAPPER = JsonMapper.builder(JsonFactory.builder()
                    .streamReadConstraints(StreamReadConstraints.builder()
                            .maxNestingDepth(100_000)
                            .maxStringLength(Integer.MAX_VALUE)
                            .maxNumberLength(Integer.MAX_VALUE)
                            .maxNameLength(Integer.MAX_VALUE)
                            .build())
                    .streamWriteConstraints(StreamWriteConstraints.builder()
                            .maxNestingDepth(100_000)
                            .build())
                    .build())
            .build();

    private OracleJson() {}
}
