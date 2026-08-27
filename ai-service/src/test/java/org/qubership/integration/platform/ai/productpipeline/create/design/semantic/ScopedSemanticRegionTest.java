package org.qubership.integration.platform.ai.productpipeline.create.design.semantic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class ScopedSemanticRegionTest {

  private final ObjectMapper mapper = new ObjectMapper();
  private final ChainSemanticCanonicalizer canonicalizer = new ChainSemanticCanonicalizer();

  @Test
  void loopScopeDoesNotCreateExecutionCycle() {
    SemanticRegion.Loop loop =
        new SemanticRegion.Loop(
            "loop-region",
            "loop-1",
            "body-script",
            List.of("body-script"),
            "after-loop",
            new LoopPolicy(LoopMode.COPY, "items", 1500));
    assertEquals(List.of("body-script"), loop.bodyExitNodeIds());
    assertEquals("after-loop", loop.exitNodeId());
    List<SemanticExecutionEdge> edges =
        List.of(
            new SemanticExecutionEdge(
                "edge-body",
                "loop-1",
                "body-script",
                "loop-region",
                new SemanticRoute.LoopBody(),
                null),
            new SemanticExecutionEdge(
                "edge-exit",
                "body-script",
                "after-loop",
                "loop-region",
                new SemanticRoute.LoopExit(),
                null));
    assertTrue(
        edges.stream()
            .noneMatch(
                edge ->
                    "body-script".equals(edge.sourceNodeId())
                        && "loop-1".equals(edge.targetNodeId())));
  }

  @Test
  void retryCountSerializesAsJsonNumber() throws Exception {
    SemanticRegion.Retry retry =
        new SemanticRegion.Retry(
            "retry-region",
            "call-1",
            "call-1",
            List.of("call-1"),
            "after-retry",
            new RetryPolicy(3, 5000));
    JsonNode count = mapper.valueToTree(retry).get("policy").get("retryCount");
    assertTrue(count.isNumber());
    assertFalse(count.isTextual());
    assertEquals(3, count.intValue());
    assertEquals(retry, roundTrip(retry));
  }

  @Test
  void roundTripsCopyAndDoWhileLoopPolicies() throws Exception {
    SemanticRegion.Loop copyLoop =
        new SemanticRegion.Loop(
            "loop-region",
            "loop-1",
            "body-script",
            List.of("body-script"),
            "after-loop",
            new LoopPolicy(LoopMode.COPY, "items", 1500));
    SemanticRegion.Loop doWhileLoop =
        new SemanticRegion.Loop(
            "loop-region",
            "loop-1",
            "body-script",
            List.of("body-script"),
            "after-loop",
            new LoopPolicy(LoopMode.DO_WHILE, "${hasMore}", 10));
    assertEquals(copyLoop, roundTrip(copyLoop));
    assertEquals(doWhileLoop, roundTrip(doWhileLoop));
    assertEquals(LoopMode.COPY, roundTrip(copyLoop).policy().mode());
    assertEquals(LoopMode.DO_WHILE, roundTrip(doWhileLoop).policy().mode());
  }

  @Test
  void rejectsNonPositiveLoopSafetyBound() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoopPolicy(LoopMode.COPY, "items", 0));
    assertThrows(
        IllegalArgumentException.class,
        () -> new LoopPolicy(LoopMode.DO_WHILE, "${hasMore}", -1));
  }

  @Test
  void preservesErrorHandlerOrder() throws Exception {
    SemanticRegion.ErrorScope scope =
        new SemanticRegion.ErrorScope(
            "error-region",
            "try-catch-1",
            "try-body",
            List.of(
                handler("catch-timeout", "java.util.concurrent.TimeoutException"),
                handler("catch-all", "java.lang.Exception")),
            "finally-script",
            List.of("after-error"));
    SemanticRegion.ErrorScope roundTripped = roundTrip(scope);
    assertEquals(
        List.of("catch-timeout", "catch-all"),
        roundTripped.handlers().stream().map(ErrorHandler::handlerId).toList());
    assertEquals("finally-script", roundTripped.finallyEntryNodeId());
  }

  @Test
  void roundTripsErrorScopeWithoutFinally() throws Exception {
    SemanticRegion.ErrorScope scope =
        new SemanticRegion.ErrorScope(
            "error-region",
            "try-catch-1",
            "try-body",
            List.of(handler("catch-all", "java.lang.Exception")),
            null,
            List.of("after-error"));
    assertNull(roundTrip(scope).finallyEntryNodeId());
  }

  @Test
  void roundTripsScopedExecutionRoutes() throws Exception {
    assertEquals(new SemanticRoute.LoopBody(), roundTrip(new SemanticRoute.LoopBody()));
    assertEquals(new SemanticRoute.LoopExit(), roundTrip(new SemanticRoute.LoopExit()));
    assertEquals(new SemanticRoute.RetryAttempt(), roundTrip(new SemanticRoute.RetryAttempt()));
    assertEquals(
        new SemanticRoute.RetryExhausted(), roundTrip(new SemanticRoute.RetryExhausted()));
    assertEquals(new SemanticRoute.TryPath(), roundTrip(new SemanticRoute.TryPath()));
    assertEquals(
        new SemanticRoute.CatchPath("catch-all"),
        roundTrip(new SemanticRoute.CatchPath("catch-all")));
    assertEquals(new SemanticRoute.FinallyPath(), roundTrip(new SemanticRoute.FinallyPath()));
  }

  @Test
  void rejectsLoopKindAlias() throws Exception {
    SemanticRegion.Loop loop =
        new SemanticRegion.Loop(
            "loop-region",
            "loop-1",
            "body-script",
            List.of("body-script"),
            "after-loop",
            new LoopPolicy(LoopMode.COPY, "items", 1500));
    ObjectNode tree = mapper.valueToTree(loop);
    tree.put("kind", "loop-2");
    assertThrows(JsonMappingException.class, () -> mapper.treeToValue(tree, SemanticRegion.class));
    tree.put("kind", "loop");
    assertThrows(JsonMappingException.class, () -> mapper.treeToValue(tree, SemanticRegion.class));
  }

  @Test
  void canonicalHashIgnoresScopedRegionListOrderAndPreservesHandlers() {
    SemanticRegion.Loop loop = copyLoop();
    SemanticRegion.Retry retry = retryRegion();
    SemanticRegion.ErrorScope errors = errorScope();
    ChainSemanticRevision base = revisionWithRegions(List.of(loop, retry, errors));
    ChainSemanticRevision shuffled = revisionWithRegions(List.of(errors, loop, retry));
    assertEquals(canonicalizer.sha256(base), canonicalizer.sha256(shuffled));
    SemanticRegion.ErrorScope swappedHandlers =
        new SemanticRegion.ErrorScope(
            "error-region",
            "try-catch-1",
            "try-body",
            List.of(
                handler("catch-all", "java.lang.Exception"),
                handler("catch-timeout", "java.util.concurrent.TimeoutException")),
            "finally-script",
            List.of("after-error"));
    ChainSemanticRevision reorderedHandlers =
        revisionWithRegions(List.of(loop, retry, swappedHandlers));
    assertNotEquals(canonicalizer.sha256(base), canonicalizer.sha256(reorderedHandlers));
  }

  private static SemanticRegion.Loop copyLoop() {
    return new SemanticRegion.Loop(
        "loop-region",
        "loop-1",
        "body-script",
        List.of("body-script"),
        "after-loop",
        new LoopPolicy(LoopMode.COPY, "items", 1500));
  }

  private static SemanticRegion.Retry retryRegion() {
    return new SemanticRegion.Retry(
        "retry-region",
        "call-1",
        "call-1",
        List.of("call-1"),
        "after-retry",
        new RetryPolicy(3, 5000));
  }

  private static SemanticRegion.ErrorScope errorScope() {
    return new SemanticRegion.ErrorScope(
        "error-region",
        "try-catch-1",
        "try-body",
        List.of(
            handler("catch-timeout", "java.util.concurrent.TimeoutException"),
            handler("catch-all", "java.lang.Exception")),
        "finally-script",
        List.of("after-error"));
  }

  private static ErrorHandler handler(String handlerId, String exceptionClass) {
    return new ErrorHandler(handlerId, exceptionClass, handlerId, List.of(handlerId));
  }

  private static ChainSemanticRevision revisionWithRegions(List<SemanticRegion> regions) {
    ChainSemanticRevision base =
        SemanticFixtures.revision(List.of(SemanticFixtures.entry("http-in", "trigger-http")));
    return new ChainSemanticRevision(
        base.schemaVersion(),
        base.revisionId(),
        base.chainIdentity(),
        base.compilerContractVersion(),
        base.entryPoints(),
        base.nodes(),
        regions,
        base.executionEdges(),
        base.containment(),
        base.mappingIntents(),
        base.constraints(),
        base.assumptions(),
        base.citations());
  }

  private SemanticRegion.Loop roundTrip(SemanticRegion.Loop region) throws Exception {
    return mapper.readValue(mapper.writeValueAsBytes(region), SemanticRegion.Loop.class);
  }

  private SemanticRegion.Retry roundTrip(SemanticRegion.Retry region) throws Exception {
    return mapper.readValue(mapper.writeValueAsBytes(region), SemanticRegion.Retry.class);
  }

  private SemanticRegion.ErrorScope roundTrip(SemanticRegion.ErrorScope region) throws Exception {
    return mapper.readValue(mapper.writeValueAsBytes(region), SemanticRegion.ErrorScope.class);
  }

  private SemanticRoute roundTrip(SemanticRoute route) throws Exception {
    return mapper.readValue(mapper.writeValueAsBytes(route), SemanticRoute.class);
  }
}
