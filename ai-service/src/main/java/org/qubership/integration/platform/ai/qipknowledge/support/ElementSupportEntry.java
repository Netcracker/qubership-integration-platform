package org.qubership.integration.platform.ai.qipknowledge.support;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Support evidence for one chain element type. */
public record ElementSupportEntry(
    String elementType,
    ElementSupportStatus status,
    boolean schemaPresent,
    boolean runtimeDescriptorPresent,
    boolean compilerContractCovered,
    boolean deprecated,
    List<String> ownerSkillIds,
    Map<String, List<String>> invalidOwnershipPropertiesBySkill,
    List<String> unownedRequiredProperties,
    List<String> reasons) {

  public ElementSupportEntry {
    ownerSkillIds = ownerSkillIds == null ? List.of() : List.copyOf(ownerSkillIds);
    invalidOwnershipPropertiesBySkill =
        invalidOwnershipPropertiesBySkill == null
            ? Map.of()
            : java.util.Collections.unmodifiableMap(
                new LinkedHashMap<>(invalidOwnershipPropertiesBySkill));
    unownedRequiredProperties =
        unownedRequiredProperties == null ? List.of() : List.copyOf(unownedRequiredProperties);
    reasons = reasons == null ? List.of() : List.copyOf(reasons);
  }
}
