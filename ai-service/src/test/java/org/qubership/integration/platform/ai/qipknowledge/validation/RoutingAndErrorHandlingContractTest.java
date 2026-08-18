package org.qubership.integration.platform.ai.qipknowledge.validation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.QipKnowledgePackFixturePaths;

class RoutingAndErrorHandlingContractTest {

  @Test
  void validationRulesAllowOneOrMoreDirectIfChildren() throws Exception {
    String rules =
        Files.readString(
            Path.of("src/test/resources/qip-knowledge-fixture/ai/validation-rules.yaml"));

    assertFalse(rules.contains("condition.if_children_count != 1"));
    assertFalse(rules.contains("exactly one if child"));
    assertTrue(rules.contains("VR-G-005"));
    assertTrue(rules.contains("condition.if_children_count < 1"));
    assertTrue(rules.contains("one or more if children"));
  }

  @Test
  void routingAddonAllowsManyDirectIfChildren() throws Exception {
    String addon =
        Files.readString(
            QipKnowledgePackFixturePaths.addonRoot()
                .resolve("skills/cip-routing-generator.addon.md"));

    assertFalse(addon.toLowerCase().contains("exactly one if"));
    assertTrue(addon.contains("one or more direct `if`"));
    assertTrue(addon.contains("sibling `if`"));
  }

  @Test
  void errorHandlingAddonBindsWrapToEditTargetsAndNeverReparentsATrigger() throws Exception {
    String addon =
        Files.readString(
            QipKnowledgePackFixturePaths.addonRoot()
                .resolve("skills/cip-error-handling-generator.addon.md"));

    assertTrue(addon.contains("edit target"));
    assertTrue(addon.contains("Never reparent a trigger"));
    assertFalse(addon.contains("reparent existing flow nodes under"));
  }
}
