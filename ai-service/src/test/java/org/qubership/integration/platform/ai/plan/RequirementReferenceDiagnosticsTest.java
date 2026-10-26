package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class RequirementReferenceDiagnosticsTest {
  private final ObjectMapper json = new ObjectMapper();

  @Test
  void unknownDisplayNameReportsTheExactRejectedFieldAndDeclaredCanonicalDestination() {
    var original = new RequirementRejectedReferenceRepairTest.Fixture(false).corrected();
    var tree = (ObjectNode) json.valueToTree(original);
    ((ObjectNode) tree.at("/facts/1/elementIntent")).put("successorElementRef", "Prepare work");
    var bad = json.convertValue(tree, RequirementCaptureInput.DraftInput.class);
    var result = RequirementCaptureEditor.initialize(bad);
    var issue = result.issues().stream().filter(value -> "UNKNOWN_ELEMENT_REFERENCE".equals(value.code()))
        .findFirst().orElseThrow();
    assertFalse(result.accepted());
    assertEquals("/draft/facts/owner/elementIntent/successorElementRef", issue.path());
    assertEquals("owner", issue.entityId());
    assertTrue(issue.message().contains("Rejected successorElementRef=\"Prepare work\""), issue.message());
    assertTrue(issue.message().contains("Canonical IDs recognized by this check: [\"prepare-step\"]"), issue.message());
    assertTrue(issue.message().contains("\"elementRef\":\"prepare-step\""), issue.message());
    assertTrue(issue.message().contains("\"requestedName\":\"Prepare work\""), issue.message());
    assertTrue(issue.message().contains("\"ownerInteractionId\":\"entry\""), issue.message());
    assertEquals("Prepare work", bad.facts().get(1).elementIntent().successorElementRef());
    assertTrue(RequirementCaptureEditor.initialize(original).accepted());
  }
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(strings = {"missing", "entry", "bad ID", "Prepare work"})
  void unknownSelfAndDisplayReferencesStillRequireAnActualCanonicalCorrection(String reference) {
    var draft = withReference(reference);
    assertFalse(RequirementCaptureEditor.initialize(draft).accepted());
    assertEquals(reference, draft.facts().get(1).elementIntent().successorElementRef());
    assertEquals("UNKNOWN_ELEMENT_REFERENCE", unknown(draft).code());
  }

  @Test
  void ambiguousNamesAndConflictingDeclarationsAreShownWithoutChoosingAnAlias() {
    var tree = (ObjectNode) json.valueToTree(withReference("Prepare work"));
    var duplicate = tree.withArray("facts").get(2).deepCopy();
    ((ObjectNode) duplicate).put("sourceFactId", "other-step");
    ((ObjectNode) duplicate.path("elementIntent")).put("elementRef", "another-step");
    tree.withArray("facts").add(duplicate);
    var draft = json.convertValue(tree, RequirementCaptureInput.DraftInput.class);
    var message = unknown(draft).message();
    assertTrue(message.contains("Canonical IDs recognized by this check: [\"another-step\",\"prepare-step\"]"), message);
    assertTrue(message.contains("\"elementRef\":\"another-step\",\"requestedName\":\"Prepare work\""), message);
    assertTrue(message.contains("\"elementRef\":\"prepare-step\",\"requestedName\":\"Prepare work\""), message);
    assertFalse(RequirementCaptureEditor.initialize(draft).accepted());
    ((ObjectNode) duplicate.path("elementIntent")).put("elementRef", "prepare-step").put("requestedName", "Conflicting name");
    tree.withArray("facts").set(3, duplicate);
    var conflicting = RequirementCaptureEditor.initialize(json.convertValue(tree, RequirementCaptureInput.DraftInput.class));
    assertFalse(conflicting.accepted());
    var participants = java.util.List.of("other-step", "step");
    assertEquals(participants, conflicting.issues().stream()
        .filter(issue -> "CONFLICTING_ELEMENT_INTENT".equals(issue.code()))
        .map(RequirementCaptureEditor.Issue::entityId)
        .sorted()
        .toList(), conflicting.issues().toString());
    var viewed = RequirementCaptureResult.view(null, null, false, false, conflicting.issues(),
        RequirementDiscoveryDirective.NONE);
    viewed.issues().stream()
        .filter(issue -> "CONFLICTING_ELEMENT_INTENT".equals(issue.code()))
        .forEach(issue -> assertEquals(participants, issue.details().relatedEntityIds(), issue.toString()));
  }

  @Test
  void canonicalReferencesDoNotOverrideNativeOwnerAndFlowOrderChecks() {
    var tree = (ObjectNode) json.valueToTree(new RequirementRejectedReferenceRepairTest.Fixture(false).corrected());
    var interaction = tree.withObject("/flow").withArray("interactions").get(0).deepCopy();
    ((ObjectNode) interaction).put("interactionId", "finish").put("direction", "OUTBOUND");
    tree.withObject("/flow").withArray("interactions").add(interaction);
    ((ObjectNode) tree.at("/facts/1/elementIntent")).put("successorElementRef", "finish");
    var order = RequirementCaptureEditor.initialize(json.convertValue(tree, RequirementCaptureInput.DraftInput.class));
    assertFalse(order.accepted());
    assertTrue(order.issues().stream().anyMatch(issue -> "ELEMENT_FLOW_ORDER_MISMATCH".equals(issue.code())), order.issues().toString());
    ((ObjectNode) tree.at("/facts/2/elementIntent")).put("ownerInteractionId", "foreign-owner");
    var owner = RequirementCaptureEditor.initialize(json.convertValue(tree, RequirementCaptureInput.DraftInput.class));
    assertFalse(owner.accepted());
    assertTrue(owner.issues().stream().anyMatch(issue -> "INVALID_ELEMENT_OWNER".equals(issue.code())));
  }

  @Test
  void metadataIsExactSortedAndIndependentOfFactEncounterOrder() throws Exception {
    String value = "quoted \"name\"\n\t😀";
    var tree = (ObjectNode) json.valueToTree(withReference(value));
    ((ObjectNode) tree.at("/facts/2/elementIntent")).put("requestedName", value);
    var draft = json.convertValue(tree, RequirementCaptureInput.DraftInput.class);
    String message = unknown(draft).message();
    assertTrue(message.contains("Rejected successorElementRef=" + json.writeValueAsString(value)), message);
    var reversed = new java.util.ArrayList<>(draft.facts());
    java.util.Collections.reverse(reversed);
    var reordered = new RequirementCaptureInput.DraftInput(draft.flow(), reversed, draft.capabilities(), draft.openQuestions(), draft.settings());
    assertEquals(message, unknown(reordered).message());
  }

  @Test
  void verboseChoicesCannotRenewLegacyNativeOrOuterCredits() {
    var issue = unknown(withReference("Prepare work"));
    String legacy = "Relative placement must name another accepted elementRef or business interaction.";
    var fingerprints = new org.qubership.integration.platform.ai.compiler.capture.policy.ToolCallFingerprintStore(json);
    var feedback = new org.qubership.integration.platform.ai.compiler.capture.CaptureAttemptFeedbackStore(fingerprints);
    var gateway = new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway(
        new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailurePolicy(), fingerprints, feedback);
    var old = new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue(
        issue.code(), issue.path(), legacy, issue.entityId());
    assertFalse(gateway.repeatedRequirementIssue("credit", "same-scope", java.util.List.of(old)));
    var first = feedback.lastRequirementFailure("credit").orElseThrow();
    var oldCause = org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause.captureFailure(first, java.util.List.of(), null);
    var ledger = new org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger(
        new org.qubership.integration.platform.ai.productpipeline.runtime.RecoveryAttemptLedger.Limits(1, 1, 12, 1));
    var oldKey = ledger.key("requirement-discovery", oldCause, "accepted-base", java.util.List.of());
    var journal = java.util.List.of(new org.qubership.integration.platform.ai.productpipeline.store.RunTransition(1, 2,
        org.qubership.integration.platform.ai.productpipeline.store.RunStatus.RUNNING,
        org.qubership.integration.platform.ai.productpipeline.store.RunStatus.RUNNING, "requirement-discovery", java.time.Instant.EPOCH,
        ledger.recordRepair(oldKey, "accepted-base")));
    var extra = (ObjectNode) json.valueToTree(withReference("Prepare work"));
    var other = extra.withArray("facts").get(2).deepCopy();
    ((ObjectNode) other).put("sourceFactId", "other-step");
    ((ObjectNode) other.path("elementIntent")).put("elementRef", "another-step");
    extra.withArray("facts").add(other);
    String changedChoices = unknown(json.convertValue(extra, RequirementCaptureInput.DraftInput.class)).message();
    assertNotEquals(issue.message(), changedChoices);
    for (String message : java.util.List.of(issue.message(), changedChoices)) {
      var verbose = new org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue(
          issue.code(), issue.path(), message, issue.entityId());
      assertTrue(gateway.repeatedRequirementIssue("credit", "same-scope", java.util.List.of(verbose)));
      var denied = feedback.lastRequirementFailure("credit").orElseThrow();
      assertEquals(org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass.IDENTICAL_SPAM, denied.failureClass());
      assertFalse(denied.outerAllowed());
      var cause = org.qubership.integration.platform.ai.productpipeline.capability.RecoveryCause.captureFailure(denied, java.util.List.of(), null);
      var key = ledger.key("requirement-discovery", cause, "accepted-base", journal);
      assertEquals(oldKey, key);
      assertFalse(ledger.mayRepair(journal, key, org.qubership.integration.platform.ai.productpipeline.runtime.InputOrigin.TRUSTED));
      assertEquals(message, cause.captureFailure().feedback().issues().getFirst().message());
    }
    assertEquals(1, fingerprints.softCreditsUsed("credit"));
  }

  private RequirementCaptureInput.DraftInput withReference(String value) {
    var tree = (ObjectNode) json.valueToTree(new RequirementRejectedReferenceRepairTest.Fixture(false).corrected());
    ((ObjectNode) tree.at("/facts/1/elementIntent")).put("successorElementRef", value);
    return json.convertValue(tree, RequirementCaptureInput.DraftInput.class);
  }

  private RequirementCaptureEditor.Issue unknown(RequirementCaptureInput.DraftInput input) {
    return RequirementCaptureEditor.initialize(input).issues().stream()
        .filter(issue -> "UNKNOWN_ELEMENT_REFERENCE".equals(issue.code())).findFirst().orElseThrow();
  }

}
