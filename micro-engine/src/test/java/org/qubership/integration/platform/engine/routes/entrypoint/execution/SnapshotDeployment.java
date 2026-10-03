package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class SnapshotDeployment {
    private final String id;
    private final String routeSourceLocation;
    private final List<String> dependencyIds;
    private final Map<String, String> snapshotNodeIdsBySourceElementId;

    public SnapshotDeployment(
            String id,
            String routeSourceLocation,
            List<String> dependencyIds
    ) {
        this(id, routeSourceLocation, dependencyIds, Map.of());
    }

    public SnapshotDeployment(
            String id,
            String routeSourceLocation,
            List<String> dependencyIds,
            Map<String, String> snapshotNodeIdsBySourceElementId
    ) {
        this.id = requireNonBlank(id, "id");
        this.routeSourceLocation = requireNonBlank(routeSourceLocation, "routeSourceLocation");
        this.dependencyIds = immutableDependencyIds(this.id, dependencyIds);
        this.snapshotNodeIdsBySourceElementId = immutableNodeIds(
                this.id,
                snapshotNodeIdsBySourceElementId
        );
    }

    public String getId() {
        return id;
    }

    public String getRouteSourceLocation() {
        return routeSourceLocation;
    }

    public List<String> getDependencyIds() {
        return dependencyIds;
    }

    public String resolveNodeId(String localNodeId, String sourceElementId) {
        if (snapshotNodeIdsBySourceElementId.isEmpty()) {
            return requireNonBlank(localNodeId, "local node id");
        }
        if (sourceElementId == null || sourceElementId.isBlank()) {
            throw new IllegalArgumentException(
                    "Catalog snapshot deployment '" + id
                            + "' requires a sourceElementId to resolve node '" + localNodeId + "'."
            );
        }
        String snapshotNodeId = snapshotNodeIdsBySourceElementId.get(sourceElementId);
        if (snapshotNodeId == null) {
            throw new IllegalArgumentException(
                    "Catalog snapshot deployment '" + id + "' does not map source element '"
                            + sourceElementId + "'."
            );
        }
        return snapshotNodeId;
    }

    private static List<String> immutableDependencyIds(String deploymentId, List<String> dependencyIds) {
        requireNonNull(dependencyIds, "dependencyIds");
        Set<String> uniqueDependencyIds = new HashSet<>();
        for (String dependencyId : dependencyIds) {
            if (dependencyId == null || dependencyId.isBlank()) {
                throw new IllegalArgumentException(
                        "Snapshot deployment '" + deploymentId + "' contains a blank dependency id."
                );
            }
            if (!uniqueDependencyIds.add(dependencyId)) {
                throw new IllegalArgumentException(
                        "Snapshot deployment '" + deploymentId + "' contains duplicate dependency '"
                                + dependencyId + "'."
                );
            }
        }
        return List.copyOf(dependencyIds);
    }

    private static Map<String, String> immutableNodeIds(
            String deploymentId,
            Map<String, String> snapshotNodeIdsBySourceElementId
    ) {
        requireNonNull(snapshotNodeIdsBySourceElementId, "snapshotNodeIdsBySourceElementId");
        Map<String, String> nodeIds = new LinkedHashMap<>();
        Set<String> snapshotNodeIds = new HashSet<>();
        snapshotNodeIdsBySourceElementId.forEach((sourceElementId, snapshotNodeId) -> {
            if (sourceElementId == null || sourceElementId.isBlank()) {
                throw new IllegalArgumentException(
                        "Snapshot deployment '" + deploymentId + "' contains a blank source element id."
                );
            }
            if (snapshotNodeId == null || snapshotNodeId.isBlank()) {
                throw new IllegalArgumentException(
                        "Snapshot deployment '" + deploymentId + "' contains a blank snapshot node id."
                );
            }
            if (!snapshotNodeIds.add(snapshotNodeId)) {
                throw new IllegalArgumentException(
                        "Snapshot deployment '" + deploymentId + "' contains duplicate snapshot node id '"
                                + snapshotNodeId + "'."
                );
            }
            nodeIds.put(sourceElementId, snapshotNodeId);
        });
        return Collections.unmodifiableMap(nodeIds);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot deployment " + fieldName + " is missing.");
        }
        return value;
    }
}
