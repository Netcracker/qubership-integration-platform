package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingRuleStatus;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementDataMapping;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementServiceCall;

class RequirementBriefProjectorTest {

  @Test
  void separatesEntryPointsFromFactKindUsingCatalogTriggerCapability() {
    RequirementFact kafka =
        new RequirementFact(
            "trigger-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.CAPABILITY,
            "kafka-trigger-2",
            "Consume user events",
            "",
            "consumeUserEvent",
            "user/events",
            "",
            "");
    RequirementFact call =
        new RequirementFact(
            "call-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.SERVICE_CALL,
            "http-service-call",
            "Look up a pet",
            "Petstore Ext",
            "getPetById",
            "",
            "",
            "");
    RequirementFact behavior =
        new RequirementFact(
            "req-1",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.BEHAVIOR,
            "",
            "Keep the original payload");
    RequirementBrief projected =
        RequirementBriefProjector.project(
            new RequirementBrief(
                "Kafka pet lookup",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Consume events and look up a pet",
                "ref",
                "draft",
                List.of(kafka, call, behavior),
                List.of()));

    assertEquals(
        List.of(
            new RequirementEntryPoint(
                "trigger-1",
                "trigger-1",
                "kafka-trigger-2",
                "user/events",
                "",
                "",
                "consumeUserEvent")),
        projected.entryPoints());
    assertEquals(
        List.of(new RequirementServiceCall("call-1", "call-1", "Petstore Ext", "getPetById")),
        projected.serviceCalls());
    assertEquals(List.of(behavior), projected.requirements());
    assertTrue(projected.mappingIntents().isEmpty());
    assertTrue(projected.dataMappings().isEmpty());
  }

  @Test
  void projectsExplicitMappingsAndOmitsPassThroughRowsFromIntents() {
    RequirementDataMapping explicit =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.petId", null)),
            List.of("trigger-1"));
    RequirementDataMapping passThrough =
        new RequirementDataMapping(
            "map-resp",
            RequirementDataMapping.Stage.RESPONSE,
            "call-1",
            "trigger-1",
            RequirementDataMapping.Mode.PASS_THROUGH,
            List.of(),
            List.of("call-1"));
    RequirementBrief projected =
        RequirementBriefProjector.project(
            new RequirementBrief(
                "Orders",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Map the request only",
                "ref",
                "draft",
                List.of(),
                List.of(explicit, passThrough)));

    assertEquals(
        List.of(
            new MappingIntent(
                "map-init",
                "trigger-1",
                MappingPort.OUTPUT,
                "call-1",
                MappingPort.REQUEST,
                List.of(
                    new MappingIntentRule(
                        "$.id", "$.petId", null, MappingRuleStatus.PROPOSED)))),
        projected.mappingIntents());
    assertEquals(List.of(explicit, passThrough), projected.dataMappings());
  }

  @Test
  void groupsFiveRulesAtOneBoundaryAndOmitsIdentityOnlyAuto() {
    RequirementDataMapping fiveRules =
        new RequirementDataMapping(
            "map-init",
            RequirementDataMapping.Stage.INITIALIZATION,
            "trigger-1",
            "call-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(
                new RequirementDataMapping.Rule("$.orderId", "$.orderId", null),
                new RequirementDataMapping.Rule("$.userId", "$.personId", null),
                new RequirementDataMapping.Rule("$.name", "$.fullName", null),
                new RequirementDataMapping.Rule("$.createdAt", "$.registrationDate", null),
                new RequirementDataMapping.Rule("$.status", "$.state", null)),
            List.of("trigger-1"));
    RequirementDataMapping identityOnly =
        new RequirementDataMapping(
            "map-resp",
            RequirementDataMapping.Stage.RESPONSE,
            "call-1",
            "trigger-1",
            RequirementDataMapping.Mode.EXPLICIT,
            List.of(new RequirementDataMapping.Rule("$.id", "$.id", null)),
            List.of("call-1"));
    RequirementBrief projected =
        RequirementBriefProjector.project(
            new RequirementBrief(
                "Orders",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Map the request only",
                "ref",
                "draft",
                List.of(),
                List.of(fiveRules, identityOnly)));

    assertEquals(1, projected.mappingIntents().size());
    MappingIntent intent = projected.mappingIntents().getFirst();
    assertEquals("map-init", intent.mappingIntentId());
    assertEquals(MappingPort.OUTPUT, intent.sourcePort());
    assertEquals(MappingPort.REQUEST, intent.targetPort());
    assertEquals(5, intent.rules().size());
    assertEquals(MappingRuleStatus.AUTO, intent.rules().getFirst().status());
    assertEquals(MappingRuleStatus.PROPOSED, intent.rules().get(1).status());
  }

  @Test
  void preservesExistingMappingIntentsInsteadOfReplacingThemFromDataMappings() {
    MappingIntent unresolved =
        new MappingIntent(
            "map-init",
            "trigger-1",
            MappingPort.OUTPUT,
            "call-1",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("", "$.personId", null, MappingRuleStatus.UNRESOLVED)));
    RequirementBrief projected =
        RequirementBriefProjector.project(
            new RequirementBrief(
                    "Orders",
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of(),
                    "Map the request only",
                    "ref",
                    "draft",
                    List.of(),
                    List.of())
                .withMappingIntents(List.of(unresolved)));

    assertEquals(List.of(unresolved), projected.mappingIntents());
  }

  @Test
  void projectsBindingForEachServiceCall() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    RequirementFact omFact =
        serviceCallFact("fact-om", "call-om-result", "Order Management", "onTaskResult");
    RequirementFact wfmFact =
        serviceCallFact("fact-wfm", "call-wfm-create-task", "Salesforce WFM", "createTask");
    CatalogBindingHint omHint =
        catalogHint(
            "call-om-result",
            "fact-om",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-om",
            observedAt);
    CatalogBindingHint wfmHint =
        catalogHint(
            "call-wfm-create-task",
            "fact-wfm",
            "createTask",
            "sys-wfm",
            "sg-wfm",
            "spec-wfm",
            "op-wfm",
            observedAt);
    RequirementServiceCall omCall =
        new RequirementServiceCall(
            "call-om-result", "fact-om", "Order Management", "onTaskResult", omHint);
    RequirementServiceCall wfmCall =
        new RequirementServiceCall(
            "call-wfm-create-task", "fact-wfm", "Salesforce WFM", "createTask", wfmHint);

    RequirementBrief projected =
        RequirementBriefProjector.project(
            briefWithCalls(List.of(omFact, wfmFact), List.of(omCall, wfmCall)));

    assertEquals(List.of(omCall, wfmCall), projected.serviceCalls());
    assertEquals(
        "op-om", projected.serviceCalls().get(0).catalogBinding().integrationOperationId());
    assertEquals(
        "op-wfm", projected.serviceCalls().get(1).catalogBinding().integrationOperationId());
  }

  @Test
  void preservesDuplicateOperationBindingsByCallId() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    RequirementFact firstFact =
        serviceCallFact("fact-om", "call-om-result", "Order Management", "onTaskResult");
    RequirementFact secondFact =
        serviceCallFact("fact-om-again", "call-om-again", "Order Management", "onTaskResult");
    CatalogBindingHint firstHint =
        catalogHint(
            "call-om-result",
            "fact-om",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-shared",
            observedAt);
    CatalogBindingHint secondHint =
        catalogHint(
            "call-om-again",
            "fact-om-again",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-shared",
            observedAt);
    RequirementServiceCall firstCall =
        new RequirementServiceCall(
            "call-om-result", "fact-om", "Order Management", "onTaskResult", firstHint);
    RequirementServiceCall secondCall =
        new RequirementServiceCall(
            "call-om-again", "fact-om-again", "Order Management", "onTaskResult", secondHint);

    RequirementBrief projected =
        RequirementBriefProjector.project(
            briefWithCalls(List.of(firstFact, secondFact), List.of(firstCall, secondCall)));

    assertEquals(List.of(firstCall, secondCall), projected.serviceCalls());
    assertEquals("call-om-result", projected.serviceCalls().get(0).serviceCallId());
    assertEquals("call-om-again", projected.serviceCalls().get(1).serviceCallId());
    assertEquals("op-shared", projected.serviceCalls().get(0).catalogBinding().integrationOperationId());
    assertEquals("op-shared", projected.serviceCalls().get(1).catalogBinding().integrationOperationId());
  }

  private static RequirementBrief briefWithCalls(
      List<RequirementFact> facts, List<RequirementServiceCall> serviceCalls) {
    return new RequirementBrief(
        "OM then WFM",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "Call OM then Salesforce WFM",
        "ref",
        "draft",
        facts,
        List.of(),
        List.of(),
        serviceCalls,
        List.of(),
        List.of());
  }

  private static RequirementFact serviceCallFact(
      String sourceFactId, String serviceCallId, String participant, String operation) {
    return new RequirementFact(
        sourceFactId,
        RequirementFactPolarity.POSITIVE,
        RequirementFactKind.SERVICE_CALL,
        "",
        "Call " + participant + " " + operation,
        participant,
        operation,
        "",
        "",
        "",
        serviceCallId);
  }

  private static CatalogBindingHint catalogHint(
      String serviceCallId,
      String sourceFactId,
      String operationQuery,
      String systemId,
      String specificationGroupId,
      String specificationId,
      String integrationOperationId,
      Instant observedAt) {
    return new CatalogBindingHint(
        "2",
        serviceCallId,
        sourceFactId,
        operationQuery,
        systemId,
        specificationGroupId,
        specificationId,
        integrationOperationId,
        "http",
        "POST",
        "/tasks",
        "2024.4",
        observedAt,
        "evidence-" + serviceCallId);
  }
}
