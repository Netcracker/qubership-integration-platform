package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts;
import org.qubership.integration.platform.ai.compiler.artifact.InMemoryArtifactBlobStore;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.plan.model.*;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticFixtures;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class CopyMappingMaterializationValidatorTest {
  private final CopyMappingMaterializationValidator validator = new CopyMappingMaterializationValidator(
      new CompilationArtifacts(new InMemoryArtifactBlobStore(), new ObjectMapper().findAndRegisterModules(), Clock.systemUTC()));

  @Test
  void shouldValidateADerivedBehaviorBodyBeforeCatalogWrite() {
    assertDoesNotThrow(() -> validate("exchange.message.body = 'approved result'"));
  }

  @Test
  void shouldRefuseMissingBehaviorImplementationBeforeCatalogWrite() {
    assertThrows(IllegalArgumentException.class, () -> validate(null));
  }

  @Test
  void shouldRefuseInvalidBehaviorCodeBeforeCatalogWrite() {
    assertThrows(IllegalArgumentException.class, () -> validate("def broken = ("));
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"removed", "renamed", "retyped"})
  void shouldRefuseLostBehaviorAndAcceptItsRestoredImplementation(String defect) {
    var brief = new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary")
        .withFacts(List.of(new RequirementFact(SemanticFixtures.COMPLETE_TASK_FACT_ID,
            RequirementFactPolarity.POSITIVE, RequirementFactKind.BEHAVIOR, "", "Return the approved result.")));
    var nodes = "removed".equals(defect) ? List.<ChainPlanNode>of() : List.of(new ChainPlanNode(
        "renamed".equals(defect) ? "different-node" : SemanticFixtures.COMPLETE_TASK_NODE_ID,
        "retyped".equals(defect) ? "log-record" : "script", "Result", null, null,
        List.of(new PlanProperty("script", "return 'approved result'"))));
    var graph = new ChainPlanGraph("1.0", new ChainSection("id", "test"), nodes, List.of());

    var failure = assertThrows(IllegalArgumentException.class,
        () -> validator.validate("compilation", brief, SemanticFixtures.linearOrdersWithCompleteTask(), graph));

    assertTrue(failure.getMessage().contains(SemanticFixtures.COMPLETE_TASK_NODE_ID), failure.getMessage());
    var restored = new ChainPlanGraph(graph.schemaVersion(), graph.chain(), List.of(new ChainPlanNode(
        SemanticFixtures.COMPLETE_TASK_NODE_ID, "script", "Result", null, null,
        List.of(new PlanProperty("script", "return 'approved result'")))), graph.edges());
    assertDoesNotThrow(() -> validator.validate("compilation", brief,
        SemanticFixtures.linearOrdersWithCompleteTask(), restored));
  }

  private void validate(String body) {
    var fact = new RequirementFact(SemanticFixtures.COMPLETE_TASK_FACT_ID, RequirementFactPolarity.POSITIVE,
        RequirementFactKind.BEHAVIOR, "", "Return the approved result.");
    var brief = new RequirementBrief("goal", List.of(), List.of(), List.of(), List.of(), "summary").withFacts(List.of(fact));
    var graph = new ChainPlanGraph("1.0", new ChainSection("id", "test"), List.of(
        new ChainPlanNode(SemanticFixtures.COMPLETE_TASK_NODE_ID, "script", "Result", null, null,
            body == null ? List.of() : List.of(new PlanProperty("script", body)))), List.of());
    validator.validate("compilation", brief, SemanticFixtures.linearOrdersWithCompleteTask(), graph);
  }
}
