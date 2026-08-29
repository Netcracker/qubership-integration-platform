package org.qubership.integration.platform.ai.productpipeline.create.design.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.binding.ResolvedServiceCallBinding;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogSystemReadTool;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticNode;
import org.qubership.integration.platform.ai.productpipeline.create.design.semantic.SemanticProvenance;

class CatalogBindingMatcherTest {

  @Test
  @DisplayName("two occurrences of the same operation keep distinct serviceCallId owners")
  void indexesDuplicateOperationsByServiceCallId() {
    List<SemanticNode.ServiceCall> calls =
        List.of(call("node-a", "call-a", "getOrder"), call("node-b", "call-b", "getOrder"));
    List<ResolvedServiceCallBinding> bindings =
        List.of(binding("call-a", "op-shared"), binding("call-b", "op-shared"));

    Map<String, ResolvedServiceCallBinding> matched = ownershipMatcher().match(calls, bindings);

    assertEquals(Set.of("call-a", "call-b"), matched.keySet());
    assertEquals("op-shared", matched.get("call-a").operationId());
    assertEquals("op-shared", matched.get("call-b").operationId());
    assertEquals("call-a", matched.get("call-a").serviceCallId());
    assertEquals("call-b", matched.get("call-b").serviceCallId());
  }

  @Test
  @DisplayName("missing, duplicate, or extra bindings fail fast by serviceCallId")
  void failsFastOnMissingDuplicateOrExtraBinding() {
    CatalogBindingMatcher matcher = ownershipMatcher();
    List<SemanticNode.ServiceCall> bothCalls =
        List.of(call("node-a", "call-a", "getOrder"), call("node-b", "call-b", "getOrder"));
    List<ResolvedServiceCallBinding> missingBindings = List.of(binding("call-a", "op-shared"));
    IllegalArgumentException missing =
        assertThrows(IllegalArgumentException.class, () -> matcher.match(bothCalls, missingBindings));
    assertEquals("missing catalog binding for serviceCallId=call-b", missing.getMessage());

    List<ResolvedServiceCallBinding> duplicateBindings =
        List.of(
            binding("call-a", "op-shared"),
            binding("call-b", "op-shared"),
            binding("call-a", "op-other"));
    IllegalArgumentException duplicate =
        assertThrows(
            IllegalArgumentException.class, () -> matcher.match(bothCalls, duplicateBindings));
    assertEquals("duplicate catalog binding for serviceCallId=call-a", duplicate.getMessage());

    List<SemanticNode.ServiceCall> oneCall = List.of(call("node-a", "call-a", "getOrder"));
    List<ResolvedServiceCallBinding> extraBindings =
        List.of(binding("call-a", "op-shared"), binding("call-b", "op-shared"));
    IllegalArgumentException extra =
        assertThrows(IllegalArgumentException.class, () -> matcher.match(oneCall, extraBindings));
    assertEquals("extra catalog binding for serviceCallId=call-b", extra.getMessage());
  }

  private static CatalogBindingMatcher ownershipMatcher() {
    return new CatalogBindingMatcher(mock(CatalogSystemReadTool.class));
  }

  private static SemanticNode.ServiceCall call(String nodeId, String serviceCallId, String operation) {
    return new SemanticNode.ServiceCall(
        nodeId, serviceCallId, operation, new SemanticProvenance(List.of()));
  }

  private static ResolvedServiceCallBinding binding(
      String serviceCallId, String integrationOperationId) {
    return new ResolvedServiceCallBinding(
        serviceCallId,
        serviceCallId,
        "INTEGRATION",
        "sys-1",
        "sg-1",
        "spec-1",
        integrationOperationId,
        "http",
        "GET",
        "/orders/{id}",
        "getOrder",
        ResolvedServiceCallBinding.Source.EXISTING_CATALOG,
        "2024.4",
        "evidence-" + serviceCallId,
        "");
  }
}
