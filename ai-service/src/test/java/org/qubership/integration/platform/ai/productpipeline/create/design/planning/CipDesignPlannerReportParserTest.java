package org.qubership.integration.platform.ai.productpipeline.create.design.planning;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.productpipeline.capability.StageOutcomeClass;
import org.qubership.integration.platform.ai.productpipeline.create.design.model.DesignPlanReport;

class CipDesignPlannerReportParserTest {

  private final CipDesignPlannerReportParser parser = new CipDesignPlannerReportParser();

  @Test
  void preservesOriginalStepTextFromFixture() throws Exception {
    String report = fixtureReport();
    String originalFirstStep =
        "Analyze requirements and name chain Orders"
            + " (cip-requirement-analyzer + cip-naming-generator)";

    ParsedPlannerReport parsed = parser.parse(report);

    assertEquals(originalFirstStep, parsed.steps().getFirst().reportText());
    assertEquals(
        java.util.List.of("cip-requirement-analyzer", "cip-naming-generator"),
        parsed.steps().getFirst().owningSkillIds());
    assertEquals(
        ParsedPlannerReport.OwnerKind.APIHUB_TOOL, parsed.steps().get(1).ownerKind());
    assertEquals(
        java.util.List.of("search_rest_api_operations"),
        parsed.steps().get(1).toolOperationRefs());
  }

  @Test
  void rejectsReportWithoutApprovalSentence() {
    String reportWithNoApprovalSentence =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Validate the assembled chain (cip-chain-validator)
        """;

    PlannerReportFormatException ex =
        assertThrows(
            PlannerReportFormatException.class, () -> parser.parse(reportWithNoApprovalSentence));
    assertTrue(ex.getMessage().contains("approval sentence"));
  }

  @Test
  void rejectsEmptyOrUnnumberedReport() {
    PlannerReportFormatException ex =
        assertThrows(
            PlannerReportFormatException.class,
            () ->
                parser.parse(
                    "If you agree, reply **Agree** or **Execute plan** to proceed."));
    assertTrue(ex.getMessage().contains("numbered"));
  }

  @Test
  void retriesOnceOnFormatFailureThenReturnsValidReport() {
    DesignProcessSkillRunner runner = mock(DesignProcessSkillRunner.class);
    CipDesignPlannerAdapter adapter = new CipDesignPlannerAdapter(runner, parser);

    String conversationId = "conv-1";
    String input = "IDS + release 2024.4";
    String invalidReport = "1. Generate HTTP Trigger element (cip-trigger-generator)\n";
    String validReport = validMinimalReport();
    String firstFormatFailure =
        assertThrows(PlannerReportFormatException.class, () -> parser.parse(invalidReport))
            .getMessage();

    String pin = "pinned-skill-hash";
    when(runner.runOnce(conversationId, "cip-design-planner", input, Optional.empty(), pin))
        .thenReturn(invalidReport);
    when(runner.runOnce(
            conversationId,
            "cip-design-planner",
            input,
            Optional.of(firstFormatFailure),
            pin))
        .thenReturn(validReport);

    PlannerRequest request = new PlannerRequest(conversationId, input, pin);
    DesignPlanReport expectedReport = new DesignPlanReport("1", validReport);

    assertEquals(expectedReport, adapter.plan(request));
    verify(runner, times(2))
        .runOnce(eq(conversationId), eq("cip-design-planner"), eq(input), any(), eq(pin));
  }

  @Test
  void mapsDoubleFormatFailureToContractFailureWithoutThirdAttempt() {
    DesignProcessSkillRunner runner = mock(DesignProcessSkillRunner.class);
    CipDesignPlannerAdapter adapter = new CipDesignPlannerAdapter(runner, parser);

    String conversationId = "conv-2";
    String input = "IDS";
    String invalidReport = "not a plan";
    String pin = "pinned-skill-hash";

    when(runner.runOnce(
            eq(conversationId), eq("cip-design-planner"), eq(input), any(), eq(pin)))
        .thenReturn(invalidReport);

    PlannerRequest request = new PlannerRequest(conversationId, input, pin);

    PlannerContractException ex =
        assertThrows(PlannerContractException.class, () -> adapter.plan(request));
    assertEquals(StageOutcomeClass.CONTRACT_FAILURE, ex.outcomeClass());
    verify(runner, times(2))
        .runOnce(eq(conversationId), eq("cip-design-planner"), eq(input), any(), eq(pin));
  }

  @Test
  void retriesApiHubStepsForCatalogOnlyInput() {
    DesignProcessSkillRunner runner = mock(DesignProcessSkillRunner.class);
    CipDesignPlannerAdapter adapter = new CipDesignPlannerAdapter(runner, parser);
    String conversationId = "conv-catalog-only";
    String input = "Binding resolution policy: CATALOG_ONLY\nReuse the existing catalog binding.";
    String pin = "pinned-skill-hash";
    String corrected =
        """
        1. Generate HTTP Trigger element (cip-trigger-generator)
        2. Configure the existing Petstore Ext.getInventory binding (cip-service-call-generator)
        3. Generate execution structure and element ordering (cip-structure-generator)
        4. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        5. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
            .trim();

    when(runner.runOnce(conversationId, "cip-design-planner", input, Optional.empty(), pin))
        .thenReturn(validMinimalReport());
    when(runner.runOnce(
            eq(conversationId),
            eq("cip-design-planner"),
            eq(input),
            org.mockito.ArgumentMatchers.argThat(
                failure ->
                    failure.isPresent()
                        && failure.get().contains("CATALOG_ONLY forbids APIHub planner steps")),
            eq(pin)))
        .thenReturn(corrected);

    assertEquals(corrected, adapter.plan(new PlannerRequest(conversationId, input, pin)).markdown());
    verify(runner, times(2))
        .runOnce(eq(conversationId), eq("cip-design-planner"), eq(input), any(), eq(pin));
  }

  private static String fixtureReport() throws Exception {
    return new String(
            Objects.requireNonNull(
                    CipDesignPlannerReportParserTest.class.getResourceAsStream(
                        "/product-pipelines/design/cip-design-planner-report.md"))
                .readAllBytes(),
            StandardCharsets.UTF_8)
        .trim();
  }

  private static String validMinimalReport() {
    return """
        1. Analyze requirements and name chain Orders (cip-requirement-analyzer + cip-naming-generator)
        2. Find API Orders API for Orders Service in APIHub for version 2024.4 (APIHub MCP search_rest_api_operations)
        3. Get API operation specification Orders API for Orders Service in APIHub (APIHub MCP get_rest_api_operations_specification)
        4. Resolve External integration target Orders Service from the retrieved spec (binding for cip-service-call-generator)
        5. Generate HTTP Trigger element with interface Orders API (cip-trigger-generator)
        6. Generate Service Call element for Orders Service.createOrder bound to the retrieved spec (cip-service-call-generator)
        7. Generate Script element for Initialization (cip-script-generator)
        8. Generate Script element for Response (cip-script-generator)
        9. Generate execution structure and element ordering (cip-structure-generator)
        10. Connect steps trigger → service-call in the execution structure (cip-structure-generator)
        11. Assemble generated-chain.cip.yaml + scripts (cip-chain-assembler)
        12. Validate the assembled chain (cip-chain-validator)
        If you agree, reply **Agree** or **Execute plan** to proceed.
        """
        .trim();
  }
}
