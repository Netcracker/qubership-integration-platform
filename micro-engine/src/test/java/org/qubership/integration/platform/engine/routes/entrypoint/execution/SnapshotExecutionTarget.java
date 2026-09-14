package org.qubership.integration.platform.engine.routes.entrypoint.execution;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

public class SnapshotExecutionTarget {
    private final String id;
    private final String subjectDeploymentId;
    private final SnapshotDeployment subjectDeployment;
    private final List<SnapshotDeployment> deployments;
    private final List<SnapshotResourceDefinition> resources;
    private final List<SnapshotFixtureDefinition> fixtures;
    private final List<SnapshotExecutionScenario> scenarios;

    public SnapshotExecutionTarget(
            String id,
            String subjectDeploymentId,
            List<SnapshotDeployment> deployments,
            List<SnapshotResourceDefinition> resources,
            List<SnapshotFixtureDefinition> fixtures,
            List<SnapshotExecutionScenario> scenarios
    ) {
        this.id = requireNonBlank(id, "id");
        this.subjectDeploymentId = requireNonBlank(subjectDeploymentId, "subjectDeploymentId");
        this.deployments = validateAndSortDeployments(this.id, deployments);
        this.subjectDeployment = this.deployments.stream()
                .filter(deployment -> deployment.getId().equals(this.subjectDeploymentId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Snapshot execution target '" + this.id + "' references unknown subject deployment '"
                                + this.subjectDeploymentId + "'."
                ));
        this.resources = validateResources(this.id, resources);
        this.fixtures = validateFixtures(this.id, this.subjectDeploymentId, this.deployments, fixtures);
        this.scenarios = List.copyOf(requireNonNull(scenarios, "scenarios"));
        validateFixtureInteractions(this.id, this.fixtures, this.scenarios);
    }

    public String getId() {
        return id;
    }

    public String getSubjectDeploymentId() {
        return subjectDeploymentId;
    }

    public SnapshotDeployment getSubjectDeployment() {
        return subjectDeployment;
    }

    public List<SnapshotDeployment> getDeployments() {
        return deployments;
    }

    public List<SnapshotResourceDefinition> getResources() {
        return resources;
    }

    public List<SnapshotFixtureDefinition> getFixtures() {
        return fixtures;
    }

    public List<SnapshotExecutionScenario> getScenarios() {
        return scenarios;
    }

    private static List<SnapshotResourceDefinition> validateResources(
            String targetId,
            List<SnapshotResourceDefinition> resources
    ) {
        requireNonNull(resources, "resources");
        Set<String> resourceTargets = new HashSet<>();
        for (SnapshotResourceDefinition resource : resources) {
            if (resource == null) {
                throw new IllegalArgumentException(
                        "Snapshot execution target '" + targetId + "' contains a null resource."
                );
            }
            if (!resourceTargets.add(resource.getTarget())) {
                throw new IllegalArgumentException(
                        "Snapshot execution target '" + targetId + "' contains duplicate resource target '"
                                + resource.getTarget() + "'."
                );
            }
        }
        return List.copyOf(resources);
    }

    private static List<SnapshotFixtureDefinition> validateFixtures(
            String targetId,
            String subjectDeploymentId,
            List<SnapshotDeployment> deployments,
            List<SnapshotFixtureDefinition> fixtures
    ) {
        requireNonNull(fixtures, "fixtures");
        Map<String, SnapshotDeployment> deploymentsById = new LinkedHashMap<>();
        deployments.forEach(deployment -> deploymentsById.put(deployment.getId(), deployment));
        Set<String> fixtureIds = new HashSet<>();
        Set<String> fixtureTargets = new HashSet<>();
        List<SnapshotFixtureDefinition> resolvedFixtures = new ArrayList<>(fixtures.size());
        for (SnapshotFixtureDefinition fixture : fixtures) {
            if (fixture == null) {
                throw new IllegalArgumentException(
                        "Snapshot execution target '" + targetId + "' contains a null fixture."
                );
            }
            SnapshotFixtureDefinition resolvedFixture = fixture.withDefaultDeployment(subjectDeploymentId);
            if (!fixtureIds.add(resolvedFixture.getId())) {
                throw new IllegalArgumentException(
                        "Snapshot execution target '" + targetId + "' contains duplicate fixture id '"
                                + resolvedFixture.getId() + "'."
                );
            }
            SnapshotDeployment fixtureDeployment = deploymentsById.get(resolvedFixture.getDeploymentId());
            if (fixtureDeployment == null) {
                throw new IllegalArgumentException(
                        "Snapshot execution target '" + targetId + "' fixture '" + resolvedFixture.getId()
                                + "' references unknown deployment '" + resolvedFixture.getDeploymentId() + "'."
                );
            }
            if (resolvedFixture.getNodeId() != null || resolvedFixture.getSourceElementId() != null) {
                resolvedFixture = resolvedFixture.withNodeId(fixtureDeployment.resolveNodeId(
                        resolvedFixture.getNodeId(),
                        resolvedFixture.getSourceElementId()
                ));
                String fixtureTarget = resolvedFixture.getDeploymentId() + '\0' + resolvedFixture.getNodeId();
                if (!fixtureTargets.add(fixtureTarget)) {
                    throw new IllegalArgumentException(
                            "Snapshot execution target '" + targetId
                                    + "' contains more than one fixture for deployment '"
                                    + resolvedFixture.getDeploymentId() + "' node '"
                                    + resolvedFixture.getNodeId() + "'."
                    );
                }
            }
            resolvedFixtures.add(resolvedFixture);
        }
        return List.copyOf(resolvedFixtures);
    }

    private static void validateFixtureInteractions(
            String targetId,
            List<SnapshotFixtureDefinition> fixtures,
            List<SnapshotExecutionScenario> scenarios
    ) {
        Set<String> fixtureIds = new HashSet<>();
        fixtures.forEach(fixture -> fixtureIds.add(fixture.getId()));
        for (SnapshotExecutionScenario scenario : scenarios) {
            if (scenario == null) {
                throw new IllegalArgumentException(
                        "Snapshot execution target '" + targetId + "' contains a null scenario."
                );
            }
            for (SnapshotScenarioInvocation invocation : scenario.getInvocations()) {
                Set<String> interactionFixtureIds = new HashSet<>();
                for (SnapshotFixtureInteraction interaction : invocation.getInteractions()) {
                    if (interaction == null) {
                        throw new IllegalArgumentException(
                                "Snapshot scenario '" + scenario.getId() + "' invocation '" + invocation.getId()
                                        + "' contains a null fixture interaction."
                        );
                    }
                    if (!fixtureIds.contains(interaction.getFixtureId())) {
                        throw new IllegalArgumentException(
                                "Snapshot scenario '" + scenario.getId() + "' invocation '" + invocation.getId()
                                        + "' references unknown fixture '" + interaction.getFixtureId() + "'."
                        );
                    }
                    if (!interactionFixtureIds.add(interaction.getFixtureId())) {
                        throw new IllegalArgumentException(
                                "Snapshot scenario '" + scenario.getId() + "' invocation '" + invocation.getId()
                                        + "' contains duplicate interaction for fixture '"
                                        + interaction.getFixtureId() + "'."
                        );
                    }
                }
                for (String fixtureId : fixtureIds) {
                    if (!interactionFixtureIds.contains(fixtureId)) {
                        throw new IllegalArgumentException(
                                "Snapshot scenario '" + scenario.getId() + "' invocation '" + invocation.getId()
                                        + "' does not define an interaction for fixture '" + fixtureId + "'."
                        );
                    }
                }
            }
        }
    }

    private static List<SnapshotDeployment> validateAndSortDeployments(
            String targetId,
            List<SnapshotDeployment> deployments
    ) {
        requireNonNull(deployments, "deployments");
        if (deployments.isEmpty()) {
            throw new IllegalArgumentException(
                    "Snapshot execution target '" + targetId + "' does not contain any deployments."
            );
        }

        Map<String, SnapshotDeployment> deploymentsById = new LinkedHashMap<>();
        for (SnapshotDeployment deployment : deployments) {
            if (deployment == null) {
                throw new IllegalArgumentException(
                        "Snapshot execution target '" + targetId + "' contains a null deployment."
                );
            }
            if (deploymentsById.putIfAbsent(deployment.getId(), deployment) != null) {
                throw new IllegalArgumentException(
                        "Snapshot execution target '" + targetId + "' contains duplicate deployment id '"
                                + deployment.getId() + "'."
                );
            }
        }

        validateDependencies(targetId, deploymentsById);
        List<SnapshotDeployment> sortedDeployments = new ArrayList<>(deployments.size());
        Set<String> visitingDeploymentIds = new HashSet<>();
        Set<String> visitedDeploymentIds = new HashSet<>();
        for (SnapshotDeployment deployment : deployments) {
            addWithDependencies(
                    targetId,
                    deployment,
                    deploymentsById,
                    visitingDeploymentIds,
                    visitedDeploymentIds,
                    sortedDeployments
            );
        }
        return List.copyOf(sortedDeployments);
    }

    private static void validateDependencies(
            String targetId,
            Map<String, SnapshotDeployment> deploymentsById
    ) {
        for (SnapshotDeployment deployment : deploymentsById.values()) {
            for (String dependencyId : deployment.getDependencyIds()) {
                if (!deploymentsById.containsKey(dependencyId)) {
                    throw new IllegalArgumentException(
                            "Snapshot execution target '" + targetId + "' deployment '" + deployment.getId()
                                    + "' references unknown dependency '" + dependencyId + "'."
                    );
                }
            }
        }
    }

    private static void addWithDependencies(
            String targetId,
            SnapshotDeployment deployment,
            Map<String, SnapshotDeployment> deploymentsById,
            Set<String> visitingDeploymentIds,
            Set<String> visitedDeploymentIds,
            List<SnapshotDeployment> sortedDeployments
    ) {
        if (visitedDeploymentIds.contains(deployment.getId())) {
            return;
        }
        if (!visitingDeploymentIds.add(deployment.getId())) {
            throw new IllegalArgumentException(
                    "Snapshot execution target '" + targetId
                            + "' contains a deployment dependency cycle involving '" + deployment.getId() + "'."
            );
        }

        for (String dependencyId : deployment.getDependencyIds()) {
            addWithDependencies(
                    targetId,
                    deploymentsById.get(dependencyId),
                    deploymentsById,
                    visitingDeploymentIds,
                    visitedDeploymentIds,
                    sortedDeployments
            );
        }
        visitingDeploymentIds.remove(deployment.getId());
        visitedDeploymentIds.add(deployment.getId());
        sortedDeployments.add(deployment);
    }

    private static String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Snapshot execution target " + fieldName + " is missing.");
        }
        return value;
    }
}
