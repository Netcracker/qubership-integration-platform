package org.qubership.integration.platform.ai.plan.presentation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

/** Deterministic snapshot passed to the plan presentation agent. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PlanPresentationFacts(
    String userRequest,
    String chainName,
    String chainDescription,
    int nodeCount,
    int edgeCount,
    List<PlanPresentationNode> coreFlowNodes,
    List<PlanPresentationEdge> coreFlowEdges,
    List<PlanCompilerAddition> compilerAdditions,
    String selectedPatternId,
    String selectedPatternSummary,
    String decisionTraceSummary,
    Boolean validationPassed,
    String validationSummary,
    Boolean planCaptured,
    String planCaptureMessage,
    String lifecycleStatus,
    List<String> endpointFacts,
    List<String> branchFacts,
    List<String> scriptOutcomes,
    List<String> serviceBindings,
    List<String> negativeConstraints,
    List<String> skillOwnership) {

  public PlanPresentationFacts {
    coreFlowNodes = coreFlowNodes == null ? List.of() : List.copyOf(coreFlowNodes);
    coreFlowEdges = coreFlowEdges == null ? List.of() : List.copyOf(coreFlowEdges);
    compilerAdditions = compilerAdditions == null ? List.of() : List.copyOf(compilerAdditions);
    endpointFacts = endpointFacts == null ? List.of() : List.copyOf(endpointFacts);
    branchFacts = branchFacts == null ? List.of() : List.copyOf(branchFacts);
    scriptOutcomes = scriptOutcomes == null ? List.of() : List.copyOf(scriptOutcomes);
    serviceBindings = serviceBindings == null ? List.of() : List.copyOf(serviceBindings);
    negativeConstraints =
        negativeConstraints == null ? List.of() : List.copyOf(negativeConstraints);
    skillOwnership = skillOwnership == null ? List.of() : List.copyOf(skillOwnership);
  }
}
