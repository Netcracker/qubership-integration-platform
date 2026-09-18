package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

class OwnedSchemaRequiredPropertyGateTest {

  private final DeterministicElementSchemaService schemaService =
      DeterministicElementSchemaService.createForUnitTests(new ObjectMapper());

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
  void maasRabbitSenderReportsMissingOwnedVhostClassifier() {
    GraphPatchOwnershipPolicy ownership =
        new GraphPatchOwnershipPolicy(
            false,
            false,
            Set.of(),
            Set.of(),
            Map.of(
                "rabbitmq-sender-2",
                Set.of("exchange", "routingKey", "connectionSourceType", "vhostClassifierName")));
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "c"),
            List.of(
                new ChainPlanNode(
                    "rabbitmq-sender",
                    "rabbitmq-sender-2",
                    "Send",
                    null,
                    null,
                    List.of(
                        new PlanProperty("connectionSourceType", "maas"),
                        new PlanProperty("exchange", "cip-auto-tests-exchange")))),
            List.of());
    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(
            graph,
            ownership,
            (ChainPlanNode node) ->
                Set.of("connectionSourceType", "exchange", "vhostClassifierName"));
    assertEquals(List.of("vhostClassifierName"), gaps.getFirst().missingPropertyKeys());
  }

  @Test
  void routingKeyAbsenceIsNotAGateGap() {
    GraphPatchOwnershipPolicy ownership =
        new GraphPatchOwnershipPolicy(
            false,
            false,
            Set.of(),
            Set.of(),
            Map.of(
                "rabbitmq-sender-2",
                Set.of("exchange", "routingKey", "connectionSourceType", "vhostClassifierName")));
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("c", "c"),
            List.of(
                new ChainPlanNode(
                    "rabbitmq-sender",
                    "rabbitmq-sender-2",
                    "Send",
                    null,
                    null,
                    List.of(
                        new PlanProperty("connectionSourceType", "maas"),
                        new PlanProperty("exchange", "ex"),
                        new PlanProperty("vhostClassifierName", "cip-auto-tests")))),
            List.of());
    assertTrue(
        OwnedSchemaRequiredPropertyGate.findGaps(
                graph,
                ownership,
                (ChainPlanNode node) ->
                    Set.of("connectionSourceType", "exchange", "vhostClassifierName"))
            .isEmpty());
  }

  @Test
  void manualKafkaSenderMissingBrokersReportsGap() {
    GraphPatchOwnershipPolicy ownership = kafkaSenderOwnership();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("publish", "Publish"),
            List.of(
                new ChainPlanNode(
                    "kafka-1",
                    "kafka-sender-2",
                    "Publish event",
                    null,
                    null,
                    List.of(
                        new PlanProperty("connectionSourceType", "manual"),
                        new PlanProperty("securityProtocol", "PLAINTEXT"),
                        new PlanProperty("saslMechanism", "GSSAPI")))),
            List.of());

    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(graph, ownership, schemaRequiredKeys());

    assertEquals(1, gaps.size());
    assertEquals("kafka-1", gaps.getFirst().nodeId());
    assertTrue(gaps.getFirst().missingPropertyKeys().contains("brokers"));
  }

  @Test
  void maasKafkaSenderWithClassifierHasNoGaps() {
    GraphPatchOwnershipPolicy ownership = kafkaSenderOwnership();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("publish", "Publish"),
            List.of(
                new ChainPlanNode(
                    "kafka-1",
                    "kafka-sender-2",
                    "Publish event",
                    null,
                    null,
                    List.of(
                        new PlanProperty("connectionSourceType", "maas"),
                        new PlanProperty("topicsClassifierName", "cip-auto-tests-topic1"),
                        new PlanProperty("maasClassifierTenantEnabled", "false"),
                        new PlanProperty(
                            "keySerializer",
                            "org.apache.kafka.common.serialization.StringSerializer"),
                        new PlanProperty(
                            "valueSerializer",
                            "org.apache.kafka.common.serialization.StringSerializer")))),
            List.of());

    assertTrue(
        OwnedSchemaRequiredPropertyGate.findGaps(graph, ownership, schemaRequiredKeys())
            .isEmpty());
  }

  @Test
  void maasKafkaSenderRequiresTenantIdWhenTenantIsEnabled() {
    GraphPatchOwnershipPolicy ownership = kafkaSenderOwnership();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("publish", "Publish"),
            List.of(
                new ChainPlanNode(
                    "kafka-1",
                    "kafka-sender-2",
                    "Publish event",
                    null,
                    null,
                    List.of(
                        new PlanProperty("connectionSourceType", "maas"),
                        new PlanProperty("topicsClassifierName", "cip-auto-tests-topic1"),
                        new PlanProperty("maasClassifierTenantEnabled", "true")))),
            List.of());

    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(graph, ownership, schemaRequiredKeys());

    assertEquals(1, gaps.size());
    assertEquals("kafka-1", gaps.getFirst().nodeId());
    assertTrue(gaps.getFirst().missingPropertyKeys().contains("maasClassifierTenantId"));
  }

  @Test
  void kafkaTriggerMissingGroupIdReportsGap() {
    GraphPatchOwnershipPolicy ownership = kafkaTriggerOwnership();
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("consume", "Consume"),
            List.of(
                new ChainPlanNode(
                    "kafka-trigger-1",
                    "kafka-trigger-2",
                    "Consume",
                    null,
                    null,
                    List.of(
                        new PlanProperty("connectionSourceType", "maas"),
                        new PlanProperty("topicsClassifierName", "cip-auto-tests-topic1")))),
            List.of());

    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(graph, ownership, schemaRequiredKeys());

    assertEquals(1, gaps.size());
    assertEquals("kafka-trigger-1", gaps.getFirst().nodeId());
    assertTrue(gaps.getFirst().missingPropertyKeys().contains("groupId"));
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

  private OwnedSchemaRequiredPropertyGate.NodeRequiredKeys schemaRequiredKeys() {
    return node ->
        schemaService.requiredPatchPropertyKeys(
            node.type(), OwnedSchemaRequiredPropertyGate.propertyMap(node));
  }

  private static GraphPatchOwnershipPolicy kafkaSenderOwnership() {
    return new GraphPatchOwnershipPolicy(
        false,
        false,
        Set.of(),
        Set.of(),
        Map.of(
            "kafka-sender-2",
            Set.of(
                "connectionSourceType",
                "topicsClassifierName",
                "maasClassifierNamespace",
                "maasClassifierTenantEnabled",
                "maasClassifierTenantId",
                "brokers",
                "topics",
                "securityProtocol",
                "saslMechanism",
                "key",
                "keySerializer",
                "valueSerializer",
                "propagateContext")));
  }

  private static GraphPatchOwnershipPolicy kafkaTriggerOwnership() {
    return new GraphPatchOwnershipPolicy(
        false,
        false,
        Set.of(),
        Set.of(),
        Map.of(
            "kafka-trigger-2",
            Set.of(
                "connectionSourceType",
                "brokers",
                "topics",
                "groupId",
                "topicsClassifierName",
                "maasClassifierNamespace",
                "maasClassifierTenantEnabled",
                "maasClassifierTenantId",
                "securityProtocol",
                "saslMechanism")));
  }
}
