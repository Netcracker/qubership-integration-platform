package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOperation;
import org.qubership.integration.platform.ai.qipknowledge.patch.NodePatch;
import org.qubership.integration.platform.ai.qipknowledge.patch.PropertyPatch;

class ScriptBodyPromptRedactionTest {

  @Test
  void rejectsScriptPropertyPatchFromErrorHandlingCapability() {
    GraphPatch patch =
        new GraphPatch(
            "eh-script",
            "cip-error-handling-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "catch-script",
                    new PlanProperty("script", "return 'error';"))),
            List.of(),
            List.of(),
            "corporate error response body");

    Optional<String> error =
        ScriptBodyPromptRedaction.validatePatch("cip-error-handling-generator", patch);

    assertTrue(error.isPresent());
    assertTrue(error.get().contains("only allowed for cip-script-generator"));
    assertTrue(error.get().contains("Omit key 'script'"));
  }

  @Test
  void rejectsScriptEmbeddedInNodePatchFromNonScriptCapability() {
    GraphPatch patch =
        new GraphPatch(
            "eh-node-script",
            "cip-error-handling-generator",
            List.of(
                new NodePatch(
                    GraphPatchOperation.ADD,
                    new ChainPlanNode(
                        "catch-script",
                        "script",
                        "Error response",
                        "catch-2",
                        null,
                        List.of(new PlanProperty("script", "return 'error';"))),
                    null)),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "add catch script with body");

    Optional<String> error =
        ScriptBodyPromptRedaction.validatePatch("cip-error-handling-generator", patch);

    assertTrue(error.isPresent());
    assertTrue(error.get().contains("only allowed for cip-script-generator"));
  }

  @Test
  void allowsScriptPropertyPatchFromScriptGenerator() {
    GraphPatch patch =
        new GraphPatch(
            "script-body",
            "cip-script-generator",
            List.of(),
            List.of(),
            List.of(
                new PropertyPatch(
                    GraphPatchOperation.ADD,
                    "script-1",
                    new PlanProperty("script", "return 'ok';"))),
            List.of(),
            List.of(),
            "fill body");

    assertEquals(
        Optional.empty(),
        ScriptBodyPromptRedaction.validatePatch("cip-script-generator", patch));
  }
}
