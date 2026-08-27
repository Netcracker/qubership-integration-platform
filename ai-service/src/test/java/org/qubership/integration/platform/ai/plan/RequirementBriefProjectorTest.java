package org.qubership.integration.platform.ai.plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
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
}
