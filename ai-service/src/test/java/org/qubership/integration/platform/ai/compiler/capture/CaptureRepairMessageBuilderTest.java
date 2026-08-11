package org.qubership.integration.platform.ai.compiler.capture;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureFailureClass;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

@ExtendWith(MockitoExtension.class)
class CaptureRepairMessageBuilderTest {

  @Mock private DeterministicElementSchemaService schemaService;

  private CaptureRepairMessageBuilder builder;

  @BeforeEach
  void setUp() {
    builder = new CaptureRepairMessageBuilder(schemaService);
  }

  @Test
  void validationMessageWithFieldHintsIsActionableAndSkipsGraphSchemaCue() {
    String message =
        builder.build(
            new CaptureAttemptFeedback(
                CaptureFailureKind.VALIDATION,
                "patternId is required",
                CaptureFailureClass.CORRECTABLE,
                true,
                List.of(
                    new CaptureFieldHint(
                        "patternId", "elementSkeleton.selectedPatternId", "GP-01"))),
            "captureSelectedPattern");

    assertTrue(
        message.contains(
            "Set top-level 'patternId' to the value already present at"
                + " 'elementSkeleton.selectedPatternId' (GP-01)."));
    assertFalse(message.contains("describeElementPatchSchema"));
    assertFalse(message.contains("Call describeElementPatchSchema for allowed property keys"));
  }

  @Test
  void validationMessageIncludesNodeKeyAndSchemaHint() {
    when(schemaService.allowedPatchPropertyKeys("catch-2"))
        .thenReturn(Set.of("priority", "handled", "redelivery"));

    String message =
        builder.build(
            new CaptureAttemptFeedback(
                CaptureFailureKind.VALIDATION,
                "Plan validation failed:\n"
                    + "node 'catch-1' (catch-2) has unknown property key 'exceptionType'."),
            "captureChainPlan");

    assertTrue(message.contains("catch-1"));
    assertTrue(message.contains("catch-2"));
    assertTrue(message.contains("exceptionType"));
    assertTrue(message.contains("describeElementPatchSchema"));
    assertTrue(message.contains("priority"));
  }

  @Test
  void unknownPropertyMessageRequiresRemovalAndListsSchemaAlternatives() {
    when(schemaService.allowedPatchPropertyKeys("script"))
        .thenReturn(Set.of("script", "exportFileExtension"));

    String message =
        builder.build(
            new CaptureAttemptFeedback(
                CaptureFailureKind.VALIDATION,
                "Structure validation failed:\n"
                    + "node 'script-1' (script) has unknown property key 'language'."),
            "captureChainStructure");

    assertTrue(
        message.contains(
            "Remove property key 'language' from node 'script-1'."));
    assertTrue(
        message.contains(
            "If it is a misspelling, replace it only with a schema-defined key"));
    assertTrue(message.contains("exportFileExtension"));
    assertTrue(message.contains("script"));
  }

  @Test
  void validationMessageTruncatesManyErrors() {
    String summary =
        "Plan validation failed:\n"
            + "error one\nerror two\nerror three\nerror four\nerror five";
    String message =
        builder.build(new CaptureAttemptFeedback(CaptureFailureKind.VALIDATION, summary), "captureChainPlan");

    assertTrue(message.contains("error one"));
    assertTrue(message.contains("error two"));
    assertTrue(message.contains("error three"));
    assertTrue(!message.contains("error four"));
  }

  @Test
  void toolArgumentsMessageMentionsCaptureTool() {
    String message = builder.toolArgumentsMessage("captureGraphPatch");

    assertTrue(message.contains("captureGraphPatch"));
    assertTrue(message.contains("invalid tool JSON"));
  }

  @Test
  void toolArgumentsMessageForRepairScriptBodiesMentionsJsonOutput() {
    String message = builder.toolArgumentsMessage("repairScriptBodies");

    assertTrue(message.contains("repairScriptBodies"));
    assertTrue(message.contains("JsonOutput.toJson"));
  }
}
