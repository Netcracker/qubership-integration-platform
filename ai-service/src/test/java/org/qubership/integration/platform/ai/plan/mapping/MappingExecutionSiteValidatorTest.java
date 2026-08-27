package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationIssue;
import org.qubership.integration.platform.ai.qipknowledge.validation.ValidationSeverity;

class MappingExecutionSiteValidatorTest {

  @Test
  void rejectsMissingDuplicateUnreachableAndUnconfiguredSites() {
    RequirementBrief brief = approvedBrief();

    List<ValidationIssue> missing =
        MappingExecutionSiteValidator.validate(passThroughGraph(), brief.mappingIntents());
    assertTrue(hasIssue(missing, "map-init", "cip-structure-generator"));

    List<ValidationIssue> duplicate =
        MappingExecutionSiteValidator.validate(duplicateSitesGraph(), brief.mappingIntents());
    assertTrue(hasIssue(duplicate, "map-init", "cip-structure-generator"));

    List<ValidationIssue> unreachable =
        MappingExecutionSiteValidator.validate(unreachableSiteGraph(), brief.mappingIntents());
    assertTrue(hasIssue(unreachable, "transform-map-init", "cip-structure-generator"));

    List<ValidationIssue> unconfigured =
        MappingExecutionSiteValidator.validate(unconfiguredShellGraph(), brief.mappingIntents());
    assertTrue(hasIssue(unconfigured, "transform-map-init", "cip-transformation-generator"));
  }

  private static boolean hasIssue(
      List<ValidationIssue> issues, String needle, String ownerCapabilityId) {
    return issues.stream()
        .anyMatch(
            issue ->
                issue.severity() == ValidationSeverity.BLOCKER
                    && issue.ownerCapabilityId().equals(ownerCapabilityId)
                    && issue.message().contains(needle));
  }

  private static RequirementBrief approvedBrief() {
    return new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map OM output to Salesforce request",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(
            List.of(
                new MappingIntent(
                    "map-init",
                    "trigger-1",
                    MappingPort.OUTPUT,
                    "call-1",
                    MappingPort.REQUEST,
                    List.of(
                        new MappingIntentRule(
                            "$.userId", "$.personId", null, MappingRuleStatus.USER_DEFINED)))));
  }

  private static ChainPlanGraph passThroughGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "trigger-1", "call-1", null)));
  }

  private static ChainPlanGraph duplicateSitesGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            mapper("transform-map-init", "map-init", true),
            mapper("transform-map-init-dup", "map-init", true),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger-1", "transform-map-init", null),
            new ChainPlanEdge("e2", "transform-map-init", "call-1", null)));
  }

  private static ChainPlanGraph unreachableSiteGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            mapper("transform-map-init", "map-init", true),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(new ChainPlanEdge("e1", "trigger-1", "call-1", null)));
  }

  private static ChainPlanGraph unconfiguredShellGraph() {
    return new ChainPlanGraph(
        "1.0",
        new ChainSection("orders", "Orders"),
        List.of(
            new ChainPlanNode("trigger-1", "http-trigger", "Trigger", null, null, List.of()),
            mapper("transform-map-init", "map-init", false),
            new ChainPlanNode("call-1", "service-call", "Call", null, null, List.of())),
        List.of(
            new ChainPlanEdge("e1", "trigger-1", "transform-map-init", null),
            new ChainPlanEdge("e2", "transform-map-init", "call-1", null)));
  }

  private static ChainPlanNode mapper(String nodeId, String mappingIntentId, boolean configured) {
    List<PlanProperty> properties =
        configured
            ? List.of(
                new PlanProperty(
                    MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, mappingIntentId),
                new PlanProperty(
                    MappingExecutionSite.MAPPING_DESCRIPTION_PROPERTY,
                    "[{\"sourcePath\":\"$.userId\",\"targetPath\":\"$.personId\"}]"))
            : List.of(
                new PlanProperty(
                    MappingExecutionSite.MAPPING_INTENT_ID_PROPERTY, mappingIntentId));
    return new ChainPlanNode(nodeId, "mapper-2", "Map", null, null, properties);
  }
}
