package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingContract;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

class BriefMappingValidatorTest {

  private static final MappingContract SOURCE =
      MappingContract.of(
          new MappingContract.Field("$.orderId", "string", true),
          new MappingContract.Field("$.userId", "string", true),
          new MappingContract.Field("$.name", "string", true),
          new MappingContract.Field("$.createdAt", "string", false),
          new MappingContract.Field("$.status", "string", false));

  private static final MappingContract TARGET =
      MappingContract.of(
          new MappingContract.Field("$.orderId", "string", true),
          new MappingContract.Field("$.personId", "string", true),
          new MappingContract.Field("$.fullName", "string", true),
          new MappingContract.Field("$.registrationDate", "string", false),
          new MappingContract.Field("$.state", "string", false),
          new MappingContract.Field("$.nickname", "string", false));

  @Test
  void groupsFiveRulesIntoOneIntentForOneBoundary() {
    Optional<MappingIntent> intent =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("$.orderId", "$.orderId", null, MappingRuleStatus.AUTO),
                new MappingIntentRule("$.userId", "$.personId", null, MappingRuleStatus.PROPOSED),
                new MappingIntentRule("$.name", "$.fullName", null, MappingRuleStatus.PROPOSED),
                new MappingIntentRule(
                    "$.createdAt", "$.registrationDate", null, MappingRuleStatus.PROPOSED),
                new MappingIntentRule("$.status", "$.state", null, MappingRuleStatus.PROPOSED)),
            SOURCE,
            TARGET);

    assertTrue(intent.isPresent());
    assertEquals("map-init", intent.get().mappingIntentId());
    assertEquals(MappingPort.OUTPUT, intent.get().sourcePort());
    assertEquals(MappingPort.REQUEST, intent.get().targetPort());
    assertEquals(5, intent.get().rules().size());
    assertEquals(MappingRuleStatus.AUTO, intent.get().rules().getFirst().status());
    assertEquals(MappingRuleStatus.PROPOSED, intent.get().rules().get(1).status());
  }

  @Test
  void identityOnlyAutoCollapsesToPassThrough() {
    Optional<MappingIntent> intent =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.orderId", "$.orderId", null, MappingRuleStatus.AUTO)),
            SOURCE,
            MappingContract.of(new MappingContract.Field("$.orderId", "string", true)));

    assertTrue(intent.isEmpty());
  }

  @Test
  void keepsAutoVisibleWhenGroupedWithProposedRules() {
    Optional<MappingIntent> intent =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("$.orderId", "$.orderId", null),
                new MappingIntentRule("$.userId", "$.personId", null)),
            SOURCE,
            TARGET);

    assertTrue(intent.isPresent());
    assertEquals(MappingRuleStatus.AUTO, intent.get().rules().getFirst().status());
    assertEquals(MappingRuleStatus.PROPOSED, intent.get().rules().get(1).status());
  }

  @Test
  void unresolvedRequiredTargetBlocksApprovalAndOptionalUnmatchedDoesNot() {
    Optional<MappingIntent> missingRequired =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.orderId", "$.orderId", null)),
            SOURCE,
            TARGET);
    RequirementBrief blocked =
        briefWithIntents(List.of(missingRequired.orElseThrow()));
    assertTrue(BriefMappingValidator.blocksApproval(blocked));
    assertTrue(
        BriefMappingValidator.unresolvedRequiredMessage(blocked)
            .orElseThrow()
            .contains("$.personId"));

    Optional<MappingIntent> requiredCovered =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("$.orderId", "$.orderId", null),
                new MappingIntentRule("$.userId", "$.personId", null),
                new MappingIntentRule("$.name", "$.fullName", null)),
            SOURCE,
            TARGET);
    RequirementBrief allowed = briefWithIntents(List.of(requiredCovered.orElseThrow()));
    assertFalse(BriefMappingValidator.blocksApproval(allowed));
    assertTrue(
        allowed.mappingIntents().getFirst().rules().stream()
            .noneMatch(rule -> "$.nickname".equals(rule.targetPath())));
  }

  @Test
  void unknownContractsDoNotInventUnresolvedRequiredTargets() {
    Optional<MappingIntent> intent =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.userId", "$.personId", null)),
            MappingContract.unknown(),
            MappingContract.unknown());

    assertTrue(intent.isPresent());
    assertEquals(MappingRuleStatus.PROPOSED, intent.get().rules().getFirst().status());
    assertFalse(BriefMappingValidator.blocksApproval(briefWithIntents(List.of(intent.get()))));
  }

  @Test
  void doesNotTreatDifferentNamesAsAuto() {
    Optional<MappingIntent> intent =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("$.userId", "$.personId", null, MappingRuleStatus.AUTO)),
            SOURCE,
            TARGET);

    assertTrue(intent.isPresent());
    assertEquals(MappingRuleStatus.PROPOSED, intent.get().rules().getFirst().status());
  }

  @Test
  void expressionStaysUnresolvedWithoutScriptPreference() {
    Optional<MappingIntent> intent =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.name", "$.fullName", "uppercase the name", MappingRuleStatus.USER_DEFINED)),
            MappingContract.unknown(),
            MappingContract.unknown());

    assertTrue(intent.isPresent());
    assertEquals(MappingRuleStatus.UNRESOLVED, intent.get().rules().getFirst().status());
    assertTrue(BriefMappingValidator.blocksApproval(briefWithIntents(List.of(intent.get()))));
  }

  @Test
  void scriptPreferenceAcceptsPlainLanguageExpression() {
    Optional<MappingIntent> intent =
        BriefMappingValidator.validateBoundary(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "$.name", "$.fullName", "uppercase the name", MappingRuleStatus.USER_DEFINED)),
            MappingContract.unknown(),
            MappingContract.unknown(),
            "SCRIPT");

    assertTrue(intent.isPresent());
    assertEquals("SCRIPT", intent.get().implementationPreference());
    assertEquals(MappingRuleStatus.USER_DEFINED, intent.get().rules().getFirst().status());
    assertFalse(BriefMappingValidator.blocksApproval(briefWithIntents(List.of(intent.get()))));
  }

  private static RequirementBrief briefWithIntents(List<MappingIntent> intents) {
    return new RequirementBrief(
            "Orders",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Map OM output to Salesforce request",
            "ref",
            "draft",
            List.of(),
            List.of())
        .withMappingIntents(intents);
  }
}
