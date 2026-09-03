package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import java.util.ArrayList;
import java.util.List;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

/** Shared two-entry semantic revisions for core serialization tests. */
public final class SemanticFixtures {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private SemanticFixtures() {}

  public static SemanticEntryPoint entry(String entryPointId, String triggerNodeId) {
    return new SemanticEntryPoint(
        entryPointId,
        triggerNodeId,
        "op-shared",
        0,
        new SemanticProvenance(List.of()),
        new SemanticEntryPoint.Presentation(null, null));
  }

  public static ChainSemanticRevision revision(List<SemanticEntryPoint> entryPoints) {
    List<SemanticEntryPoint> completed = new ArrayList<>();
    List<SemanticNode> nodes = new ArrayList<>();
    List<SemanticExecutionEdge> edges = new ArrayList<>();
    for (int index = 0; index < entryPoints.size(); index++) {
      SemanticEntryPoint source = entryPoints.get(index);
      completed.add(
          new SemanticEntryPoint(
              source.entryPointId(),
              source.triggerNodeId(),
              source.initialTargetNodeId(),
              index,
              source.provenance(),
              source.presentation()));
      nodes.add(
          new SemanticNode.Trigger(
              source.triggerNodeId(),
              capabilityKey(source.triggerNodeId()),
              source.provenance()));
      edges.add(
          new SemanticExecutionEdge(
              "edge-" + source.entryPointId(),
              source.triggerNodeId(),
              source.initialTargetNodeId(),
              null,
              null,
              null));
    }
    nodes.add(new SemanticNode.Operation("op-shared", "script", new SemanticProvenance(List.of())));
    nodes.add(
        new SemanticNode.ServiceCall(
            "node-call",
            "call-1",
            "getOrder",
            new SemanticProvenance(List.of("fact-call"))));
    edges.add(
        new SemanticExecutionEdge("edge-call", "op-shared", "node-call", null, null, "map-body"));
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-1",
        "chain-greetings",
        CONTRACT.contractVersion(),
        completed,
        nodes,
        List.of(),
        edges,
        List.of(),
        List.of(
            new MappingIntent(
                "map-body",
                "edge-call",
                MappingPort.OUTPUT,
                "edge-call",
                MappingPort.REQUEST,
                List.of(new MappingIntentRule("id", "orderId", null)))),
        List.of("no auth"),
        List.of("happy path"),
        List.of());
  }

  public static final String COMPLETE_TASK_NODE_ID = "script-complete-task";
  public static final String COMPLETE_TASK_FACT_ID = "fact-complete-task";

  /** Linear HTTP trigger plus one service call, with no mapping intents. */
  public static ChainSemanticRevision linearOrders() {
    return linear(
        "Orders",
        "revision-orders",
        "trigger-http",
        "node-call",
        "call-1",
        "createOrder",
        "Orders API",
        List.of(),
        List.of());
  }

  /**
   * Skipped mapping hop with an approved constant-response script. {@code mappingIntents} stay
   * empty; the script is behavior-owned through positive BEHAVIOR provenance.
   */
  public static ChainSemanticRevision linearOrdersWithCompleteTask() {
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-orders-complete-task",
        "Orders",
        CONTRACT.contractVersion(),
        List.of(
            new SemanticEntryPoint(
                "entry-1",
                "trigger-http",
                COMPLETE_TASK_NODE_ID,
                0,
                new SemanticProvenance(List.of()),
                new SemanticEntryPoint.Presentation("Orders API", null))),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                COMPLETE_TASK_NODE_ID,
                "script",
                new SemanticProvenance(List.of(COMPLETE_TASK_FACT_ID))),
            new SemanticNode.ServiceCall(
                "node-call",
                "call-1",
                "createOrder",
                new SemanticProvenance(List.of("fact-call")))),
        List.of(),
        List.of(
            new SemanticExecutionEdge(
                "edge-1",
                "trigger-http",
                COMPLETE_TASK_NODE_ID,
                null,
                new SemanticRoute.Sequence(),
                null),
            new SemanticExecutionEdge(
                "edge-2",
                COMPLETE_TASK_NODE_ID,
                "node-call",
                null,
                new SemanticRoute.Sequence(),
                null)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  /** Same as {@link #linearOrders()} plus one explicit mapping intent. */
  public static ChainSemanticRevision linearOrdersWithMapping() {
    return linear(
        "Orders",
        "revision-orders",
        "trigger-http",
        "node-call",
        "call-1",
        "createOrder",
        "Orders API",
        List.of(
            new MappingIntent(
                "map-init",
                "edge-1",
                MappingPort.OUTPUT,
                "edge-1",
                MappingPort.REQUEST,
                List.of(new MappingIntentRule("id", "customerId", null)))),
        List.of());
  }

  /** Same as {@link #linearOrders()} plus two identity mapping intents. */
  public static ChainSemanticRevision linearOrdersWithTwoIdentityMappings() {
    return linear(
        "Orders",
        "revision-orders",
        "trigger-http",
        "node-call",
        "call-1",
        "createOrder",
        "Orders API",
        List.of(
            new MappingIntent(
                "map-a",
                "edge-1",
                MappingPort.OUTPUT,
                "edge-1",
                MappingPort.REQUEST,
                List.of(new MappingIntentRule("id", "id", null))),
            new MappingIntent(
                "map-b",
                "edge-1",
                MappingPort.OUTPUT,
                "edge-1",
                MappingPort.REQUEST,
                List.of(new MappingIntentRule("code", "code", null)))),
        List.of());
  }

  public static ChainSemanticRevision linear(
      String chainIdentity,
      String revisionId,
      String triggerNodeId,
      String callNodeId,
      String serviceCallId,
      String operation,
      String entryLabel,
      List<MappingIntent> mappingIntents,
      List<String> constraints) {
    List<MappingIntent> intents = mappingIntents == null ? List.of() : List.copyOf(mappingIntents);
    String mappingId = intents.isEmpty() ? null : intents.getFirst().mappingIntentId();
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        revisionId,
        chainIdentity,
        CONTRACT.contractVersion(),
        List.of(
            new SemanticEntryPoint(
                "entry-1",
                triggerNodeId,
                callNodeId,
                0,
                new SemanticProvenance(List.of()),
                new SemanticEntryPoint.Presentation(entryLabel, null))),
        List.of(
            new SemanticNode.Trigger(
                triggerNodeId, "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.ServiceCall(
                callNodeId,
                serviceCallId,
                operation,
                new SemanticProvenance(List.of("fact-call")))),
        List.of(),
        List.of(
            new SemanticExecutionEdge(
                "edge-1",
                triggerNodeId,
                callNodeId,
                null,
                new SemanticRoute.Sequence(),
                mappingId)),
        List.of(),
        intents,
        constraints == null ? List.of() : List.copyOf(constraints),
        List.of(),
        List.of());
  }

  /**
   * Two independent entry points that share one downstream operation. Each trigger starts its own
   * exchange; the shared node is not a barrier join.
   */
  public static ChainSemanticRevision twoEntrySharedDownstream() {
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-two-entry",
        "Two entry shared downstream",
        CONTRACT.contractVersion(),
        List.of(
            entry("http-in", "trigger-http"),
            entry("kafka-in", "trigger-kafka")),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Trigger(
                "trigger-kafka", "kafka-trigger-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "op-shared", "script", new SemanticProvenance(List.of("fact-shared")))),
        List.of(),
        List.of(
            new SemanticExecutionEdge(
                "edge-http-in",
                "trigger-http",
                "op-shared",
                null,
                new SemanticRoute.Sequence(),
                null),
            new SemanticExecutionEdge(
                "edge-kafka-in",
                "trigger-kafka",
                "op-shared",
                null,
                new SemanticRoute.Sequence(),
                null)),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  /**
   * Condition with if/else branches that reconverge on {@code script-common}. Each branch invokes
   * that node independently.
   */
  public static ChainSemanticRevision conditionReconvergence() {
    return new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(),
        "revision-reconverge",
        "Condition reconverge",
        CONTRACT.contractVersion(),
        List.of(
            new SemanticEntryPoint(
                "http-in",
                "trigger-http",
                "condition-1",
                0,
                new SemanticProvenance(List.of()),
                new SemanticEntryPoint.Presentation(null, null))),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "condition-1", "condition", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "script-true", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "script-false", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "script-common", "script", new SemanticProvenance(List.of()))),
        List.of(
            new SemanticRegion.Condition(
                "region-condition",
                "condition-1",
                List.of(
                    new SemanticBranch.Condition(
                        "true-branch",
                        ConditionBranchRole.IF,
                        "status == 'ok'",
                        1,
                        "script-true",
                        List.of("script-true")),
                    new SemanticBranch.Condition(
                        "false-branch",
                        ConditionBranchRole.ELSE,
                        null,
                        0,
                        "script-false",
                        List.of("script-false"))),
                "script-common")),
        List.of(
            new SemanticExecutionEdge(
                "edge-entry",
                "trigger-http",
                "condition-1",
                null,
                new SemanticRoute.Sequence(),
                null),
            new SemanticExecutionEdge(
                "edge-true",
                "condition-1",
                "script-true",
                "region-condition",
                new SemanticRoute.ConditionBranch("true-branch"),
                null),
            new SemanticExecutionEdge(
                "edge-false",
                "condition-1",
                "script-false",
                "region-condition",
                new SemanticRoute.ConditionBranch("false-branch"),
                null),
            new SemanticExecutionEdge(
                "edge-true-join",
                "script-true",
                "script-common",
                "region-condition",
                new SemanticRoute.Reconverge(List.of("true-branch")),
                null),
            new SemanticExecutionEdge(
                "edge-false-join",
                "script-false",
                "script-common",
                "region-condition",
                new SemanticRoute.Reconverge(List.of("false-branch")),
                null)),
        List.of(
            new SemanticContainment("condition-1", "script-true", "if"),
            new SemanticContainment("condition-1", "script-false", "else")),
        List.of(),
        List.of(),
        List.of(),
        List.of());
  }

  public static SemanticRegion.Split asyncSplitOneBranch() {
    return new SemanticRegion.Split(
        "region-async-split",
        "split-async-1",
        SplitMode.ASYNC,
        List.of(new SemanticBranch.Split("notify", 0, "call-notify", List.of("call-notify"))),
        null);
  }

  public static SemanticRegion.Split syncSplitTwoBranches() {
    return new SemanticRegion.Split(
        "region-sync-split",
        "split-1",
        SplitMode.SYNC,
        List.of(
            new SemanticBranch.Split("left", 0, "call-left", List.of("call-left")),
            new SemanticBranch.Split("right", 1, "call-right", List.of("call-right"))),
        null);
  }

  private static String capabilityKey(String triggerNodeId) {
    return triggerNodeId.contains("kafka") ? "kafka-trigger-2" : "http-trigger";
  }
}
