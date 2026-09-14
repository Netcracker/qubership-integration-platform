package org.qubership.integration.platform.engine.routes.entrypoint.bundle;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLParser;

import java.util.Objects;

public final class SnapshotYamlMappers {
    private SnapshotYamlMappers() {
    }

    public static ObjectMapper create() {
        return configure(new YAMLMapper()
                .enable(YAMLParser.Feature.PARSE_BOOLEAN_LIKE_WORDS_AS_STRINGS));
    }

    public static ObjectMapper strictCopy(ObjectMapper objectMapper) {
        return configure(Objects.requireNonNull(objectMapper, "objectMapper").copy());
    }

    private static ObjectMapper configure(ObjectMapper objectMapper) {
        return objectMapper
                .enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }
}
