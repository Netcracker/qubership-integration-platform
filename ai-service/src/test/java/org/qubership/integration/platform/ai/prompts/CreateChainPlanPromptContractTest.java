package org.qubership.integration.platform.ai.prompts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateChainPlanPromptContractTest {

  @Test
  void createChainPlanPromptDocumentsStepBasedDiscoveryFirstWorkflow() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/create-chain-plan.md");
    String text = Files.readString(path);
    assertTrue(text.contains("Step 1") && text.contains("Service discovery"), text);
    assertTrue(text.contains("Step 1 HITL"), text);
    assertTrue(text.contains("No catalog mutations"), text);
    assertTrue(text.contains("same turn") || text.contains("Same turn"), text);
    assertTrue(text.contains("request") && text.contains("expectedProperties"), text);
    assertTrue(text.contains("Current active chain implementation plan"), text);
    assertTrue(text.contains("Step 2"), text);
    assertTrue(text.contains("Step 3"), text);
    assertTrue(text.contains("Implementation summary"), text);
    assertTrue(text.contains("Workflow"), text);
    assertTrue(text.contains("Role boundaries"), text);
    assertTrue(text.contains("createElementsByJson"), text);
    assertTrue(text.contains("Yes, start implementation"), text);
    assertTrue(text.contains("Integration flow for QIP Chain"), text);
    assertTrue(text.contains("Gate 2") && text.contains("IMPLEMENT_CHAIN"), text);
    assertTrue(text.contains("Agree,Modify plan"), text);
    assertTrue(text.contains("service-call"), text);
    assertTrue(text.contains("ChainImplementationPlan"), text);
    assertTrue(text.contains("user_accepted_unbound"), text);
    assertTrue(text.contains("importRequired"), text);
    assertTrue(
        text.contains("listCatalogOperations") && text.contains("do **not** set `importRequired: true`"),
        text);
    assertTrue(text.contains("already catalog-bound"), text);
    assertTrue(
        text.contains("sourceIdsDocumentId") && text.contains("sourceIdsAttachmentObjectKey"),
        text);
    assertTrue(text.contains("searchCatalogSystems"), text);
    assertTrue(text.contains("listCatalogOperations"), text);
    assertTrue(text.contains("searchFilter"), text);
    assertFalse(text.contains("getCatalogOperations"), text);
    assertTrue(text.contains("integrationSpecificationId"), text);
    assertTrue(text.contains("integrationOperationMethod"), text);
    assertTrue(text.contains("async-api-trigger") && text.contains("http-trigger"), text);
    assertTrue(text.contains("Custom Endpoint") && text.contains("contextPath"), text);
    assertTrue(
        text.contains("systemId")
            && text.contains("specificationId")
            && text.contains("data[]"),
        text);
    assertTrue(text.contains("Short continuations"), text);
    assertTrue(text.contains("operation-id-placeholder"), text);
    assertTrue(text.contains("IDS"), text);
    assertTrue(text.contains("2025.2"), text);
    assertTrue(text.contains("TMF648"), text);
    assertTrue(text.contains("CloudOSS"), text);
    assertTrue(text.contains("describeElementPatchSchema"), text);
    assertFalse(text.contains("publishChainImplementationPlan"), text);
    assertTrue(
        text.contains("fenced") && text.contains("assistant") && text.contains("captures"),
        text);
    assertTrue(text.contains("never") || text.contains("Never"), text);
    assertTrue(text.contains("Next Steps"), text);
    assertTrue(text.contains("pastes a ready-made") || text.contains("ready-made"), text);
    assertTrue(text.contains("Runtime connections"), text);
    assertTrue(text.contains("siblings on the same level"), text);
    assertTrue(text.contains("Inside a container"), text);
    assertTrue(text.contains("Chain root (top level)"), text);
    assertTrue(text.contains("multiple root elements"), text);
    assertTrue(text.contains("Forbidden in `connections[]`"), text);
    assertTrue(text.contains("workflow siblings"), text);
    assertTrue(text.contains("shell element"), text);

    Path branching = Path.of("src/main/resources/prompts/implement-chain-branching.md");
    String branchingText = Files.readString(branching);
    assertTrue(branchingText.contains("container child cannot be created"), branchingText);
  }
}
