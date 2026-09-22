package org.qubership.integration.platform.ai.compiler;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.productpipeline.create.design.planning.DesignPlanContractValidator;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;

/**
 * Grants each element contract's required properties to the skill that already owns that type.
 *
 * <p>The owner comes from the existing sender, trigger, file-transfer, script, and loop table.
 * A skill keeps every key it already owns.
 */
public final class ContractRequiredPropertyGrant {

  private ContractRequiredPropertyGrant() {}

  public static GraphPatchOwnershipPolicy grant(
      GraphPatchOwnershipPolicy policy, String skillId, CompilerContract contract) {
    if (policy == null) {
      policy = GraphPatchOwnershipPolicy.denyAll();
    }
    if (skillId == null || skillId.isBlank() || contract == null || contract.elements() == null) {
      return policy;
    }
    Map<String, Set<String>> extra = new LinkedHashMap<>();
    for (Map.Entry<String, CompilerContract.ElementContract> entry : contract.elements().entrySet()) {
      String type = entry.getKey();
      if (DesignPlanContractValidator.ownerSkillId(type).filter(skillId::equals).isEmpty()) {
        continue;
      }
      List<String> required = entry.getValue().requiredProperties();
      if (required == null || required.isEmpty()) {
        continue;
      }
      extra.put(type, new LinkedHashSet<>(required));
    }
    return policy.withAdditionalProperties(extra);
  }
}
