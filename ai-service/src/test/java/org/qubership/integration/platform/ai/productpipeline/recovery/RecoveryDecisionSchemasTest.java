package org.qubership.integration.platform.ai.productpipeline.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.langchain4j.internal.JsonSchemaElementUtils;
import dev.langchain4j.model.chat.request.json.JsonAnyOfSchema;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Kind;
import org.qubership.integration.platform.ai.compiler.artifact.CompilationArtifacts.Reference;
import org.qubership.integration.platform.ai.compiler.artifact.TestArtifactBlobStoreProducer;
import org.qubership.integration.platform.ai.productpipeline.artifact.UserInput;
import org.qubership.integration.platform.ai.productpipeline.capability.CaptureFailureEvidence;

@QuarkusTest
@TestProfile(TestArtifactBlobStoreProducer.Profile.class)
class RecoveryDecisionSchemasTest {
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void faultTargetsRequireOneCompleteCurrentTupleRatherThanIndependentlyAllowedFieldValues() {
    var brief = new Reference(Kind.REQUIREMENT_BRIEF, "brief", "brief-hash");
    var semantic = new Reference(Kind.CHAIN_SEMANTIC_REVISION, "semantic", "semantic-hash");
    var producer = new Reference(Kind.REQUIREMENT_DRAFT, "producer", "producer-hash");
    var otherProducer = new Reference(Kind.REQUIREMENT_DRAFT, "other-producer", "other-hash");
    var plan = new Reference(Kind.IMPLEMENTATION_PLAN, "plan", "plan-hash");
    var nested = new Reference(Kind.REQUIREMENT_CAPTURE_ATTEMPT, "nested", "nested-hash");
    var obsolete = new Reference(Kind.CHAIN_PLAN_GRAPH, "obsolete", "obsolete-hash");
    var history = new RepairHistory.Attempt(new Reference(Kind.RECOVERY_EVIDENCE, "past-evidence", "past-hash"),
        "past-failure", "requirement-discovery", List.of(), List.of(), List.of(obsolete), null, List.of(), false);
    var evidence = new RecoveryEvidence(1, "current-failure", "CONTRACT_SHAPE", "requirement-discovery",
        "requirement-discovery", brief, semantic, List.of(producer, otherProducer), List.of(), null,
        List.of(), List.of(), List.of(), List.of(), false,
        new CaptureFailureEvidence(null, brief, nested, List.of()));
    var context = new RecoveryContext(evidence, null, null, "en", List.of(), plan, null, List.of(history));
    JsonNode fault = fields(context).path("faultArtifactRef");

    for (var expected : List.of(brief, semantic, producer, otherProducer, plan)) {
      assertAccepts(fault, JSON.valueToTree(expected));
    }
    assertAccepts(fault, JSON.nullNode());
    for (var forbidden : List.of(nested, obsolete,
        new Reference(producer.kind(), producer.artifactId(), otherProducer.contentHash()),
        new Reference(plan.kind(), producer.artifactId(), producer.contentHash()),
        new Reference(producer.kind(), "invented", producer.contentHash()))) {
      assertRejects(fault, JSON.valueToTree(forbidden));
      var ignoredSchema = new RecoveryDecision(RecoveryCauseClass.DERIVATION_DEFECT, forbidden,
          List.of("current-failure"), RecoveryAction.REGENERATE_ARTIFACT, List.of(), "", "Repair the current output.");
      assertFalse(RecoveryDecisionValidator.validate(ignoredSchema, context).accepted());
    }
    assertRejects(fault, JSON.getNodeFactory().textNode(producer.artifactId()));
  }

  @Test
  void nonStrictSerializationDoesNotPromiseRejectionOfUnknownObjectKeys() {
    var producer = new Reference(Kind.REQUIREMENT_DRAFT, "producer", "producer-hash");
    var context = context("current-failure", producer);
    var root = (JsonObjectSchema) RecoveryDecisionSchemas.responseFormat(context).jsonSchema().rootElement();
    var alternatives = (JsonAnyOfSchema) root.properties().get("faultArtifactRef");
    alternatives.anyOf().stream().filter(JsonObjectSchema.class::isInstance).map(JsonObjectSchema.class::cast)
        .forEach(reference -> assertEquals(Boolean.FALSE, reference.additionalProperties()));
    JsonNode serialized = fields(context).path("faultArtifactRef");
    serialized.path("anyOf").forEach(alternative -> assertFalse(alternative.has("additionalProperties")));
    var extra = JSON.valueToTree(producer).deepCopy();
    ((com.fasterxml.jackson.databind.node.ObjectNode) extra).put("extraAuthority", true);
    assertAccepts(serialized, extra);
    assertRejects(serialized, JSON.valueToTree(
        new Reference(producer.kind(), producer.artifactId(), "foreign-hash")));
  }

  @Test
  void historicalEvidenceRemainsCitableWithoutBecomingACurrentFaultTarget() {
    var producer = new Reference(Kind.REQUIREMENT_DRAFT, "producer", "producer-hash");
    var historyRef = new Reference(Kind.RECOVERY_EVIDENCE, "past-evidence", "past-hash");
    var oldFault = new Reference(Kind.CHAIN_PLAN_GRAPH, "old-graph", "old-hash");
    var finding = new SemanticFinding("CURRENT", "native rule", "current-occurrence", "entry", "script",
        List.of(), List.of(), List.of(), "", Map.of(), List.of(), "native details");
    var oldFinding = new SemanticFinding("PAST", "prior native rule", "past-occurrence", "entry", "script",
        List.of(), List.of(), List.of(), "", Map.of(), List.of(), "prior details");
    var history = new RepairHistory.Attempt(historyRef, "past-failure", "design-execution", List.of(),
        List.of(), List.of(oldFault), null, List.of(oldFinding), false);
    var evidence = new RecoveryEvidence(1, "current-failure", "VALIDATION_BLOCKER", "design-execution",
        null, null, List.of(producer), List.of(finding), null, List.of());
    var context = new RecoveryContext(evidence, null, null, "en",
        List.of(new UserInput("author-source", "requirement-discovery", "Keep the saved behavior.", Instant.EPOCH)),
        null, null, List.of(history));
    JsonNode fields = fields(context);
    for (String id : List.of("current-failure", "current-occurrence", "producer", "producer-hash",
        "past-failure", "past-evidence", "past-hash", "past-occurrence", "author-source")) {
      assertAccepts(fields.path("evidenceRefs"), JSON.valueToTree(List.of(id)));
    }
    assertRejects(fields.path("evidenceRefs"), JSON.valueToTree(List.of("invented")));
    assertRejects(fields.path("faultArtifactRef"), JSON.valueToTree(historyRef));
    assertRejects(fields.path("faultArtifactRef"), JSON.valueToTree(oldFault));
    var validCitation = new RecoveryDecision(RecoveryCauseClass.UNCLASSIFIED, null, List.of("past-occurrence"),
        RecoveryAction.PARK, List.of(), "", "The original refusal remains available.");
    assertTrue(RecoveryDecisionValidator.validate(validCitation, context).accepted());
    var wrongFault = new RecoveryDecision(validCitation.causeClass(), oldFault, validCitation.evidenceRefs(),
        validCitation.action(), List.of(), "", validCitation.userSummary());
    assertFalse(RecoveryDecisionValidator.validate(wrongFault, context).accepted());
  }

  @Test
  void emptyDomainsUseNullAndAnEmptyArrayWithoutInventingAReference() {
    var context = new RecoveryContext(null, null, null, "en");
    JsonNode fields = fields(context);
    assertEquals(JSON.getNodeFactory().textNode("null"), fields.path("faultArtifactRef").path("type"));
    assertAccepts(fields.path("faultArtifactRef"), JSON.nullNode());
    assertRejects(fields.path("faultArtifactRef"), JSON.valueToTree(
        new Reference(Kind.REQUIREMENT_DRAFT, "invented", "invented-hash")));
    assertAccepts(fields.path("evidenceRefs"), JSON.valueToTree(List.of()));
    assertRejects(fields.path("evidenceRefs"), JSON.valueToTree(List.of("invented")));
    assertRejects(fields.path("evidenceRefs"), JSON.nullNode());
    assertEquals(0, fields.path("evidenceRefs").path("maxItems").asInt(-1));
    var missingEvidence = new RecoveryDecision(RecoveryCauseClass.UNCLASSIFIED, null, List.of(),
        RecoveryAction.PARK, List.of(), "", "No recovery evidence is available.");
    assertEquals(List.of("Missing recovery evidence."),
        RecoveryDecisionValidator.validate(missingEvidence, context).findings());
  }

  @Test
  void evidenceWithoutArtifactTargetsStillHasRealCitationsAndNoFaultAlternative() {
    var evidence = new RecoveryEvidence(1, "failure-without-artifact", "VALIDATION_BLOCKER", "requirement-discovery",
        null, null, List.of(), List.of(), null, List.of());
    JsonNode fields = fields(new RecoveryContext(evidence, null, null, "en"));
    assertAccepts(fields.path("faultArtifactRef"), JSON.nullNode());
    assertRejects(fields.path("faultArtifactRef"), JSON.valueToTree(
        new Reference(Kind.REQUIREMENT_DRAFT, "invented", "invented-hash")));
    assertAccepts(fields.path("evidenceRefs"), JSON.valueToTree(List.of("failure-without-artifact")));
    assertRejects(fields.path("evidenceRefs"), JSON.valueToTree(List.of("invented")));
  }

  @Test
  void successiveContextsDoNotRetainEarlierReferenceAlternatives() {
    var firstRef = new Reference(Kind.REQUIREMENT_DRAFT, "first", "first-hash");
    var secondRef = new Reference(Kind.REQUIREMENT_DRAFT, "second", "second-hash");
    var first = context("first-failure", firstRef);
    var second = context("second-failure", secondRef);
    JsonNode firstFields = fields(first);
    JsonNode secondFields = fields(second);
    assertAccepts(firstFields.path("faultArtifactRef"), JSON.valueToTree(firstRef));
    assertAccepts(secondFields.path("faultArtifactRef"), JSON.valueToTree(secondRef));
    assertRejects(firstFields.path("faultArtifactRef"), JSON.valueToTree(secondRef));
    assertRejects(secondFields.path("faultArtifactRef"), JSON.valueToTree(firstRef));
    assertRejects(secondFields.path("evidenceRefs"), JSON.valueToTree(List.of("first-failure")));
    assertEquals(firstFields, fields(first), "Another invocation must not mutate an earlier schema.");
  }

  private static RecoveryContext context(String failureId, Reference ref) {
    return new RecoveryContext(new RecoveryEvidence(1, failureId, "VALIDATION_BLOCKER", "design-execution",
        null, null, List.of(ref), List.of(), null, List.of()), null, null, "en");
  }

  private static JsonNode fields(RecoveryContext context) {
    var root = RecoveryDecisionSchemas.responseFormat(context).jsonSchema().rootElement();
    return JSON.valueToTree(JsonSchemaElementUtils.toMap(root)).path("properties");
  }

  private static void assertAccepts(JsonNode schema, JsonNode value) {
    var findings = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema).validate(value);
    assertTrue(findings.isEmpty(), () -> value + " rejected by " + schema + ": " + findings);
  }

  private static void assertRejects(JsonNode schema, JsonNode value) {
    assertFalse(JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7).getSchema(schema).validate(value).isEmpty(),
        () -> value + " unexpectedly accepted by " + schema);
  }
}
