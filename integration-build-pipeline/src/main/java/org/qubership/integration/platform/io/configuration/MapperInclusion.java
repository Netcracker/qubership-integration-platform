package org.qubership.integration.platform.io.configuration;

import com.fasterxml.jackson.annotation.JsonInclude;

public final class MapperInclusion {

    private MapperInclusion() {
    }

    public static JsonInclude.Value nonNull(JsonInclude.Value inclusion) {
        return inclusion
                .withValueInclusion(JsonInclude.Include.NON_NULL)
                .withContentInclusion(JsonInclude.Include.NON_NULL);
    }

    public static JsonInclude.Value nonEmpty(JsonInclude.Value inclusion) {
        return inclusion
                .withValueInclusion(JsonInclude.Include.NON_EMPTY)
                .withContentInclusion(JsonInclude.Include.NON_EMPTY);
    }
}
