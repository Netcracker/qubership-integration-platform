package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedOperation;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/**
 * Deterministic regression for the original Rocky failure shape: inbound onTaskStart, outbound
 * createTask and onTaskResult, mapping RESPONSE to REQUEST, no generic-barrier stand-in.
 */
class BusinessFirstRequirementFlowIT {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private final ChainSemanticCaptureAdapter adapter =
      new ChainSemanticCaptureAdapter(new ChainSemanticCanonicalizer());

  @Test
  void rockyFlowProjectsInboundTriggerOutboundCallsAndResponseToRequestMapping() {
    RequirementBrief brief = ChainSemanticCaptureFixtures.rockyBriefWithMapping();
    assertEquals(1, brief.entryPoints().size());
    assertEquals("task-start", brief.entryPoints().getFirst().entryPointId());
    assertEquals(
        Set.of("create-task", "task-result"),
        brief.serviceCalls().stream()
            .map(call -> call.serviceCallId())
            .collect(Collectors.toSet()));
    MappingIntent mapping = brief.mappingIntents().getFirst();
    assertEquals(MappingPort.RESPONSE, mapping.sourcePort());
    assertEquals(MappingPort.REQUEST, mapping.targetPort());
    assertEquals("create-task", mapping.sourceRef());
    assertEquals("task-result", mapping.targetRef());
    assertEquals("commandType", mapping.rules().getFirst().targetPath());
    assertTrue(mapping.rules().getFirst().expression().contains("completeTask"));

    ChainSemanticRevision revision =
        adapter.adapt(rockyMappedCapture(mapping.mappingIntentId()), "run-rocky", brief, CONTRACT);

    assertEquals(1, revision.entryPoints().size());
    assertEquals(
        List.of("task-start"),
        revision.nodes().stream()
            .filter(SemanticNode.Trigger.class::isInstance)
            .map(SemanticNode::nodeId)
            .toList());
    assertEquals(
        Set.of("create-task", "task-result"),
        revision.nodes().stream()
            .filter(SemanticNode.ServiceCall.class::isInstance)
            .map(SemanticNode::nodeId)
            .collect(Collectors.toSet()));
    assertTrue(
        revision.nodes().stream()
            .noneMatch(
                node ->
                    node instanceof SemanticNode.Operation operation
                        && "generic-barrier".equals(operation.elementType())));
    MappingIntent captured = revision.mappingIntents().getFirst();
    assertEquals(MappingPort.RESPONSE, captured.sourcePort());
    assertEquals(MappingPort.REQUEST, captured.targetPort());
    new DefaultChainSemanticRevisionValidator().validate(revision, CONTRACT);
  }

  private static ChainSemanticCapture rockyMappedCapture(String mappingIntentId) {
    return ChainSemanticCaptureFixtures.rockyCapture(
        List.of(new CapturedOperation("mapper-1", "script", List.of())),
        List.of(
            new CapturedEdge("task-start", "create-task", null, null, null, null, null, null),
            new CapturedEdge(
                "create-task", "mapper-1", null, null, null, null, null, mappingIntentId),
            new CapturedEdge("mapper-1", "task-result", null, null, null, null, null, null)));
  }
}
