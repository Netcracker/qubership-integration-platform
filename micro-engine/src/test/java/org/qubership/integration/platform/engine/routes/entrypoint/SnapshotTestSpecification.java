package org.qubership.integration.platform.engine.routes.entrypoint;

import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotExecutionScenario;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotFixtureDefinition;
import org.qubership.integration.platform.engine.routes.entrypoint.execution.SnapshotResourceDefinition;

import java.util.List;

class SnapshotTestSpecification {
    private String subject;
    private List<SnapshotDeploymentDefinition> deployments = List.of();
    private List<SnapshotResourceDefinition> resources = List.of();
    private List<SnapshotFixtureDefinition> fixtures = List.of();
    private List<SnapshotExecutionScenario> scenarios = List.of();

    public String getSubject() {
        return subject;
    }

    public void setSubject(String subject) {
        this.subject = subject;
    }

    public List<SnapshotDeploymentDefinition> getDeployments() {
        return deployments;
    }

    public void setDeployments(List<SnapshotDeploymentDefinition> deployments) {
        this.deployments = deployments == null ? List.of() : List.copyOf(deployments);
    }

    public List<SnapshotResourceDefinition> getResources() {
        return resources;
    }

    public void setResources(List<SnapshotResourceDefinition> resources) {
        this.resources = resources == null ? List.of() : List.copyOf(resources);
    }

    public List<SnapshotFixtureDefinition> getFixtures() {
        return fixtures;
    }

    public void setFixtures(List<SnapshotFixtureDefinition> fixtures) {
        this.fixtures = fixtures == null ? List.of() : List.copyOf(fixtures);
    }

    public List<SnapshotExecutionScenario> getScenarios() {
        return scenarios;
    }

    public void setScenarios(List<SnapshotExecutionScenario> scenarios) {
        this.scenarios = scenarios == null ? List.of() : List.copyOf(scenarios);
    }
}
