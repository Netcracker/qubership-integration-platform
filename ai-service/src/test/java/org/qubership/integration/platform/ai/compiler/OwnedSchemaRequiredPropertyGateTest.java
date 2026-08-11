package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

class OwnedSchemaRequiredPropertyGateTest {

  private static final Function<String, Set<String>> QUARTZ_REQUIRED =
      type -> "quartz-scheduler".equals(type) ? Set.of("cron") : Set.of();

  private static final GraphPatchOwnershipPolicy QUARTZ_OWNERSHIP =
      new GraphPatchOwnershipPolicy(
          false,
          false,
          Set.of(),
          Set.of(),
          Map.of("quartz-scheduler", Set.of("cron", "deleteJob")));

  @Test
  void emptyGraphWithMissingOwnedCronReportsGap() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "c"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1", "quartz-scheduler", "Hourly", null, null, List.of())),
            List.of());

    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(graph, QUARTZ_OWNERSHIP, QUARTZ_REQUIRED);

    assertEquals(1, gaps.size());
    assertEquals("quartz-scheduler-1", gaps.getFirst().nodeId());
    assertEquals(List.of("cron"), gaps.getFirst().missingPropertyKeys());
  }

  @Test
  void presentCronAllowsEmptyPatch() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "c"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1",
                    "quartz-scheduler",
                    "Hourly",
                    null,
                    null,
                    List.of(new PlanProperty("cron", "0 0 * * * ?")))),
            List.of());

    assertTrue(
        OwnedSchemaRequiredPropertyGate.findGaps(graph, QUARTZ_OWNERSHIP, QUARTZ_REQUIRED)
            .isEmpty());
  }

  @Test
  void incompleteNonEmptyOwnedNodeWithDeleteJobOnlyStillReportsMissingCron() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "c"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1",
                    "quartz-scheduler",
                    "Hourly",
                    null,
                    null,
                    List.of(new PlanProperty("deleteJob", "false")))),
            List.of());

    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(graph, QUARTZ_OWNERSHIP, QUARTZ_REQUIRED);

    assertEquals(1, gaps.size());
    assertEquals("quartz-scheduler-1", gaps.getFirst().nodeId());
    assertEquals(List.of("cron"), gaps.getFirst().missingPropertyKeys());
  }

  @Test
  void placeholderSentinelCronStillReportsMissing() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "c"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1",
                    "quartz-scheduler",
                    "Hourly",
                    null,
                    null,
                    List.of(
                        new PlanProperty(
                            "cron", OwnedSchemaRequiredPropertyGate.PLACEHOLDER_VALUE)))),
            List.of());

    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(graph, QUARTZ_OWNERSHIP, QUARTZ_REQUIRED);

    assertEquals(1, gaps.size());
    assertEquals(List.of("cron"), gaps.getFirst().missingPropertyKeys());
  }

  @Test
  void angleBracketPlaceholderStillReportsMissing() {
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "c"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1",
                    "quartz-scheduler",
                    "Hourly",
                    null,
                    null,
                    List.of(new PlanProperty("cron", "<your-cron-expression>")))),
            List.of());

    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(graph, QUARTZ_OWNERSHIP, QUARTZ_REQUIRED);

    assertEquals(1, gaps.size());
    assertEquals(List.of("cron"), gaps.getFirst().missingPropertyKeys());
  }

  @Test
  void doesNotReportUnownedRequiredKeys() {
    GraphPatchOwnershipPolicy noOwnership = GraphPatchOwnershipPolicy.denyAll();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "c"),
            List.of(
                new ChainPlanNode(
                    "quartz-scheduler-1", "quartz-scheduler", "Hourly", null, null, List.of())),
            List.of());

    assertTrue(
        OwnedSchemaRequiredPropertyGate.findGaps(graph, noOwnership, QUARTZ_REQUIRED).isEmpty());
  }

  @Test
  void messageListsNodeAndFieldsWithEmptyValueShapeNotCopyableSentinel() {
    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        List.of(
            new OwnedSchemaRequiredPropertyGate.Gap(
                "quartz-scheduler-1", "quartz-scheduler", List.of("cron")));
    String message =
        OwnedSchemaRequiredPropertyGate.formatCorrectableMessage(
            "cip-quartz-scheduler-generator", gaps);
    assertTrue(message.contains("quartz-scheduler-1"));
    assertTrue(message.contains("cron"));
    assertTrue(message.contains("propertyPatches") || message.contains("ADD"));
    assertTrue(message.contains("\"value\": \"\"") || message.contains("\"value\":\"\""));
    assertFalse(message.contains(OwnedSchemaRequiredPropertyGate.PLACEHOLDER_VALUE));
    assertFalse(message.contains(OwnedSchemaRequiredPropertyGate.PLACEHOLDER_CRON));
    assertTrue(message.contains("do not use placeholder tokens"));
    assertTrue(!message.matches("(?s).*0 \\*/5.*") && !message.contains("0 0 * * * ?"));
  }
}
