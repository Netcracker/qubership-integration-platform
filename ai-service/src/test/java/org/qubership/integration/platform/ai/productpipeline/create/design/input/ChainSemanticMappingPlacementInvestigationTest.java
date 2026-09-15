package org.qubership.integration.platform.ai.productpipeline.create.design.input;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedEdge;
import org.qubership.integration.platform.ai.productpipeline.create.design.input.ChainSemanticCapture.CapturedOperation;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticCanonicalizer;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Direction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Interaction;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementFlow.Transition;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

/** Characterizes mapping-id placement against approved requirement-flow transitions. */
class ChainSemanticMappingPlacementInvestigationTest {

  private static final String START_MAP = "request-task-start-to-create-task";
  private static final String RESULT_MAP = "response-create-task-to-task-result";
  private static final CompilerContract CONTRACT =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  private final ChainSemanticCaptureAdapter adapter =
      new ChainSemanticCaptureAdapter(new ChainSemanticCanonicalizer());

  @Test
  @DisplayName("BM-003: mapping ids cannot be placed on each other's transitions")
  void swappedMappingSitesAreRejected() {
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> adapter.adapt(capture(true), "run-swapped-mappings", brief(), CONTRACT));

    assertTrue(error.getMessage().contains(START_MAP), error.getMessage());
    assertTrue(error.getMessage().contains("create-task -> task-result"), error.getMessage());
    assertTrue(error.getMessage().contains("task-start -> create-task"), error.getMessage());
  }

  @Test
  @DisplayName("BM-003: mapping ids remain valid on their approved transitions")
  void correctlyPlacedMappingSitesAreAccepted() {
    assertDoesNotThrow(
        () -> adapter.adapt(capture(false), "run-correct-mappings", brief(), CONTRACT));
  }

  @Test
  @DisplayName("BM-003: only the approved assignment passes all mapping-id combinations")
  void onlyApprovedMappingAssignmentIsAccepted() {
    List<String> choices = Arrays.asList(null, START_MAP, RESULT_MAP, "unknown-map");

    for (String first : choices) {
      for (String second : choices) {
        String scenario = "first=" + first + ", second=" + second;
        if (START_MAP.equals(first) && RESULT_MAP.equals(second)) {
          assertDoesNotThrow(
              () -> adapter.adapt(capture(first, second), "run-" + scenario, brief(), CONTRACT),
              scenario);
        } else {
          assertThrows(
              IllegalArgumentException.class,
              () -> adapter.adapt(capture(first, second), "run-" + scenario, brief(), CONTRACT),
              scenario);
        }
      }
    }
  }

  @Test
  @DisplayName("BM-003: exactly one of six three-hop mapping permutations is accepted")
  void onlyApprovedThreeHopMappingPermutationIsAccepted() {
    List<List<String>> permutations =
        List.of(
            List.of("map-start-a", "map-a-b", "map-b-c"),
            List.of("map-start-a", "map-b-c", "map-a-b"),
            List.of("map-a-b", "map-start-a", "map-b-c"),
            List.of("map-a-b", "map-b-c", "map-start-a"),
            List.of("map-b-c", "map-start-a", "map-a-b"),
            List.of("map-b-c", "map-a-b", "map-start-a"));

    for (List<String> assignment : permutations) {
      String scenario = String.join(",", assignment);
      if (assignment.equals(permutations.getFirst())) {
        assertDoesNotThrow(
            () ->
                adapter.adapt(
                    threeHopCapture(assignment), "run-three-hop-" + scenario, threeHopBrief(), CONTRACT),
            scenario);
      } else {
        assertThrows(
            IllegalArgumentException.class,
            () ->
                adapter.adapt(
                    threeHopCapture(assignment), "run-three-hop-" + scenario, threeHopBrief(), CONTRACT),
            scenario);
      }
    }
  }

  private static RequirementBrief brief() {
    RequirementBrief original = ChainSemanticCaptureFixtures.rockyBriefWithMapping();
    MappingIntent result = original.mappingIntents().getFirst();
    MappingIntent start =
        new MappingIntent(
            START_MAP,
            "task-start",
            MappingPort.OUTPUT,
            "create-task",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("orderId", "externalId", null)));
    return original.withMappingIntents(List.of(start, result));
  }

  private static ChainSemanticCapture capture(boolean swap) {
    return capture(swap ? RESULT_MAP : START_MAP, swap ? START_MAP : RESULT_MAP);
  }

  private static ChainSemanticCapture capture(String firstMappingId, String secondMappingId) {
    return ChainSemanticCaptureFixtures.rockyCapture(
        List.of(
            new CapturedOperation("map-start", "script", List.of()),
            new CapturedOperation("map-result", "script", List.of("fact-script"))),
        List.of(
            new CapturedEdge("task-start", "map-start", null, null, null, null, null, null),
            new CapturedEdge(
                "map-start",
                "create-task",
                null,
                null,
                null,
                null,
                null,
                firstMappingId),
            new CapturedEdge("create-task", "map-result", null, null, null, null, null, null),
            new CapturedEdge(
                "map-result",
                "task-result",
                null,
                null,
                null,
                null,
                null,
                secondMappingId)));
  }

  private static RequirementBrief threeHopBrief() {
    CatalogBindingHint callA = binding("call-a");
    CatalogBindingHint callB = binding("call-b");
    CatalogBindingHint callC = binding("call-c");
    return new RequirementBrief(
        "Three-hop mapping",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Map three consecutive transitions",
        "draft-three-hop",
        "draft",
        List.of(),
        List.of(
            new RequirementEntryPoint(
                "start", "", "http-trigger", "", "POST", "/start", "start")),
        List.of(
            new RequirementServiceCall("call-a", "", "A", "createA", callA),
            new RequirementServiceCall("call-b", "", "B", "createB", callB),
            new RequirementServiceCall("call-c", "", "C", "createC", callC)),
        List.of(),
        List.of(
            mapping("map-start-a", "start", MappingPort.OUTPUT, "call-a"),
            mapping("map-a-b", "call-a", MappingPort.RESPONSE, "call-b"),
            mapping("map-b-c", "call-b", MappingPort.RESPONSE, "call-c")),
        new RequirementFlow(
            List.of(
                new Interaction("start", Direction.INBOUND, "Client", "start", ""),
                new Interaction("call-a", Direction.OUTBOUND, "A", "createA", ""),
                new Interaction("call-b", Direction.OUTBOUND, "B", "createB", ""),
                new Interaction("call-c", Direction.OUTBOUND, "C", "createC", "")),
            List.of(
                new Transition("start", "call-a"),
                new Transition("call-a", "call-b"),
                new Transition("call-b", "call-c"))),
        List.of(callA, callB, callC));
  }

  private static ChainSemanticCapture threeHopCapture(List<String> assignment) {
    return ChainSemanticCaptureFixtures.rockyCapture(
        List.of(
            new CapturedOperation("map-start-a-shell", "script", List.of()),
            new CapturedOperation("map-a-b-shell", "script", List.of()),
            new CapturedOperation("map-b-c-shell", "script", List.of())),
        List.of(
            new CapturedEdge("start", "map-start-a-shell", null, null, null, null, null, null),
            new CapturedEdge(
                "map-start-a-shell",
                "call-a",
                null,
                null,
                null,
                null,
                null,
                assignment.get(0)),
            new CapturedEdge("call-a", "map-a-b-shell", null, null, null, null, null, null),
            new CapturedEdge(
                "map-a-b-shell",
                "call-b",
                null,
                null,
                null,
                null,
                null,
                assignment.get(1)),
            new CapturedEdge("call-b", "map-b-c-shell", null, null, null, null, null, null),
            new CapturedEdge(
                "map-b-c-shell",
                "call-c",
                null,
                null,
                null,
                null,
                null,
                assignment.get(2))));
  }

  private static MappingIntent mapping(
      String mappingId, String source, MappingPort sourcePort, String target) {
    return new MappingIntent(
        mappingId,
        source,
        sourcePort,
        target,
        MappingPort.REQUEST,
        List.of(new MappingIntentRule("source", "target", null)));
  }

  private static CatalogBindingHint binding(String interactionId) {
    return new CatalogBindingHint(
        CatalogBindingHint.SCHEMA_VERSION,
        interactionId,
        interactionId,
        "POST /" + interactionId,
        "system-" + interactionId,
        "group-" + interactionId,
        "spec-" + interactionId,
        "operation-" + interactionId,
        "http",
        "POST",
        "/" + interactionId,
        "2024.4",
        Instant.EPOCH,
        "evidence-" + interactionId);
  }
}
