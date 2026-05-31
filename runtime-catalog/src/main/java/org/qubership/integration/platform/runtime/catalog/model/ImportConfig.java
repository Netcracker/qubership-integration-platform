package org.qubership.integration.platform.runtime.catalog.model;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.util.Map;

@Getter
@AllArgsConstructor
public class ImportConfig {

    private final Map<String, JsonNode> chains;
    private final Map<String, JsonNode> services;
    private final Map<String, JsonNode> specificationGroups;
    private final Map<String, JsonNode> specifications;
    private final Map<String, JsonNode> commonVariables;
    private final Map<String, JsonNode> contextServices;
    private final Map<String, byte[]> resources;

    public boolean isEmpty() {
        return chains.isEmpty()
                && services.isEmpty()
                && specificationGroups.isEmpty()
                && specifications.isEmpty()
                && commonVariables.isEmpty()
                && contextServices.isEmpty();
    }
}
