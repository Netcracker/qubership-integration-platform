package org.qubership.integration.platform.ai.plan.mapping;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntent;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingIntentRule;
import org.qubership.integration.platform.ai.qipknowledge.artifact.MappingPort;

class MappingMechanismSelectorTest {

  @Test
  void copyAndConstantRulesUseScriptWhileMapper2IsDisabled() {
    MappingIntent intent =
        new MappingIntent(
            "name-to-subject",
            "onTaskStart",
            MappingPort.OUTPUT,
            "createTask",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule("name", "Subject", null),
                new MappingIntentRule("priority", "Priority", null)));

    assertEquals(MappingMechanism.SCRIPT, MappingMechanismSelector.select(intent).orElse(null));
  }

  @Test
  void mapper2PreferenceFallsBackToScriptWhileMapper2IsDisabled() {
    MappingIntent intent =
        new MappingIntent(
            "name-to-subject",
            "onTaskStart",
            MappingPort.OUTPUT,
            "createTask",
            MappingPort.REQUEST,
            List.of(new MappingIntentRule("name", "Subject", null)),
            "MAPPER_2");

    assertEquals(MappingMechanism.SCRIPT, MappingMechanismSelector.select(intent).orElse(null));
    assertTrue(MappingMechanismSelector.clarification(intent).isEmpty());
  }

  @Test
  void complexRulesWithoutPreferenceUseScriptWhileMapper2IsDisabled() {
    MappingIntent intent =
        new MappingIntent(
            "om-task-to-salesforce-task",
            "onTaskStart",
            MappingPort.OUTPUT,
            "createTask",
            MappingPort.REQUEST,
            List.of(
                new MappingIntentRule(
                    "priority", "Priority", "high or urgent or critical maps to High")));

    assertEquals(MappingMechanism.SCRIPT, MappingMechanismSelector.select(intent).orElse(null));
  }

  @Test
  void emptyRulesUseScriptWhileMapper2IsDisabled() {
    MappingIntent intent =
        new MappingIntent(
            "om-task-to-salesforce-task",
            "onTaskStart",
            MappingPort.OUTPUT,
            "createTask",
            MappingPort.REQUEST,
            List.of());

    assertEquals(MappingMechanism.SCRIPT, MappingMechanismSelector.select(intent).orElse(null));
  }

  @Test
  void mapper2ElementTypeBecomesScriptWhileMapper2IsDisabled() {
    assertEquals("script", MappingMechanismSelector.canonicalTransformElementType("mapper-2"));
    assertEquals("script", MappingMechanismSelector.canonicalTransformElementType(" mapper-2 "));
    assertEquals("condition", MappingMechanismSelector.canonicalTransformElementType("condition"));
  }

  @Test
  void scriptPreferenceSelectsScriptForEnglishAndNonEnglishExpressions() {
    MappingIntent english = scriptPreference("uppercase the name");
    MappingIntent variant = scriptPreference("mettre l'identifiant en majuscules");
    MappingIntent languageNeutral = scriptPreference("normalize the identifier");

    assertEquals(MappingMechanism.SCRIPT, MappingMechanismSelector.select(english).orElse(null));
    assertEquals(MappingMechanism.SCRIPT, MappingMechanismSelector.select(variant).orElse(null));
    assertEquals(
        MappingMechanism.SCRIPT, MappingMechanismSelector.select(languageNeutral).orElse(null));
    assertEquals(
        MappingMechanismSelector.select(english), MappingMechanismSelector.select(variant));
    assertEquals(
        MappingMechanismSelector.select(english),
        MappingMechanismSelector.select(languageNeutral));
  }

  @Test
  void scriptCompatibilityDoesNotRequireEnglishExpressionWords() {
    MappingIntent joinRecords =
        scriptPreference("join records from two systems");

    assertEquals(
        MappingMechanism.SCRIPT, MappingMechanismSelector.select(joinRecords).orElse(null));
    assertTrue(MappingMechanismSelector.clarification(joinRecords).isEmpty());
    assertTrue(MappingMechanismSelector.scriptAcceptsExpression("normalize the identifier"));
    assertTrue(MappingMechanismSelector.scriptAcceptsExpression("mettre l'identifiant en majuscules"));
  }

  private static MappingIntent scriptPreference(String expression) {
    return new MappingIntent(
        "map-init",
        "onTaskStart",
        MappingPort.OUTPUT,
        "createTask",
        MappingPort.REQUEST,
        List.of(new MappingIntentRule("name", "Subject", expression)),
        "SCRIPT");
  }
}
