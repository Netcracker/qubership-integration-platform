package org.qubership.integration.platform.ai.compiler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.compiler.contract.ClasspathCompilerContractRepository;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;
import org.qubership.integration.platform.ai.plan.model.ChainPlanNode;
import org.qubership.integration.platform.ai.plan.model.ChainSection;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

class ContractRequiredPropertyGrantTest {

  private final CompilerContract contract =
      new ClasspathCompilerContractRepository().require(CompilerContract.V1);

  @Test
  void grantsContractRequiredPropertiesToTheExistingOwnerAndKeepsPriorKeys() {
    GraphPatchOwnershipPolicy serviceCall =
        ContractRequiredPropertyGrant.grant(
            new GraphPatchOwnershipPolicy(
                false,
                false,
                Set.of("mail-sender"),
                Set.of(),
                Map.of("mail-sender", Set.of("timeout"))),
            "cip-service-call-generator",
            contract);
    GraphPatchOwnershipPolicy fileTransfer =
        ContractRequiredPropertyGrant.grant(
            GraphPatchOwnershipPolicy.denyAll(), "cip-file-operations-generator", contract);

    assertTrue(serviceCall.properties().get("mail-sender").contains("timeout"));
    assertTrue(serviceCall.properties().get("mail-sender").containsAll(Set.of("from", "url")));
    assertTrue(serviceCall.properties().get("http-sender").contains("httpMethod"));
    assertFalse(serviceCall.properties().containsKey("sftp-upload"));
    assertTrue(fileTransfer.properties().get("sftp-upload").contains("connectUrl"));
    assertTrue(fileTransfer.properties().get("sftp-download").contains("connectUrl"));
  }

  @Test
  void emptyPatchGapUsesTheGrantedContractKeysForThatOwnerOnly() {
    GraphPatchOwnershipPolicy serviceCall =
        ContractRequiredPropertyGrant.grant(
            GraphPatchOwnershipPolicy.denyAll(), "cip-service-call-generator", contract);
    ChainPlanGraph graph =
        new ChainPlanGraph(
            "1.0",
            new ChainSection("orders", "Orders"),
            List.of(
                new ChainPlanNode("mail", "mail-sender", "Mail", null, null, List.of()),
                new ChainPlanNode("sftp", "sftp-upload", "SFTP", null, null, List.of())),
            List.of());

    OwnedSchemaRequiredPropertyGate.NodeRequiredKeys required =
        node -> {
          CompilerContract.ElementContract element = contract.elements().get(node.type());
          if (element == null || element.requiredProperties() == null) {
            return Set.of();
          }
          return new LinkedHashSet<>(element.requiredProperties());
        };
    List<OwnedSchemaRequiredPropertyGate.Gap> gaps =
        OwnedSchemaRequiredPropertyGate.findGaps(graph, serviceCall, required);

    assertEquals(1, gaps.size());
    assertEquals("mail", gaps.getFirst().nodeId());
    assertTrue(gaps.getFirst().missingPropertyKeys().containsAll(List.of("from", "url")));
  }
}
