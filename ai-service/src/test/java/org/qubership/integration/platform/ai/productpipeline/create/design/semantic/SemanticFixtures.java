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

  private static String capabilityKey(String triggerNodeId) {
    return triggerNodeId.contains("kafka") ? "kafka-trigger-2" : "http-trigger";
  }
}
