package org.qubership.integration.platform.ai.qipknowledge.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.ChainSemanticRevision;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementBrief;

/** Checks generated HTTP values against the source bound to the approved plan. */
public final class ApprovedHttpEndpointValidator {
  public static final String FINDING_CODE = "HTTP_ENDPOINT_MISMATCH";

  private ApprovedHttpEndpointValidator() {}

  public static List<ValidationIssue> validate(
      ChainPlanGraph graph, RequirementBrief brief, ChainSemanticRevision semantic) {
    List<ValidationIssue> issues = new ArrayList<>();
    for (var entry : brief.entryPoints()) {
      if (!"http-trigger".equals(entry.capabilityKey())) {
        continue;
      }
      var bindings = semantic.entryPoints().stream()
          .filter(binding -> entry.entryPointId().equals(binding.entryPointId())).toList();
      if (bindings.size() != 1) {
        throw new IllegalStateException("Approved HTTP entry point has no unique semantic binding: " + entry.entryPointId());
      }
      String nodeId = bindings.getFirst().triggerNodeId();
      var node = graph.nodes().stream().filter(value -> nodeId.equals(value.nodeId())).findFirst().orElse(null);
      String pathKey = node != null && value(node, "integrationOperationId") != null
          && !value(node, "integrationOperationId").isBlank() ? "integrationOperationPath" : "contextPath";
      String owner = "integrationOperationPath".equals(pathKey)
          ? "cip-trigger-generator" : "cip-http-trigger-endpoint-generator";
      for (String key : List.of(pathKey, "httpMethodRestrict")) {
        String expected = "httpMethodRestrict".equals(key) ? entry.httpMethod() : entry.path();
        String actual = node == null ? null : value(node, key);
        if (expected.isBlank() || node == null || !"http-trigger".equals(node.type()) || !Objects.equals(expected, actual)) {
          issues.add(new ValidationIssue("http-endpoint-" + nodeId + "-" + key, ValidationSeverity.BLOCKER,
              "HTTP entry '" + entry.entryPointId() + "' on node '" + nodeId + "' changed " + key
                  + ": approved=" + expected + ", generated=" + actual + ", sourceFactId=" + entry.sourceFactId() + ".",
              owner, List.of(nodeId), List.of(),
              "Restore " + key + " to the exact approved value: " + expected + ". Preserve all other requirements."
                  + " This is a generated value error; do not ask the author to repeat the known URL.",
              FINDING_CODE, nodeId, "/properties/" + key));
        }
      }
    }
    return List.copyOf(issues);
  }

  private static String value(ChainPlanNode node, String key) {
    return node.properties().stream().filter(property -> key.equals(property.key()))
        .map(property -> property.value() == null ? "" : property.value()).findFirst().orElse(null);
  }
}
