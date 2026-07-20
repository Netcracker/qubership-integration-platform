package org.qubership.integration.platform.parsers.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.core.util.Json;
import io.swagger.v3.core.util.Json31;
import io.swagger.v3.oas.models.SpecVersion;

/**
 * Picks the swagger-core mapper that matches the parsed specification version.
 *
 * <p>The 3.1 mapper serializes {@code Schema.types} the way OpenAPI 3.1 expects: a scalar
 * {@code type} for a single type and an array for several. The 3.0 mapper reads only the
 * legacy scalar {@code type} field and silently drops 3.1 type information. Choosing the
 * mapper per version removes the need for the process-wide {@code bind-type} flag.
 *
 * <p>Both mappers are cached singletons inside swagger-core, so resolution is cheap and the
 * returned instance is safe to share across imports.
 */
public class OpenApiMapperResolver {

    /**
     * Returns the mapper for the given spec version. A null or 3.0 version maps to the
     * legacy mapper, so callers never need a null check.
     */
    public ObjectMapper forVersion(SpecVersion specVersion) {
        return specVersion == SpecVersion.V31 ? Json31.mapper() : Json.mapper();
    }
}
