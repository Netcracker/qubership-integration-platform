package org.qubership.integration.platform.ai.qipknowledge.artifact;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.RequirementFact;
import org.qubership.integration.platform.ai.plan.RequirementFactKind;
import org.qubership.integration.platform.ai.plan.RequirementFactPolarity;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.CatalogBindingHint;

class RequirementBriefTextTest {

  private final ObjectMapper objectMapper = new ObjectMapper();

  @Test
  void formatsBusinessInteractionsAndTransitions() {
    RequirementBrief brief =
        new RequirementBrief(
                "OM to Salesforce WFM",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Consume onTaskStart, create a Salesforce task, publish onTaskResult")
            .withFlow(
                new RequirementFlow(
                    List.of(
                        new RequirementFlow.Interaction(
                            "task-start", RequirementFlow.Direction.INBOUND, "OM", "onTaskStart", ""),
                        new RequirementFlow.Interaction(
                            "create-task",
                            RequirementFlow.Direction.OUTBOUND,
                            "Salesforce",
                            "createTask",
                            ""),
                        new RequirementFlow.Interaction(
                            "task-result",
                            RequirementFlow.Direction.OUTBOUND,
                            "OM",
                            "onTaskResult",
                            "")),
                    List.of(
                        new RequirementFlow.Transition("task-start", "create-task"),
                        new RequirementFlow.Transition("create-task", "task-result"))))
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-result",
                        "create-task",
                        MappingPort.RESPONSE,
                        "task-result",
                        MappingPort.REQUEST,
                        List.of(
                            new MappingIntentRule("", "commandType", "Set to completeTask.")))));

    String formatted = RequirementBriefText.format(brief);

    assertTrue(
        formatted.contains(
            "interactionId=task-start direction=INBOUND participant=OM operation=onTaskStart"),
        formatted);
    assertTrue(
        formatted.contains(
            "interactionId=create-task direction=OUTBOUND participant=Salesforce operation=createTask"),
        formatted);
    assertTrue(
        formatted.contains(
            "interactionId=task-result direction=OUTBOUND participant=OM operation=onTaskResult"),
        formatted);
    assertTrue(formatted.contains("task-start -> create-task"), formatted);
    assertTrue(formatted.contains("create-task -> task-result"), formatted);
    int interactionsAt = formatted.indexOf("Business interactions:");
    int transitionsAt = formatted.indexOf("Business transitions:");
    String interactions = formatted.substring(interactionsAt, transitionsAt);
    assertFalse(interactions.contains("publish"), interactions);
    assertFalse(interactions.contains("subscribe"), interactions);
    assertFalse(interactions.contains("completeTask"), interactions);
    int mappingsAt = formatted.indexOf("Mapping intents:");
    assertTrue(mappingsAt > transitionsAt, formatted);
    assertTrue(formatted.substring(mappingsAt).contains("completeTask"), formatted);
  }

  @Test
  void formatsStructuredBriefFields() {
    RequirementBrief brief =
        new RequirementBrief(
            "Call customer API",
            List.of("packageId: pkg-1", "operationId: getCustomer"),
            List.of("protocol: REST"),
            List.of("API Hub service not resolved"),
            List.of(),
            "Lookup customer by id");

    String formatted = RequirementBriefText.format(brief);

    assertTrue(formatted.contains("Goal: Call customer API"));
    assertTrue(formatted.contains("Summary: Lookup customer by id"));
    assertTrue(formatted.contains("Inputs:"));
    assertTrue(formatted.contains("- packageId: pkg-1"));
    assertTrue(formatted.contains("- operationId: getCustomer"));
    assertTrue(formatted.contains("Constraints:"));
    assertTrue(formatted.contains("- protocol: REST"));
    assertTrue(formatted.contains("Assumptions:"));
    assertTrue(formatted.contains("- API Hub service not resolved"));
  }

  @Test
  void showsEachFactIdSoDesignCaptureCanCopySourceFactIds() {
    RequirementFact fact =
        new RequirementFact(
            "fact-trigger",
            RequirementFactPolarity.POSITIVE,
            RequirementFactKind.ENDPOINT,
            "http-trigger",
            "Internal HTTP GET /health-proxy trigger");
    RequirementBrief brief =
        new RequirementBrief(
            "Proxy inventory",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Proxy inventory health data",
            null,
            null,
            List.of(fact));

    String formatted = RequirementBriefText.format(brief);

    assertTrue(
        formatted.contains(
            "[POSITIVE] Internal HTTP GET /health-proxy trigger sourceFactId=fact-trigger"),
        formatted);
  }

  /** The capability key used to lead the line, and a model copied it as the entry point id. */
  @Test
  void labelsTheEntryPointIdSoItCannotBeConfusedWithTheCapabilityKey() {
    RequirementBrief brief =
        new RequirementBrief("Proxy inventory", List.of(), List.of(), List.of(), List.of(), "")
            .withFacts(List.of());
    RequirementBrief withEntryPoint =
        new RequirementBrief(
            brief.goal(),
            brief.inputs(),
            brief.constraints(),
            brief.assumptions(),
            brief.citations(),
            "Proxy inventory health data",
            null,
            null,
            List.of(),
            List.of(
                new RequirementEntryPoint(
                    "fact-trigger", "fact-trigger", "http-trigger", "", "GET", "/health-proxy", "")),
            List.of(),
            List.of(),
            List.of());

    String formatted = RequirementBriefText.format(withEntryPoint);

    assertTrue(
        formatted.contains("- entryPointId=fact-trigger capabilityKey=http-trigger"), formatted);
  }

  @Test
  void returnsEmptyForNullBrief() {
    assertEquals("", RequirementBriefText.format(null));
  }

  @Test
  void formatsTypedMappingIntentsForGeneratorPrompts() {
    RequirementBrief brief =
        new RequirementBrief(
                "Proxy inventory",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Map an HTTP request to an inventory call",
                null,
                null,
                List.of())
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-init",
                        "step-trigger",
                        MappingPort.OUTPUT,
                        "step-call",
                        MappingPort.REQUEST,
                        List.of(
                            new MappingIntentRule(
                                "$.request.id",
                                "$.headers.X-Request-Id",
                                "string(value)")))));

    String formatted = RequirementBriefText.format(brief);

    assertTrue(
        formatted.contains("map-init step-trigger/OUTPUT -> step-call/REQUEST"), formatted);
    assertTrue(
        formatted.contains(
            "$.request.id -> $.headers.X-Request-Id | expression: string(value)"),
        formatted);
  }

  @Test
  void legacyJsonWithoutDataMappingsDecodesToEmptyList() throws Exception {
    String legacyJson =
        """
        {
          "goal": "Call customer API",
          "inputs": ["packageId: pkg-1"],
          "constraints": [],
          "assumptions": [],
          "citations": [],
          "summary": "Lookup customer by id"
        }
        """;

    RequirementBrief brief = objectMapper.readValue(legacyJson, RequirementBrief.class);

    assertTrue(brief.entryPoints().isEmpty());
    assertTrue(brief.serviceCalls().isEmpty());
    assertTrue(brief.mappingIntents().isEmpty());
    assertEquals("Call customer API", brief.goal());
  }

  @Test
  void formatsMappingIntentRulesWithStatus() {
    RequirementBrief brief =
        new RequirementBrief(
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
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-init",
                        "trigger-1",
                        MappingPort.OUTPUT,
                        "call-1",
                        MappingPort.REQUEST,
                        List.of(
                            new MappingIntentRule(
                                "$.orderId", "$.orderId", null, MappingRuleStatus.AUTO),
                            new MappingIntentRule(
                                "$.userId", "$.personId", null, MappingRuleStatus.PROPOSED),
                            new MappingIntentRule(
                                "$.name", "$.fullName", "trim(value)", MappingRuleStatus.USER_DEFINED),
                            new MappingIntentRule(
                                "", "$.personId", null, MappingRuleStatus.UNRESOLVED)))));

    String formatted = RequirementBriefText.format(brief);

    assertTrue(formatted.contains("map-init trigger-1/OUTPUT -> call-1/REQUEST"), formatted);
    assertTrue(formatted.contains("AUTO $.orderId -> $.orderId"), formatted);
    assertTrue(formatted.contains("PROPOSED $.userId -> $.personId"), formatted);
    assertTrue(formatted.contains("USER_DEFINED $.name -> $.fullName"), formatted);
    assertTrue(formatted.contains("UNRESOLVED  -> $.personId"), formatted);
  }

  @Test
  void mappingIntentJsonRoundTripsRuleStatus() throws Exception {
    RequirementBrief original =
        new RequirementBrief(
                "Orders",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                "Map one boundary",
                "ref",
                "draft",
                List.of(),
                List.of())
            .withMappingIntents(
                List.of(
                    new MappingIntent(
                        "map-init",
                        "trigger-1",
                        MappingPort.OUTPUT,
                        "call-1",
                        MappingPort.REQUEST,
                        List.of(
                            new MappingIntentRule(
                                "$.userId", "$.personId", null, MappingRuleStatus.PROPOSED)))));

    RequirementBrief restored =
        objectMapper.readValue(objectMapper.writeValueAsString(original), RequirementBrief.class);

    assertEquals(1, restored.mappingIntents().size());
    assertEquals(
        MappingRuleStatus.PROPOSED, restored.mappingIntents().getFirst().rules().getFirst().status());
    assertEquals("$.personId", restored.mappingIntents().getFirst().rules().getFirst().targetPath());
  }

  @Test
  void formatsCallIdsAndCatalogIdentities() {
    Instant observedAt = Instant.parse("2026-08-27T12:00:00Z");
    CatalogBindingHint hint =
        new CatalogBindingHint(
            "2",
            "call-om-result",
            "fact-om",
            "onTaskResult",
            "sys-om",
            "sg-om",
            "spec-om",
            "op-shared",
            "http",
            "POST",
            "/tasks/result",
            "2024.4",
            observedAt,
            "evidence-om");
    RequirementBrief brief =
        new RequirementBrief(
            "Call OM then Salesforce WFM",
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "Two outbound calls",
            "ref",
            "draft",
            List.of(
                new RequirementFact(
                    "fact-om",
                    RequirementFactPolarity.POSITIVE,
                    RequirementFactKind.SERVICE_CALL,
                    "",
                    "Call Order Management onTaskResult",
                    "Order Management",
                    "onTaskResult",
                    "",
                    "",
                    "",
                    "call-om-result")),
            List.of(),
            List.of(
                new RequirementServiceCall(
                    "call-om-result",
                    "fact-om",
                    "Order Management",
                    "onTaskResult",
                    hint),
                new RequirementServiceCall(
                    "call-unbound", "fact-unbound", "Billing", "createInvoice")),
            List.of(),
            List.of());

    String formatted = RequirementBriefText.format(brief);

    assertTrue(formatted.contains("call-om-result"), formatted);
    assertTrue(formatted.contains("systemId=sys-om"), formatted);
    assertTrue(formatted.contains("specificationGroupId=sg-om"), formatted);
    assertTrue(formatted.contains("specificationId=spec-om"), formatted);
    assertTrue(formatted.contains("integrationOperationId=op-shared"), formatted);
    assertTrue(formatted.contains("protocol=http"), formatted);
    assertTrue(formatted.contains("method=POST"), formatted);
    assertTrue(formatted.contains("path=/tasks/result"), formatted);
    assertTrue(formatted.contains("call-unbound"), formatted);
    assertTrue(formatted.contains("Billing"), formatted);
    assertFalse(formatted.contains("systemId=\n"), formatted);
  }
}
