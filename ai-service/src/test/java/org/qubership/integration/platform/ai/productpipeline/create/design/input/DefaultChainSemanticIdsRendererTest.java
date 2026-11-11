package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.IdsDocument;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ConditionBranchRole;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ErrorHandler;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopMode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.LoopPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.RetryPolicy;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticBranch;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticExecutionEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRegion;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticRoute;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.productpipeline.create.facade.CanonicalPayloadHash;

class DefaultChainSemanticIdsRendererTest {

  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private final ChainSemanticIdsRenderer renderer = new DefaultChainSemanticIdsRenderer();

  @Test
  void renderIsDeterministicAndUsesSequenceDiagramAutonumber() {
    var revision =
        SemanticFixtures.revision(
            List.of(
                SemanticFixtures.entry("http-in", "trigger-http"),
                SemanticFixtures.entry("kafka-in", "trigger-kafka")));

    IdsDocument first = renderer.render(revision, CONTRACT);
    IdsDocument second = renderer.render(revision, CONTRACT);
    assertEquals(first.markdown(), second.markdown());
    assertTrue(first.markdown().contains("sequenceDiagram"));
    assertTrue(first.markdown().contains("autonumber"));
    assertFalse(first.markdown().contains("flowchart"));
    assertFalse(first.markdown().contains("stateDiagram"));
    assertEquals(2, countOccurrences(first.markdown(), "sequenceDiagram"));
    assertTrue(first.markdown().indexOf("http-in") < first.markdown().indexOf("kafka-in"));
    assertEquals(CanonicalPayloadHash.sha256Hex(revision), first.normalizedFlowHash());
    assertEquals(CanonicalPayloadHash.sha256Hex(revision), first.sourceHash());
    assertEquals(IdsDocument.Mode.DERIVED, first.mode());
  }

  @Test
  void rendersLinearFlowInExecutionOrderInsteadOfEdgeIdOrder() {
    ChainSemanticRevision revision =
        new ChainSemanticRevision(
            CONTRACT.semanticSchemaVersion(),
            "revision-linear-flow",
            "OM to Salesforce WFM",
            CONTRACT.contractVersion(),
            List.of(
                new SemanticEntryPoint(
                    "on-task-start",
                    "trigger-async",
                    "request-mapping",
                    0,
                    new SemanticProvenance(List.of()),
                    null)),
            List.of(
                new SemanticNode.Trigger(
                    "trigger-async",
                    "task-start",
                    "async-api-trigger",
                    new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "request-mapping", "script", new SemanticProvenance(List.of())),
                new SemanticNode.ServiceCall(
                    "create-task", "create-task", "createTask", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "response-mapping", "script", new SemanticProvenance(List.of())),
                new SemanticNode.ServiceCall(
                    "on-task-result",
                    "task-result",
                    "onTaskResult",
                    new SemanticProvenance(List.of()))),
            List.of(),
            List.of(
                new SemanticExecutionEdge(
                    "edge-4", "trigger-async", "request-mapping", null, null, null),
                new SemanticExecutionEdge(
                    "edge-3", "request-mapping", "create-task", null, null, null),
                new SemanticExecutionEdge(
                    "edge-2", "create-task", "response-mapping", null, null, null),
                new SemanticExecutionEdge(
                    "edge-1", "response-mapping", "on-task-result", null, null, null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    String markdown =
        renderer.render(revision, CONTRACT, ChainSemanticCaptureFixtures.rockyBrief()).markdown();

    assertTrue(
        markdown.contains(
            "    participant Service1 as OM\n"
                + "    participant CIP\n"
                + "    participant Service2 as Salesforce\n"
                + "    Service1->>CIP: onTaskStart\n"
                + "    CIP->>CIP: script\n"
                + "    CIP->>Service2: createTask\n"
                + "    CIP->>CIP: script\n"
                + "    CIP->>Service1: onTaskResult\n"),
        markdown);
  }

  @Test
  void conditionLoopRetryAndErrorUseMermaidSequenceSyntax() {
    ChainSemanticRevision revision =
        new ChainSemanticRevision(
            CONTRACT.semanticSchemaVersion(),
            "revision-control",
            "chain-control",
            CONTRACT.contractVersion(),
            List.of(
                new SemanticEntryPoint(
                    "http-in",
                    "trigger-http",
                    "condition-1",
                    0,
                    new SemanticProvenance(List.of()),
                    null)),
            List.of(
                new SemanticNode.Trigger(
                    "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "condition-1", "condition", new SemanticProvenance(List.of())),
                new SemanticNode.Operation("loop-1", "loop-2", new SemanticProvenance(List.of())),
                new SemanticNode.ServiceCall(
                    "call-1", "call-1", "getOrder", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "try-catch-1", "try-catch-finally-2", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "catch-body", "script", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "else-body", "script", new SemanticProvenance(List.of())),
                new SemanticNode.Operation(
                    "io-handler", "script", new SemanticProvenance(List.of()))),
            List.of(
                new SemanticRegion.Condition(
                    "region-condition",
                    "condition-1",
                    List.of(
                        new SemanticBranch.Condition(
                            "ok",
                            ConditionBranchRole.IF,
                            "status == 'ok'",
                            1,
                            "loop-1",
                            List.of("loop-1")),
                        new SemanticBranch.Condition(
                            "fail",
                            ConditionBranchRole.ELSE,
                            "status != 'ok'",
                            2,
                            "else-body",
                            List.of("else-body"))),
                    null),
                new SemanticRegion.Loop(
                    "region-loop",
                    "loop-1",
                    "call-1",
                    List.of("call-1"),
                    "try-catch-1",
                    new LoopPolicy(LoopMode.COPY, "items", 10)),
                new SemanticRegion.Retry(
                    "region-retry",
                    "call-1",
                    "call-1",
                    List.of("call-1"),
                    "try-catch-1",
                    new RetryPolicy(3, 100)),
                new SemanticRegion.ErrorScope(
                    "region-error",
                    "try-catch-1",
                    "call-1",
                    List.of(
                        new ErrorHandler(
                            "catch-all",
                            "java.lang.Exception",
                            "catch-body",
                            List.of("catch-body")),
                        new ErrorHandler(
                            "catch-io",
                            "java.io.IOException",
                            "io-handler",
                            List.of("io-handler"))),
                    null,
                    List.of("catch-body", "io-handler"))),
            List.of(
                new SemanticExecutionEdge(
                    "edge-entry",
                    "trigger-http",
                    "condition-1",
                    null,
                    new SemanticRoute.Sequence(),
                    null),
                new SemanticExecutionEdge(
                    "edge-ok",
                    "condition-1",
                    "loop-1",
                    "region-condition",
                    new SemanticRoute.ConditionBranch("ok"),
                    null),
                new SemanticExecutionEdge(
                    "edge-loop",
                    "loop-1",
                    "call-1",
                    "region-loop",
                    new SemanticRoute.LoopBody(),
                    null),
                new SemanticExecutionEdge(
                    "edge-retry",
                    "call-1",
                    "try-catch-1",
                    "region-retry",
                    new SemanticRoute.RetryExhausted(),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            List.of());

    String markdown = renderer.render(revision, CONTRACT).markdown();
    assertTrue(markdown.contains("alt status == 'ok'"), markdown);
    assertTrue(markdown.contains("else status != 'ok'"), markdown);
    assertTrue(markdown.contains("loop "));
    assertTrue(markdown.contains("opt "));
    int tryCall = markdown.indexOf("getOrder");
    int catchException = markdown.indexOf("opt catch java.lang.Exception");
    int catchIo = markdown.indexOf("opt catch java.io.IOException");
    assertTrue(tryCall >= 0, markdown);
    assertTrue(catchException > tryCall, markdown);
    assertTrue(catchIo > catchException, markdown);
    assertFalse(markdown.contains("flowchart"));
    assertFalse(markdown.contains("graph "));
    assertFalse(markdown.contains("stateDiagram"));
  }

  @Test
  void errorScopeShowsTheOutboundCallInsideTheTryPath() {
    ChainSemanticRevision revision = new ChainSemanticRevision(
        CONTRACT.semanticSchemaVersion(), "revision-error-path", "chain-error-path",
        CONTRACT.contractVersion(),
        List.of(new SemanticEntryPoint(
            "http-in", "trigger-http", "error-scope", 0,
            new SemanticProvenance(List.of()), null)),
        List.of(
            new SemanticNode.Trigger(
                "trigger-http", "http-trigger", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "error-scope", "try-catch-finally-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "map-request", "script", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "send-task", "http-sender", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "catch-error", "catch-2", new SemanticProvenance(List.of())),
            new SemanticNode.Operation(
                "error-response", "script", new SemanticProvenance(List.of()))),
        List.of(new SemanticRegion.ErrorScope(
            "region-error", "error-scope", "map-request",
            List.of(new ErrorHandler(
                "catch-all", "java.lang.Exception", "catch-error", List.of("error-response"))),
            null, List.of("send-task", "error-response"))),
        List.of(
            new SemanticExecutionEdge(
                "edge-entry", "trigger-http", "error-scope", null,
                new SemanticRoute.Sequence(), null),
            new SemanticExecutionEdge(
                "edge-try", "error-scope", "map-request", "region-error",
                new SemanticRoute.TryPath(), null),
            new SemanticExecutionEdge(
                "edge-send", "map-request", "send-task", "region-error",
                new SemanticRoute.Sequence(), null),
            new SemanticExecutionEdge(
                "edge-catch", "error-scope", "catch-error", "region-error",
                new SemanticRoute.CatchPath("catch-all"), null),
            new SemanticExecutionEdge(
                "edge-response", "catch-error", "error-response", "region-error",
                new SemanticRoute.Sequence(), null)),
        List.of(), List.of(), List.of(), List.of(), List.of());

    String markdown = renderer.render(revision, CONTRACT).markdown();

    int wrapper = markdown.indexOf("CIP->>CIP: try-catch-finally-2");
    int send = markdown.indexOf("CIP->>CIP: http-sender");
    int catchPath = markdown.indexOf("opt catch java.lang.Exception");
    assertTrue(wrapper >= 0 && send > wrapper && catchPath > send, markdown);
    assertTrue(markdown.indexOf("CIP->>CIP: script", catchPath) > catchPath, markdown);
  }

  private static int countOccurrences(String text, String token) {
    int count = 0;
    int from = 0;
    while (true) {
      int at = text.indexOf(token, from);
      if (at < 0) {
        return count;
      }
      count++;
      from = at + token.length();
    }
  }
}
