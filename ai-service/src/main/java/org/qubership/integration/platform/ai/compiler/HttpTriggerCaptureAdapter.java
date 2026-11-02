package org.qubership.integration.platform.ai.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.qubership.integration.platform.ai.compiler.capture.policy.CaptureToolOutcomeGateway.TypedIssue;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticEntryPoint;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTrigger;
import org.qubership.integration.platform.ai.qipknowledge.artifact.ConfiguredTriggerSet;
import org.qubership.integration.platform.ai.qipknowledge.artifact.RequirementEntryPoint;

/** Projects approved HTTP entry points and typed exposure choices into trigger properties. */
final class HttpTriggerCaptureAdapter {

  ConfiguredTriggerSet adapt(
      HttpTriggerCapture capture, HttpTriggerCaptureSession.Binding binding) {
    if (capture == null || binding == null) {
      throw new IllegalArgumentException("HTTP trigger capture and approved inputs are required.");
    }
    Map<String, RequirementEntryPoint> approved = new HashMap<>();
    for (RequirementEntryPoint entry : binding.brief().entryPoints()) {
      approved.put(entry.entryPointId(), entry);
    }
    Map<String, RequirementEntryPoint> byNodeId = new HashMap<>();
    for (SemanticEntryPoint entry : binding.revision().entryPoints()) {
      SemanticNode node = binding.revision().nodes().stream()
          .filter(candidate -> candidate.nodeId().equals(entry.triggerNodeId()))
          .findFirst().orElse(null);
      RequirementEntryPoint requirement = approved.get(entry.entryPointId());
      if (!(node instanceof SemanticNode.Trigger trigger)
          || !"http-trigger".equals(trigger.capabilityKey()) || requirement == null
          || requirement.path().isBlank() || requirement.httpMethod().isBlank()) {
        throw new IllegalArgumentException(
            "Approved HTTP path and method are required for " + entry.entryPointId() + ".");
      }
      byNodeId.put(entry.triggerNodeId(), requirement);
    }
    List<String> roles = binding.skeleton().entryPointRoleIds();
    List<String> nodes = byNodeId.keySet().stream().sorted().toList();
    List<TypedIssue> issues = new ArrayList<>();
    if (capture.endpoints().size() != byNodeId.size()) {
      issues.add(new TypedIssue("ENDPOINT_COUNT", "/capture/endpoints",
          "Received " + capture.endpoints().size() + " endpoints for " + byNodeId.size()
              + " approved HTTP triggers. Send one endpoint for each of: " + String.join(", ", nodes) + "."));
    }
    Set<String> usedRoles = new HashSet<>();
    Set<String> usedNodes = new HashSet<>();
    List<ConfiguredTrigger> triggers = new ArrayList<>();
    List<String> factIds = new ArrayList<>();
    for (int index = 0; index < capture.endpoints().size(); index++) {
      HttpTriggerCapture.Endpoint endpoint = capture.endpoints().get(index);
      String at = "/capture/endpoints/" + index;
      if (endpoint == null) {
        issues.add(new TypedIssue("MISSING_FIELD", at, "Send an endpoint object."));
        continue;
      }
      String role = endpoint.roleId();
      // The skeleton's list can be immutable, and List.of().contains(null) throws.
      String entity = role != null && roles.contains(role) ? role : null;
      int before = issues.size();
      if (role == null) {
        issues.add(new TypedIssue("MISSING_FIELD", at + "/roleId", "Include this field.", entity));
      } else if (entity == null) {
        issues.add(new TypedIssue("UNKNOWN_ROLE", at + "/roleId",
            "'" + role + "' is not an approved roleId. Use one of: " + String.join(", ", roles) + ".", entity));
      } else if (!usedRoles.add(role)) {
        issues.add(new TypedIssue("REUSED_ROLE", at + "/roleId",
            "'" + role + "' is already used by another endpoint. Use each roleId once.", entity));
      }
      String node = endpoint.semanticNodeId();
      if (node == null) {
        issues.add(new TypedIssue("MISSING_FIELD", at + "/semanticNodeId", "Include this field.", entity));
      } else if (!byNodeId.containsKey(node)) {
        issues.add(new TypedIssue("UNKNOWN_NODE", at + "/semanticNodeId",
            "'" + node + "' is not an approved semanticNodeId. Use one of: " + String.join(", ", nodes) + ".",
            entity));
      } else if (!usedNodes.add(node)) {
        issues.add(new TypedIssue("REUSED_NODE", at + "/semanticNodeId",
            "'" + node + "' is already used by another endpoint. Use each semanticNodeId once.", entity));
      }
      if (endpoint.externalRoute() == null) {
        issues.add(new TypedIssue("MISSING_FIELD", at + "/externalRoute",
            "Set externalRoute to true or false.", entity));
      }
      if (issues.size() > before) {
        continue;
      }
      RequirementEntryPoint requirement = byNodeId.get(node);
      triggers.add(new ConfiguredTrigger(
          role, node, "http-trigger",
          requirement.httpMethod() + " " + requirement.path(),
          List.of(
              new PlanProperty("contextPath", requirement.path()),
              new PlanProperty("httpMethodRestrict", requirement.httpMethod()),
              new PlanProperty("externalRoute", endpoint.externalRoute().toString()))));
      if (!requirement.sourceFactId().isBlank()) {
        factIds.add(requirement.sourceFactId());
      }
    }
    if (!issues.isEmpty()) {
      throw new Rejection(issues);
    }
    return new ConfiguredTriggerSet(1, triggers, factIds.stream().distinct().toList(), List.of());
  }

  /** Every endpoint defect of one capture, so the model can correct them in one call. */
  static final class Rejection extends IllegalArgumentException {
    private final transient List<TypedIssue> issues;

    Rejection(List<TypedIssue> issues) {
      super(issues.stream().map(TypedIssue::message).collect(Collectors.joining("\n")));
      this.issues = List.copyOf(issues);
    }

    List<TypedIssue> issues() {
      return issues;
    }
  }
}
