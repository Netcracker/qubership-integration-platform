package org.qubership.integration.platform.ai.prompts;

import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.llm.qute.QuteUserMessageEscaping;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CreateChainPlanPromptContractTest {

  @Test
  void createChainPlanRoleSourceIsSafeAfterQuteEscape() throws Exception {
    String escaped = QuteUserMessageEscaping.escapeForAiServiceUserMessage(promptText());
    assertTrue(escaped.contains("\\{Specification found?}"), escaped);
    assertTrue(escaped.contains("\\{ \"fromClientId\""), escaped);
    assertFalse(containsUnescapedOpenBrace(escaped), escaped);
  }

  private static boolean containsUnescapedOpenBrace(String text) {
    for (int i = 0; i < text.length(); i++) {
      if (text.charAt(i) != '{') {
        continue;
      }
      int backslashes = 0;
      for (int j = i - 1; j >= 0 && text.charAt(j) == '\\'; j--) {
        backslashes++;
      }
      if (backslashes % 2 == 0) {
        return true;
      }
    }
    return false;
  }

  @Test
  void createChainPlanPromptDocumentsStepBasedWorkflow() throws Exception {
    String text = promptText();
    assertTrue(text.contains("Step 1") && text.contains("Service discovery"), text);
    assertTrue(text.contains("No catalog mutations"), text);
    assertTrue(text.contains("same turn") || text.contains("Same turn"), text);
    assertTrue(text.contains("Current active chain implementation plan"), text);
    assertTrue(text.contains("Step 2"), text);
    assertTrue(text.contains("Step 3"), text);
    assertTrue(text.contains("Step 2.1"), text);
    assertTrue(text.contains("Step 2.2"), text);
    assertTrue(text.contains("chain-plan-json"), text);
    assertTrue(text.contains("numbered implementation plan") || text.contains("numbered to-do list"), text);
    assertFalse(text.contains("Implementation summary"), text);
    assertTrue(text.contains("GO_BY_THIS_PLAN"), text);
    assertTrue(text.contains("#Step 1") && text.contains("#Step 2") && text.contains("#Step 3"), text);
    assertTrue(text.contains("Role boundaries"), text);
    assertTrue(text.contains("resolve service binding by this exact workflow"), text);
    assertTrue(text.contains("STOP this CREATE_CHAIN_PLAN turn"), text);
    assertTrue(text.contains("next chat turn"), text);
    assertTrue(text.contains("Never") && text.contains("createSystem"), text);
    assertTrue(text.contains("importApiHubSpecToSystem"), text);
    assertTrue(text.contains("createElementsByJson"), text);
    assertTrue(text.contains("Yes, start implementation"), text);
    assertTrue(text.contains("Integration flow for QIP Chain"), text);
    assertTrue(text.contains("Gate 2") && text.contains("IMPLEMENT_CHAIN"), text);
    assertTrue(text.contains("Agree,Modify plan"), text);
    assertFalse(text.contains("publishChainImplementationPlan"), text);
  }

  @Test
  void createChainPlanPromptDocumentsBindingAndImportRules() throws Exception {
    String text = promptText();
    assertTrue(text.contains("Step 1 HITL"), text);
    assertTrue(text.contains("request") && text.contains("expectedProperties"), text);
    assertTrue(text.contains("service-call"), text);
    assertTrue(text.contains("ChainImplementationPlan"), text);
    assertTrue(text.contains("user_accepted_unbound"), text);
    assertTrue(text.contains("importRequired") || text.contains("IMPORT_SPECIFICATION"), text);
    assertTrue(text.contains("recordApiHubImportCandidate"), text);
    assertTrue(text.contains("apiHubPackageId"), text);
    assertTrue(text.contains("catalogSystemType"), text);
    assertTrue(
        text.contains("sourceIdsDocumentId") && text.contains("sourceIdsAttachmentObjectKey"),
        text);
    assertTrue(text.contains("catalogSystemType") && text.contains("INTERNAL"), text);
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
    assertTrue(text.contains("do not") && text.contains("integrationOperationId"), text);
    assertTrue(text.contains("Do not ask the user which ApiHub operations"), text);
  }

  @Test
  void createChainPlanPromptDocumentsCatalogCandidateVerificationRules() throws Exception {
    String text = promptText();
    assertTrue(text.contains("catalog candidate"), text);
    assertTrue(text.contains("not as final service binding"), text);
    assertTrue(text.contains("Select the catalog system only after"), text);
    assertTrue(text.contains("required IDS operations are found"), text);
    assertTrue(text.contains("do not ask about unrelated APIHub packages"), text);
    assertTrue(text.contains("Do not call APIHub tools"), text);
    assertTrue(text.contains("api-packages-list"), text);
  }

  @Test
  void createChainPlanPromptDocumentsCanonicalJsonShape() throws Exception {
    String text = promptText();
    assertTrue(text.contains("Forbidden capture shape"), text);
    assertTrue(text.contains("do not nest") || text.contains("do **not** put `elements[]`"), text);
    assertTrue(text.contains("JSON wire shape only"), text);
    assertTrue(text.contains("Do not copy element count"), text);
    assertFalse(text.contains("\"clientId\": \"http-trigger-1\""), text);
    assertFalse(text.contains("\"clientId\": \"service-call-1\""), text);
    assertTrue(text.contains("Gate 1 is plan approval only"), text);
    assertTrue(text.contains("Agree,Modify plan` only"), text);
  }

  @Test
  void createChainPlanPromptDocumentsDesignGraphDerivationRules() throws Exception {
    String text = promptText();
    assertTrue(text.contains("Step 2.0"), text);
    assertTrue(text.contains("Derive the graph from the IDS or design"), text);
    assertTrue(text.contains("derive every element, `clientId`, child placement"), text);
    assertTrue(text.contains("branching indicators"), text);
    assertTrue(text.contains("JSON has no container element"), text);
    assertTrue(text.contains("condition") && text.contains("try-catch-finally-2"), text);
    assertTrue(text.contains("Do not call `requestConfirmation` when"), text);
  }

  @Test
  void createChainPlanPromptDocumentsApiHubSearchRules() throws Exception {
    String text = promptText();
    assertTrue(text.contains("Short continuations"), text);
    assertTrue(text.contains("IDS"), text);
    assertTrue(text.contains("searchApiOperations"), text);
    assertTrue(text.contains("listApiHubPackages"), text);
    assertTrue(text.contains("APIHub search"), text);
    assertTrue(text.contains("lexical"), text);
    assertTrue(text.contains("omit") && text.contains("release"), text);
    assertTrue(text.contains("TMF648"), text);
    assertTrue(text.contains("CloudOSS"), text);
    assertTrue(text.contains("describeElementPatchSchema"), text);
    assertTrue(
        text.contains("fenced") && text.contains("assistant") && text.contains("captures"),
        text);
    assertTrue(text.contains("never") || text.contains("Never"), text);
    assertTrue(text.contains("Next Steps"), text);
    assertTrue(text.contains("pastes a ready-made") || text.contains("ready-made"), text);
    assertTrue(
        text.contains("connections[] must not be empty")
            || text.contains("must not stay empty")
            || text.contains("must not be empty"),
        text);
  }

  private static String promptText() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/create-chain-plan.md");
    return Files.readString(path);
  }
}
