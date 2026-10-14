package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.qubership.integration.platform.engine.routes.support.SnapshotCollections.immutableMapOrEmpty;

public class SnapshotScenarioDriverDefinition {
    private static final String NODE_ID_PARAMETER = "nodeId";

    private final String provider;
    private final String sourceElementId;
    private final Map<String, Object> parameters;

    @JsonCreator
    public SnapshotScenarioDriverDefinition(
            @JsonProperty("provider") String provider,
            @JsonProperty("sourceElementId") String sourceElementId,
            @JsonProperty("parameters") Map<String, Object> parameters
    ) {
        this.provider = requireNonBlank(provider);
        this.sourceElementId = optionalNonBlank(sourceElementId);
        this.parameters = immutableMapOrEmpty(parameters);
    }

    public String getProvider() {
        return provider;
    }

    public String getSourceElementId() {
        return sourceElementId;
    }

    public Map<String, Object> getParameters() {
        return parameters;
    }

    public SnapshotScenarioDriverDefinition resolveNodeId(SnapshotDeployment deployment) {
        if (sourceElementId == null) {
            return this;
        }

        Object localNodeIdValue = parameters.get(NODE_ID_PARAMETER);
        String localNodeId = localNodeIdValue instanceof String string ? string : null;
        String resolvedNodeId = deployment.resolveNodeId(localNodeId, sourceElementId);
        Map<String, Object> resolvedParameters = new LinkedHashMap<>(parameters);
        resolvedParameters.put(NODE_ID_PARAMETER, resolvedNodeId);
        return new SnapshotScenarioDriverDefinition(provider, sourceElementId, resolvedParameters);
    }

    private static String requireNonBlank(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot scenario driver provider is missing.");
        }
        return value;
    }

    private static String optionalNonBlank(String value) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException("Snapshot scenario driver sourceElementId cannot be blank.");
        }
        return value;
    }

}
