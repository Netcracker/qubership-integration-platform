package org.qubership.integration.platform.ai.compiler;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    if (capture.endpoints().size() != byNodeId.size()) {
      throw new IllegalArgumentException(
          "Capture must cover every approved HTTP trigger exactly once.");
    }
    Set<String> allowedRoles = Set.copyOf(binding.skeleton().entryPointRoleIds());
    Set<String> usedRoles = new HashSet<>();
    Set<String> usedNodes = new HashSet<>();
    List<ConfiguredTrigger> triggers = new ArrayList<>();
    List<String> factIds = new ArrayList<>();
    for (HttpTriggerCapture.Endpoint endpoint : capture.endpoints()) {
      if (endpoint == null || endpoint.roleId() == null || endpoint.externalRoute() == null
          || !allowedRoles.contains(endpoint.roleId())
          || !usedRoles.add(endpoint.roleId())
          || !byNodeId.containsKey(endpoint.semanticNodeId())
          || !usedNodes.add(endpoint.semanticNodeId())) {
        throw new IllegalArgumentException(
            "HTTP trigger role, node id, and externalRoute must match approved targets exactly.");
      }
      RequirementEntryPoint requirement = byNodeId.get(endpoint.semanticNodeId());
      triggers.add(new ConfiguredTrigger(
          endpoint.roleId(), endpoint.semanticNodeId(), "http-trigger",
          requirement.httpMethod() + " " + requirement.path(),
          List.of(
              new PlanProperty("contextPath", requirement.path()),
              new PlanProperty("httpMethodRestrict", requirement.httpMethod()),
              new PlanProperty("externalRoute", endpoint.externalRoute().toString()))));
      if (!requirement.sourceFactId().isBlank()) {
        factIds.add(requirement.sourceFactId());
      }
    }
    return new ConfiguredTriggerSet(1, triggers, factIds.stream().distinct().toList(), List.of());
  }
}
