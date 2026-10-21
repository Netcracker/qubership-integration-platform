package org.qubership.integration.platform.ai.qipknowledge.patch;

import java.util.List;
import org.qubership.integration.platform.ai.plan.model.ChainPlanEdge;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;

/** Test fixtures for {@link GraphPatch} apply/runtime scenarios. */
public final class GraphPatchFixtures {

  private static final String CAPABILITY_ID = "cip-error-handling-generator";

  private static final String CATCH_SCRIPT =
      """
      def exception = exchange.getProperty("CamelExceptionCaught")
      exchange.in.headers.put("CamelHttpResponseCode", 500)
      exchange.in.body = '{"error": "' + exception?.message + '"}'
      """;

  private GraphPatchFixtures() {}

  /** Minimal EH wrap patch for http-trigger → script (matches former GEN-04 pass shape). */
  public static GraphPatch wrapHttpTriggerFlow(String triggerNodeId, String scriptNodeId) {
    String wrapperId = id(triggerNodeId, "try-catch-finally");
    String tryId = id(triggerNodeId, "try");
    String catchId = id(triggerNodeId, "catch");
    String catchScriptId = id(triggerNodeId, "catch-script");

    return new GraphPatch(
        "error-handling-" + triggerNodeId,
        CAPABILITY_ID,
        List.of(
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode(
                    wrapperId, "try-catch-finally-2", "Error Handling", null, null, List.of()),
                null),
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode(tryId, "try-2", "Try", wrapperId, 0, List.of()),
                null),
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode(
                    catchId,
                    "catch-2",
                    "Catch",
                    wrapperId,
                    0,
                    List.of(
                        new PlanProperty("exception", "java.lang.Exception"),
                        new PlanProperty("priority", "0"))),
                null),
            new NodePatch(
                GraphPatchOperation.ADD,
                new ChainPlanNode(
                    catchScriptId,
                    "script",
                    "Error Response",
                    catchId,
                    0,
                    List.of(new PlanProperty("script", CATCH_SCRIPT))),
                null),
            new NodePatch(
                GraphPatchOperation.UPDATE,
                new ChainPlanNode(scriptNodeId, "script", "Response Script", tryId, 2, List.of()),
                scriptNodeId)),
        List.of(
            new EdgePatch(
                GraphPatchOperation.ADD,
                new ChainPlanEdge(
                    id(triggerNodeId, "edge-trigger-to-try"), triggerNodeId, wrapperId, null),
                null),
            new EdgePatch(GraphPatchOperation.REMOVE, null, "edge-1"),
            new EdgePatch(
                GraphPatchOperation.ADD,
                new ChainPlanEdge(
                    id(triggerNodeId, "edge-catch-to-script"), catchId, catchScriptId, catchId),
                null)),
        List.of(),
        List.of(),
        List.of(),
        "Add GEN-04 error-handling structure");
  }

  private static String id(String triggerNodeId, String suffix) {
    return "eh-" + triggerNodeId + "-" + suffix;
  }
}
