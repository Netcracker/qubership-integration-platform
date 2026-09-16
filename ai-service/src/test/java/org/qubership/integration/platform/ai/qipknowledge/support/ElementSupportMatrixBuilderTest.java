package org.qubership.integration.platform.ai.qipknowledge.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerNodeExecutionMode;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineNode;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

class ElementSupportMatrixBuilderTest {

  @Test
  void classifiesSupportFromSchemaDescriptorContractAndOwnership(@TempDir Path repoRoot)
      throws Exception {
    addRuntimeDescriptor(repoRoot, "reuse");
    addRuntimeDescriptor(repoRoot, "reuse-reference");
    addRuntimeDescriptor(repoRoot, "script");

    CompilerPipelineIndex pipelineIndex =
        pipelineIndex(
            node("composition", Set.of("reuse"), Map.of()),
            node(
                "composition-reference",
                Set.of("reuse-reference"),
                Map.of("reuse-reference", Set.of("elementId"))),
            node(
                "script",
                Set.of("script"),
                Map.of("script", Set.of("script", "mappingCoverage"))),
            node("database", Set.of("dbaas"), Map.of("dbaas", Set.of("query"))));
    CompilerContract contract = contractCovering("reuse", "script");

    ElementSupportMatrix matrix =
        new ElementSupportMatrixBuilder().build(repoRoot, pipelineIndex, contract);

    ElementSupportEntry reuse = matrix.require("reuse");
    assertEquals(ElementSupportStatus.SUPPORTED, reuse.status());
    assertEquals(List.of("composition"), reuse.ownerSkillIds());

    ElementSupportEntry script = matrix.require("script");
    assertEquals(ElementSupportStatus.SUPPORTED, script.status());
    assertTrue(script.invalidOwnershipPropertiesBySkill().isEmpty());

    ElementSupportEntry reuseReference = matrix.require("reuse-reference");
    assertEquals(ElementSupportStatus.PARTIAL, reuseReference.status());
    assertEquals(
        List.of("elementId"),
        reuseReference.invalidOwnershipPropertiesBySkill().get("composition-reference"));
    assertEquals(List.of("reuseElementId"), reuseReference.unownedRequiredProperties());
    assertTrue(reuseReference.reasons().contains("compiler contract coverage missing"));

    ElementSupportEntry dbaas = matrix.require("dbaas");
    assertEquals(ElementSupportStatus.UNSUPPORTED, dbaas.status());
    assertFalse(dbaas.schemaPresent());
    assertFalse(dbaas.runtimeDescriptorPresent());

    ElementSupportEntry container = matrix.require("container");
    assertEquals(ElementSupportStatus.UNSUPPORTED, container.status());
    assertTrue(container.schemaPresent());
    assertFalse(container.runtimeDescriptorPresent());
  }

  private static void addRuntimeDescriptor(Path repoRoot, String elementType) throws Exception {
    Path directory =
        repoRoot.resolve("runtime-catalog/src/main/resources/elements").resolve(elementType);
    Files.createDirectories(directory);
    Files.writeString(directory.resolve("description.yaml"), "name: " + elementType);
  }

  private static CompilerPipelineIndex pipelineIndex(CompilerPipelineNode... nodes) {
    return new CompilerPipelineIndex(
        2, null, null, List.of(), null, Map.of(), List.of(nodes), List.of());
  }

  private static CompilerPipelineNode node(
      String skillId, Set<String> nodeTypes, Map<String, Set<String>> properties) {
    return new CompilerPipelineNode(
        skillId,
        "Generation",
        skillId,
        List.of(),
        List.of(),
        List.of(),
        "captureGraphPatch",
        List.of(),
        List.of(),
        true,
        List.of(),
        "",
        "",
        0,
        0,
        true,
        CompilerNodeExecutionMode.LLM_SKILL,
        null,
        new GraphPatchOwnershipPolicy(true, true, nodeTypes, Set.of(), properties));
  }

  private static CompilerContract contractCovering(String... elementTypes) {
    CompilerContract.ElementContract element =
        new CompilerContract.ElementContract(Map.of(), List.of(), "test", null);
    Map<String, CompilerContract.ElementContract> elements = new java.util.LinkedHashMap<>();
    for (String elementType : elementTypes) {
      elements.put(elementType, element);
    }
    return new CompilerContract(
        CompilerContract.V1,
        "test",
        elements,
        Map.of(),
        Set.of(),
        Set.of(),
        Set.of(),
        "test");
  }
}
