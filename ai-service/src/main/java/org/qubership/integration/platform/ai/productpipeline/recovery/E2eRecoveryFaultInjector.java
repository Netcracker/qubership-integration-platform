package org.qubership.integration.platform.ai.productpipeline.recovery;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.qubership.integration.platform.ai.configuration.AppConfig;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCauseCode;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignExecutionPlan;

/** Injects deterministic, chain-scoped failures for live recovery tests. */
@ApplicationScoped
public class E2eRecoveryFaultInjector {

  public static final String DEFAULT_PLAN = "design-execution=MISSING_REQUIRED_PROPERTY:2";

  private final String chainNamePrefix;
  private final Map<String, FaultSpec> faultsByStage;
  private final ConcurrentHashMap<String, Integer> injections = new ConcurrentHashMap<>();

  @Inject
  public E2eRecoveryFaultInjector(AppConfig appConfig) {
    this(
        appConfig.e2e().recoveryFaultChainPrefix().orElse(""),
        appConfig.e2e().recoveryFaultPlan().orElse(DEFAULT_PLAN));
  }

  public E2eRecoveryFaultInjector(String chainNamePrefix, String plan) {
    this.chainNamePrefix = chainNamePrefix == null ? "" : chainNamePrefix.trim();
    this.faultsByStage = parse(plan);
  }

  public Optional<RecoveryCauseCode> next(
      String runId, String chainName, String stageId) {
    FaultSpec fault = faultsByStage.get(stageId);
    if (chainNamePrefix.isBlank()
        || chainName == null
        || !chainName.startsWith(chainNamePrefix)
        || fault == null) {
      return Optional.empty();
    }
    int count = injections.merge(runId + '\0' + stageId, 1, Integer::sum);
    return count <= fault.maxInjections() ? Optional.of(fault.causeCode()) : Optional.empty();
  }

  /** Corrupts the candidate before approval so execution rejects an actual planning artifact. */
  public DesignExecutionPlan corruptDesignPlan(String runId, String chainName, DesignExecutionPlan plan) {
    if (next(runId, chainName, "design-plan-validation").isEmpty()) {
      return plan;
    }
    var steps = new ArrayList<>(plan.steps());
    var step = steps.getFirst();
    var owners = new ArrayList<>(step.owningSkillIds());
    owners.add("E2E recovery fault: unknown planner owner");
    steps.set(0, new DesignExecutionPlan.Step(
        step.stepId(), step.reportOrdinal(), step.reportText(), step.ownerKind(), owners,
        step.toolOperationRefs(), step.participantRefs(), step.operationQueryRefs(), step.dependsOn(),
        step.requiredArtifactTypes(), step.producedArtifactTypes(), step.mappingIntentId(), step.serviceCallId(),
        step.regionId(), step.claims()));
    return new DesignExecutionPlan(
        plan.schemaVersion(), plan.semanticRevisionId(), plan.designAuthority(), plan.designInputRef(),
        plan.designInputHash(), plan.apiRelease(), plan.bindingResolutionPolicy(), steps,
        plan.sourceReportRef(), plan.sourceReportHash(), plan.pinnedSkillHashes(), plan.pinnedAddonHashes(),
        plan.compilerCatalogHash(), plan.bindingResolutionPolicyHash(),
        plan.sourceContractId(), plan.sourceContractHash());
  }

  /** Corrupts a real graph value so the live test exercises validation and model repair. */
  public ChainPlanGraph corruptHttpUrl(String runId, ChainPlanGraph graph) {
    if (graph == null || !faultsByStage.containsKey("http-url-validation")) {
      return graph;
    }
    for (var node : graph.nodes()) {
      if (!"http-trigger".equals(node.type())) {
        continue;
      }
      for (var property : node.properties()) {
        if ("contextPath".equals(property.key()) && property.value() != null && !property.value().isBlank()) {
          if (next(runId, graph.chain().name(), "http-url-validation").isPresent()) {
            return graph.withNodeProperty(node.nodeId(), "contextPath", "\"" + property.value() + "\"");
          }
          return graph;
        }
      }
    }
    return graph;
  }

  private static Map<String, FaultSpec> parse(String plan) {
    if (plan == null || plan.isBlank()) {
      return Map.of();
    }
    Map<String, FaultSpec> parsed = new HashMap<>();
    for (String entry : plan.split(",")) {
      String[] assignment = entry.trim().split("=", 2);
      String[] specification = assignment.length == 2 ? assignment[1].split(":", 2) : new String[0];
      if (assignment.length != 2 || assignment[0].isBlank() || specification.length != 2) {
        throw new IllegalArgumentException("Invalid E2E recovery fault entry: " + entry);
      }
      RecoveryCauseCode causeCode = RecoveryCauseCode.valueOf(specification[0].trim());
      int maxInjections = Integer.parseInt(specification[1].trim());
      if (maxInjections < 1) {
        throw new IllegalArgumentException("E2E recovery fault count must be positive: " + entry);
      }
      parsed.put(assignment[0].trim(), new FaultSpec(causeCode, maxInjections));
    }
    return Map.copyOf(parsed);
  }

  private record FaultSpec(RecoveryCauseCode causeCode, int maxInjections) {}
}
