package org.qubership.integration.platform.engine.camel.metadata;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.common.annotation.Identifier;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

@ApplicationScoped
public class MetadataConverter {
    @Inject
    @Identifier("jsonMapper")
    ObjectMapper objectMapper;

    public String toString(Metadata metadata) {
        try {
            return objectMapper.writeValueAsString(metadata);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize metadata", e);
        }
    }

    public Metadata toMetadata(String value) {
        try {
            return objectMapper.readValue(value, Metadata.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize metadata", e);
        }
    }
}
