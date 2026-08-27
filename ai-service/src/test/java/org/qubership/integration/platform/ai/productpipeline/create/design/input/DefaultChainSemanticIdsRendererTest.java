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
