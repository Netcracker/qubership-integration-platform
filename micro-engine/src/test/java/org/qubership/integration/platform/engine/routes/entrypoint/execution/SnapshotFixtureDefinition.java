package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SnapshotFixtureDefinition {
    private final String id;
    private final String provider;
    private final String deploymentId;
    private final String nodeId;
    private final String sourceElementId;

    @JsonCreator
    public SnapshotFixtureDefinition(
            @JsonProperty("id") String id,
            @JsonProperty("provider") String provider,
            @JsonProperty("deployment") String deploymentId,
            @JsonProperty("nodeId") String nodeId,
            @JsonProperty("sourceElementId") String sourceElementId
    ) {
        this.id = requireNonBlank(id, "id");
        this.provider = requireNonBlank(provider, "provider");
        this.deploymentId = optionalNonBlank(deploymentId, "deployment");
        this.nodeId = optionalNonBlank(nodeId, "nodeId");
        this.sourceElementId = optionalNonBlank(sourceElementId, "sourceElementId");
    }

    public String getId() {
        return id;
    }

    public String getProvider() {
        return provider;
    }

    public String getDeploymentId() {
        return deploymentId;
    }

    public String getNodeId() {
        return nodeId;
    }

    public String getSourceElementId() {
        return sourceElementId;
    }

    SnapshotFixtureDefinition withDefaultDeployment(String defaultDeploymentId) {
        if (deploymentId != null) {
            return this;
        }
        return new SnapshotFixtureDefinition(id, provider, defaultDeploymentId, nodeId, sourceElementId);
    }

    SnapshotFixtureDefinition withNodeId(String resolvedNodeId) {
        return new SnapshotFixtureDefinition(id, provider, deploymentId, resolvedNodeId, sourceElementId);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot fixture definition " + fieldName + " is missing.");
        }
        return value;
    }

    private static String optionalNonBlank(String value, String fieldName) {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException("Snapshot fixture definition " + fieldName + " cannot be blank.");
        }
        return value;
    }
}
