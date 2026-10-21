package org.qubership.integration.platform.ai.chain.presentation;

import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ChainCatalogViewServiceTest {

  private ChainCatalogViewService viewService;

  @BeforeEach
  void setUp() {
    viewService = new ChainCatalogViewService(new ObjectMapper());
  }

  @Test
  void formatMermaidFlowchartIncludesDependencies() {
    ChainCatalogFacts facts = sampleFacts();

    String mermaid = viewService.formatMermaidFlowchart(facts);

    assertTrue(mermaid.contains("flowchart TD"));
    assertTrue(mermaid.contains("HTTP Trigger"));
    assertTrue(mermaid.contains("-->"));
  }

  @Test
  void formatTreeShowsHierarchy() {
    ChainCatalogFacts facts =
        new ChainCatalogFacts(
            "chain-1",
            "Demo",
            "",
            2,
            0,
            "",
            List.of(
                new ChainCatalogElement(
                    "el-parent", "try-catch-finally-2", "Wrapper", null, null, null, null, Map.of()),
                new ChainCatalogElement(
                    "el-try", "try-2", "Try", "el-parent", null, null, null, Map.of())),
            List.of(),
            "built_in_catalog");

    String tree = viewService.formatTree(facts);

    assertTrue(tree.contains("Wrapper"));
    assertTrue(tree.contains("Try"));
  }

  private static ChainCatalogFacts sampleFacts() {
    return new ChainCatalogFacts(
        "chain-1",
        "Greetings",
        "",
        2,
        1,
        "HTTP Trigger (http-trigger)",
        List.of(
            new ChainCatalogElement(
                "el-trigger", "http-trigger", "HTTP Trigger", null, null, null, null, Map.of()),
            new ChainCatalogElement(
                "el-script", "script", "Parse", null, null, null, null, Map.of())),
        List.of(new ChainCatalogDependency("el-trigger", "el-script")),
        "built_in_catalog");
  }
}
