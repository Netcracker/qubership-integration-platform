package org.qubership.integration.platform.ai.harness;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.DefaultChainSemanticRevisionValidator;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;

class ExecutorEvalFixtureTest {

  private static final Path FIXTURE_ROOT = Path.of("e2e", "executor-eval");
  private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());

  @ParameterizedTest
  @MethodSource("caseIds")
  void fixtureIsDeserializableAndSemanticallyValid(String caseId) throws Exception {
    ExecutorHarnessRequest request =
        JSON.readValue(
            Files.readString(FIXTURE_ROOT.resolve("inputs").resolve(caseId + ".json")),
            ExecutorHarnessRequest.class);
    String plannerResponse =
        Files.readString(FIXTURE_ROOT.resolve("planner-responses").resolve(caseId + ".md"));

    assertFalse(plannerResponse.isBlank());
    assertBindingsOwnTheirTargets(caseId, request);
    assertMappingRefsExist(caseId, request);
    assertDoesNotThrow(
        () ->
            new DefaultChainSemanticRevisionValidator()
                .validate(
                    request.semanticRevision(),
                    new ClasspathCompilerContractRepository()
                        .require(request.semanticRevision().compilerContractVersion()),
                    request.requirementBrief()));
  }

  private static void assertBindingsOwnTheirTargets(
      String caseId, ExecutorHarnessRequest request) {
    Map<String, SemanticNode.ServiceCall> callsByOccurrence =
        request.semanticRevision().nodes().stream()
            .filter(SemanticNode.ServiceCall.class::isInstance)
            .map(SemanticNode.ServiceCall.class::cast)
            .collect(
                java.util.stream.Collectors.toMap(
                    SemanticNode.ServiceCall::serviceCallId, call -> call));
    ResolvedServiceCallBinding.requireExactOwners(
        List.copyOf(callsByOccurrence.keySet()), request.bindings());
    Set<String> targets = new HashSet<>();
    for (ResolvedServiceCallBinding binding : request.bindings()) {
      SemanticNode.ServiceCall call = callsByOccurrence.get(binding.serviceCallId());
      assertTrue(call != null, () -> caseId + ": extra binding " + binding.serviceCallId());
      assertTrue(
          call.nodeId().equals(binding.targetNodeId()),
          () ->
              caseId
                  + ": binding "
                  + binding.serviceCallId()
                  + " targets "
                  + binding.targetNodeId()
                  + " instead of "
                  + call.nodeId());
      assertTrue(
          targets.add(binding.targetNodeId()),
          () -> caseId + ": duplicate binding target " + binding.targetNodeId());
    }
  }

  private static List<String> caseIds() throws Exception {
    return JSON.readTree(Files.readString(FIXTURE_ROOT.resolve("cases.json")))
        .path("cases")
        .findValuesAsText("id");
  }

  private static void assertMappingRefsExist(String caseId, ExecutorHarnessRequest request) {
    Set<String> validRefs = new HashSet<>();
    request.semanticRevision().nodes().forEach(node -> validRefs.add(node.nodeId()));
    request.semanticRevision().entryPoints().forEach(entry -> validRefs.add(entry.entryPointId()));
    request.semanticRevision().nodes().stream()
        .filter(SemanticNode.Trigger.class::isInstance)
        .map(SemanticNode.Trigger.class::cast)
        .forEach(trigger -> validRefs.add(trigger.interactionId()));
    request.semanticRevision().nodes().stream()
        .filter(SemanticNode.ServiceCall.class::isInstance)
        .map(SemanticNode.ServiceCall.class::cast)
        .forEach(call -> validRefs.add(call.serviceCallId()));
    request.semanticRevision().executionEdges().forEach(edge -> validRefs.add(edge.edgeId()));

    request
        .requirementBrief()
        .mappingIntents()
        .forEach(
            intent -> {
              assertTrue(
                  validRefs.contains(intent.sourceRef()),
                  () -> caseId + ": unknown mapping sourceRef " + intent.sourceRef());
              assertTrue(
                  validRefs.contains(intent.targetRef()),
                  () -> caseId + ": unknown mapping targetRef " + intent.targetRef());
            });
  }
}
