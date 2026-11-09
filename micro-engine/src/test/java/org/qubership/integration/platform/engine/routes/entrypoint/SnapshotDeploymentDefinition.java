package org.qubership.integration.platform.engine.routes.entrypoint;

import java.util.List;

class SnapshotDeploymentDefinition {
    private String id;
    private String route;
    private String snapshotVariant;
    private List<String> dependsOn = List.of();
    private boolean isolatedContext;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRoute() {
        return route;
    }

    public void setRoute(String route) {
        this.route = route;
    }

    public String getSnapshotVariant() {
        return snapshotVariant;
    }

    public void setSnapshotVariant(String snapshotVariant) {
        this.snapshotVariant = snapshotVariant;
    }

    public List<String> getDependsOn() {
        return dependsOn;
    }

    public void setDependsOn(List<String> dependsOn) {
        this.dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
    }

    public boolean isIsolatedContext() {
        return isolatedContext;
    }

    public void setIsolatedContext(boolean isolatedContext) {
        this.isolatedContext = isolatedContext;
    }
}
