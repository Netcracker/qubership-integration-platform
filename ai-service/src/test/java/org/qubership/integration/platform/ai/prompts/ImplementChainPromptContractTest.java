package org.qubership.integration.platform.ai.prompts;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ImplementChainPromptContractTest {

  @Test
  void implementChainPromptDocumentsMaterializeOnlyWithoutImport() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/implement-chain.md");
    String text = Files.readString(path);
    assertTrue(text.contains("never imports ApiHub"), text);
    assertTrue(text.contains("createElementsByJson"), text);
    assertTrue(text.contains("IMPORT_SPECIFICATION"), text);
    assertTrue(text.contains("do not") && text.contains("importApiHubSpecToSystem"), text);
  }

  @Test
  void implementChainPromptDocumentsCatalogSetupPlaybook() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/implement-chain.md");
    String text = Files.readString(path);
    assertTrue(text.contains("real catalog binding ids"), text);
    assertTrue(text.contains("createElementsByJson"), text);
    assertTrue(text.contains("re-run APIHub search"), text);
  }

  @Test
  void implementChainPromptDocumentsSchemaToolsAndPatchJsonShape() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/implement-chain.md");
    String text = Files.readString(path);
    assertTrue(text.contains("Gate 1") && text.contains("Gate 2"), text);
    assertTrue(text.contains("describeElementProperty"), text);
    assertTrue(text.contains("describeElementPatchSchema"), text);
    assertTrue(text.contains("serialized JSON string"), text);
    assertTrue(text.contains("server-side"), text);
    assertTrue(text.contains("qip-ui-labels-to-patch-keys.md"), text);
    assertTrue(text.contains("updateElement"), text);
    assertTrue(text.contains("branching appendix"), text);
  }

  @Test
  void implementChainBranchingSupplementExistsAndCoversBranchingTypes() throws Exception {
    Path path = Path.of("src/main/resources/prompts/implement-chain-branching.md");
    String text = Files.readString(path);
    assertTrue(text.contains("split-2"), text);
    assertTrue(text.contains("try-catch-finally-2"), text);
    assertTrue(text.contains("circuit-breaker-2"), text);
    assertTrue(text.contains("condition"), text);
    assertTrue(text.contains("loop-2"), text);
  }

  @Test
  void implementChainPromptDocumentsPropertyFailureRepairWithoutImport() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/implement-chain.md");
    String text = Files.readString(path);
    assertTrue(text.contains("5. **Repair"), text);
    assertTrue(text.contains("fix binding") || text.contains("Repair only"), text);
    assertTrue(text.contains("IMPORT_SPECIFICATION"), text);
  }

  @Test
  void implementChainPromptDocumentsExecutionOrderAndPlanBindings() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/implement-chain.md");
    String text = Files.readString(path);
    assertTrue(text.contains("Execution process"), text);
    assertTrue(text.contains("Implementation summary"), text);
    assertTrue(text.contains("createChain"), text);
    assertTrue(text.contains("importApiHubSpecToSystem"), text);
    assertTrue(text.contains("createElementsByJson"), text);
    assertTrue(text.contains("bindingStatus"), text);
    assertTrue(text.contains("IMPORT_SPECIFICATION"), text);
    assertTrue(text.contains("CREATE_CHAIN_PLAN"), text);
    assertTrue(text.contains("4. **Verify**"), text);
    assertTrue(text.contains("5. **Repair"), text);
    assertTrue(text.contains("6. **Report status**"), text);
    assertTrue(text.contains("getDependencies"), text);
  }

  @Test
  void mergedImplementChainSystemPromptOnClasspathContainsSchemaToolsAndBranchingAppendix()
      throws Exception {
    var url =
        Thread.currentThread()
            .getContextClassLoader()
            .getResource("prompts/implement-chain-system.md");
    assertNotNull(
        url,
        "Merged implement-chain system prompt must be on classpath after Maven merge-system-prompts"
            + " writes target/classes/prompts/implement-chain-system.md");
    String text = Files.readString(Path.of(url.toURI()));
    assertTrue(text.contains("describeElementProperty"), text);
    assertTrue(text.contains("describeElementPatchSchema"), text);
    assertTrue(text.contains("QIP Chain Structural Patterns"), text);
    assertTrue(text.contains("\\{"), "branching JSON must be Qute-escaped in merged system prompt");
  }

  @Test
  void implementChainPromptDocumentsOpenDebtHitlAndDismissTool() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/implement-chain.md");
    String text = Files.readString(path);
    assertTrue(text.contains("Open plan debt"), text);
    assertTrue(text.contains("markPlanOpenDebtIgnoredByUser"), text);
    assertTrue(text.contains("openDebtFingerprint"), text);
  }

  @Test
  void implementChainPromptDocumentsDefaultsPolicy() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/implement-chain.md");
    String text = Files.readString(path);
    assertTrue(text.contains("default"), text);
    assertTrue(text.contains("omit"), text);
  }

  @Test
  void implementChainPromptDocumentsRepairBudget() throws Exception {
    Path path = Path.of("src/main/resources/prompts/roles/implement-chain.md");
    String text = Files.readString(path);
    assertTrue(text.contains("repair budget exhausted"), text.toLowerCase());
    assertTrue(text.contains("chainId") && text.contains("already set"), text);
  }

  @Test
  void mergedImplementChainSystemPromptContainsHitlRepairPolicy() throws Exception {
    var url =
        Thread.currentThread()
            .getContextClassLoader()
            .getResource("prompts/implement-chain-system.md");
    assertNotNull(
        url,
        "Merged implement-chain system prompt must be on classpath after Maven merge-system-prompts"
            + " writes target/classes/prompts/implement-chain-system.md");
    String text = Files.readString(Path.of(url.toURI()));
    assertTrue(text.contains("describeElementProperty"), text);
    assertTrue(text.contains("describeElementPatchSchema"), text);
  }

  @Test
  void embeddedRagDocsBundlePresentOnClasspath() {
    assertNotNull(
        Thread.currentThread()
            .getContextClassLoader()
            .getResource("docs/qip-catalog-patch-defaults.md"),
        "docs/ must be packaged for QipDocumentIngestor (see .gitignore /docs vs resources/docs)");
    assertNotNull(
        Thread.currentThread()
            .getContextClassLoader()
            .getResource("docs/qip-ui-labels-to-patch-keys.md"),
        "UI→PATCH mapping doc must be packaged with docs/");
  }
}
