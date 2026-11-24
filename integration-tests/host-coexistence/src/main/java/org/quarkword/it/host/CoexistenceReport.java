package org.quarkword.it.host;

import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.fasterxml.jackson.databind.annotation.JsonNaming;
import java.util.List;

// The host mapper writes snake_case; this report keeps its field names.
@JsonNaming(PropertyNamingStrategies.LowerCamelCaseStrategy.class)
public record CoexistenceReport(
        List<String> hostScripts,
        List<String> quarkwordScripts,
        List<String> defaultUnitEntities,
        List<String> quarkwordUnitEntities,
        List<String> hostFlywayLocations,
        List<String> quarkwordFlywayLocations,
        boolean sameDatabase) {}
