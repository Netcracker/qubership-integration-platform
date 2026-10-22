package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.qubership.integration.platform.ai.compiler.addon.AddonRuntimeMetadataParser;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.qubership.integration.platform.ai.qipknowledge.CipKnowledgePackFixturePaths;

@Tag("certified-pack")
class RoutingAndErrorHandlingContractTest {

  @Test
  void validationRulesAllowOneOrMoreDirectIfChildren() throws Exception {
    String rules =
        Files.readString(
            CipKnowledgePackFixturePaths.knowledgeRoot().resolve("ai/validation-rules.yaml"));

    assertFalse(rules.contains("condition.if_children_count != 1"));
    assertFalse(rules.contains("exactly one if child"));
    assertTrue(rules.contains("VR-G-005"));
    assertTrue(rules.contains("condition.if_children_count < 1"));
    assertTrue(rules.contains("one or more if children"));
  }

  @Test
  void standaloneRoutingAllowsManyDirectIfChildren() throws Exception {
    String addon =
        Files.readString(
            CipKnowledgePackFixturePaths.packRoot()
                .resolve("skills/cip-routing-generator/SKILL.md"));

    assertFalse(addon.toLowerCase().contains("exactly one if"));
    assertTrue(addon.contains("one or more direct if"));
    assertTrue(addon.contains("sibling\nif"));
  }

  @Test
  void errorHandlingDefinitionOwnsPropertiesButNotTopology() throws Exception {
    String addon =
        Files.readString(
            CipKnowledgePackFixturePaths.packRoot()
                .resolve("skills/cip-error-handling-generator/SKILL.md"));

    assertTrue(addon.contains("exception and integer priority"));
    assertTrue(addon.replaceAll("\\s+", " ").contains("Do not add containers, branches, or scripts"));
    var metadata = new AddonRuntimeMetadataParser().parseAddonFile(
        CipKnowledgePackFixturePaths.addonRoot().resolve("skills/cip-error-handling-generator.json"));
    var ownership = metadata.ownership();
    assertFalse(ownership.mayAddNodes());
    assertFalse(ownership.mayAddEdges());
    assertTrue(ownership.nodeTypes().isEmpty());
    assertEquals(Set.of("exception", "priority"), ownership.properties().get("catch-2"));
  }

  @Test
  void errorHandlingFixturesHaveNoTopologyPatchExample() {
    Path example =
        CipKnowledgePackFixturePaths.packRoot().resolveSibling("rocky-skills-maintenance")
            .resolve("tests/fixtures/cip-error-handling-generator/add-try-catch-wrapper-atomic.json");

    assertFalse(Files.exists(example));
  }
}
