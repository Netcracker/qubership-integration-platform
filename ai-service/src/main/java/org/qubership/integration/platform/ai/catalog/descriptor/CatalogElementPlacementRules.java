package org.qubership.integration.platform.ai.catalog.descriptor;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogElementDescriptorModel;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Placement checks derived from embedded {@code catalog-descriptors} YAML (same fields as
 * runtime-catalog {@code ElementDescriptor}), so agents can fail fast without relying on catalog
 * HTTP errors.
 */
@ApplicationScoped
public class CatalogElementPlacementRules {

  private static final String ERR_ELEMENT_TYPE_PREFIX = "element type `";

  private final CatalogDescriptorResourceLoader descriptors;
  private final CatalogRestClient catalogRestClient;

  @Inject
  public CatalogElementPlacementRules(
      CatalogDescriptorResourceLoader descriptors,
      @RestClient CatalogRestClient catalogRestClient) {
    this.descriptors = descriptors;
    this.catalogRestClient = catalogRestClient;
  }

  /**
   * Validates moving {@code childType} under a parent of type {@code parentType} (tree parent),
   * using descriptors when present.
   *
   * @return human-readable reason, or empty when no local rule rejects the move
   */
  public Optional<String> validateTransfer(String parentType, String childType) {
    if (isBlank(parentType) || isBlank(childType)) {
      return Optional.empty();
    }
    String pType = parentType.trim();
    String cType = childType.trim();
    Optional<CatalogElementDescriptorModel> childOpt = descriptors.load(cType);
    Optional<CatalogElementDescriptorModel> parentOpt = descriptors.load(pType);

    Optional<String> childRule = transferRuleForChild(childOpt, parentOpt, pType, cType);
    if (childRule.isPresent()) {
      return childRule;
    }
    return transferRuleForParent(parentOpt, pType, cType);
  }

  public Optional<String> validateConnection(String fromType, String toType) {
    Optional<String> fromRule = validateConnectionSource(fromType);
    if (fromRule.isPresent()) {
      return fromRule;
    }
    return validateConnectionTarget(toType);
  }

  private Optional<String> validateConnectionSource(String fromType) {
    if (isBlank(fromType)) {
      return Optional.empty();
    }
    String typeKey = fromType.trim();
    Optional<CatalogElementDescriptorModel> descriptor = descriptors.load(typeKey);
    if (descriptor.isEmpty() || !Boolean.FALSE.equals(descriptor.get().getOutputEnabled())) {
      return Optional.empty();
    }
    return Optional.of(
        "source type `"
            + typeKey
            + "` cannot have outgoing dependencies (descriptor outputEnabled=false)");
  }

  private Optional<String> validateConnectionTarget(String toType) {
    if (isBlank(toType)) {
      return Optional.empty();
    }
    String typeKey = toType.trim();
    Optional<CatalogElementDescriptorModel> descriptor = descriptors.load(typeKey);
    if (descriptor.isEmpty() || !Boolean.FALSE.equals(descriptor.get().getInputEnabled())) {
      return Optional.empty();
    }
    return Optional.of(
        "target type `"
            + typeKey
            + "` cannot have incoming dependencies (descriptor inputEnabled=false)");
  }

  private Optional<String> transferRuleForChild(
      Optional<CatalogElementDescriptorModel> childOpt,
      Optional<CatalogElementDescriptorModel> parentOpt,
      String parentType,
      String childType) {
    if (childOpt.isEmpty()) {
      return Optional.empty();
    }
    CatalogElementDescriptorModel child = childOpt.get();
    if (Boolean.FALSE.equals(child.getAllowedInContainers())) {
      return Optional.of(
          ERR_ELEMENT_TYPE_PREFIX
              + childType
              + "` is not allowed inside containers (descriptor allowedInContainers=false)");
    }
    if (isInboundTrigger(child)
        && parentOpt.filter(CatalogElementPlacementRules::isContainerDescriptor).isPresent()) {
      return Optional.of(
          "inbound trigger type `"
              + childType
              + "` cannot be nested under container `"
              + parentType
              + "`; keep triggers at chain root / swimlane and use createConnection");
    }
    return verifyParentRestriction(child, parentType);
  }

  private static Optional<String> transferRuleForParent(
      Optional<CatalogElementDescriptorModel> parentOpt, String parentType, String childType) {
    if (parentOpt.isEmpty()) {
      return Optional.empty();
    }
    Map<String, String> allowed = parentOpt.get().getAllowedChildren();
    if (allowed == null || allowed.isEmpty() || allowed.containsKey(childType)) {
      return Optional.empty();
    }
    return Optional.of(
        "parent type `"
            + parentType
            + "` only allows children "
            + allowed.keySet()
            + "; cannot move `"
            + childType
            + "` there");
  }

  /**
   * Validates create placement before calling catalog POST.
   *
   * @param parentElementId empty string or null when creating at chain root
   */
  public Optional<String> validateCreatePlacement(
      String chainId, String elementType, String parentElementId) {
    if (isBlank(elementType)) {
      return Optional.empty();
    }
    String typeKey = elementType.trim();
    Optional<CatalogElementDescriptorModel> childOpt = descriptors.load(typeKey);
    if (childOpt.isEmpty()) {
      return Optional.empty();
    }
    CatalogElementDescriptorModel child = childOpt.get();
    String parentId = blankToNull(parentElementId);

    Optional<String> noContainers =
        rejectCreateWhenDisallowedInContainers(child, typeKey, parentId);
    if (noContainers.isPresent()) {
      return noContainers;
    }

    List<String> restriction = child.getParentRestriction();
    if (restriction != null && !restriction.isEmpty() && parentId == null) {
      return Optional.of(
          ERR_ELEMENT_TYPE_PREFIX
              + typeKey
              + "` must be created under a parent of one of types "
              + restriction
              + ", not at chain root. Nest the element under the correct container in the plan, "
              + "or pass parentElementId of the specific container element.");
    }

    boolean needsParentFetch =
        parentId != null
            && ((restriction != null && !restriction.isEmpty()) || isInboundTrigger(child));
    CatalogElementResponseDto parentEl =
        needsParentFetch ? catalogRestClient.getElement(chainId, parentId) : null;

    Optional<String> restrictionErr =
        validateCreateParentRestriction(restriction, typeKey, parentId, parentEl);
    if (restrictionErr.isPresent()) {
      return restrictionErr;
    }

    return validateInboundTriggerNotUnderContainer(typeKey, parentId, parentEl, child);
  }

  private static Optional<String> rejectCreateWhenDisallowedInContainers(
      CatalogElementDescriptorModel child, String typeKey, String parentId) {
    if (!Boolean.FALSE.equals(child.getAllowedInContainers()) || parentId == null) {
      return Optional.empty();
    }
    return Optional.of(
        ERR_ELEMENT_TYPE_PREFIX
            + typeKey
            + "` cannot be created under a parent (descriptor allowedInContainers=false)");
  }

  private static Optional<String> validateCreateParentRestriction(
      List<String> restriction,
      String typeKey,
      String parentId,
      CatalogElementResponseDto parentEl) {
    if (restriction == null || restriction.isEmpty() || parentId == null) {
      return Optional.empty();
    }
    if (parentEl == null || isBlank(parentEl.type)) {
      return Optional.empty();
    }
    if (parentTypeMatchesRestriction(restriction, parentEl.type)) {
      return Optional.empty();
    }
    return Optional.of(
        ERR_ELEMENT_TYPE_PREFIX
            + typeKey
            + "` may only be created under "
            + restriction
            + "; parent has type `"
            + parentEl.type
            + "`");
  }

  private Optional<String> validateInboundTriggerNotUnderContainer(
      String typeKey,
      String parentId,
      CatalogElementResponseDto parentEl,
      CatalogElementDescriptorModel child) {
    if (parentId == null || !isInboundTrigger(child)) {
      return Optional.empty();
    }
    if (parentEl == null || isBlank(parentEl.type)) {
      return Optional.empty();
    }
    Optional<CatalogElementDescriptorModel> parentDesc = descriptors.load(parentEl.type.trim());
    if (parentDesc.filter(CatalogElementPlacementRules::isContainerDescriptor).isEmpty()) {
      return Optional.empty();
    }
    return Optional.of(
        "inbound trigger type `"
            + typeKey
            + "` cannot be created under container `"
            + parentEl.type
            + "`; place it at chain root or swimlane");
  }

  private static Optional<String> verifyParentRestriction(
      CatalogElementDescriptorModel child, String parentType) {
    List<String> restriction = child.getParentRestriction();
    if (restriction == null || restriction.isEmpty()) {
      return Optional.empty();
    }
    if (parentTypeMatchesRestriction(restriction, parentType)) {
      return Optional.empty();
    }
    String label = child.getName() != null ? child.getName() : "(unknown)";
    return Optional.of(
        ERR_ELEMENT_TYPE_PREFIX
            + label
            + "` may only be nested under "
            + restriction
            + "; target parent is `"
            + parentType
            + "`");
  }

  private static boolean parentTypeMatchesRestriction(List<String> restriction, String parentType) {
    String p = parentType.trim();
    for (String allowed : restriction) {
      if (allowed != null && allowed.trim().equalsIgnoreCase(p)) {
        return true;
      }
    }
    return false;
  }

  private static boolean isInboundTrigger(CatalogElementDescriptorModel child) {
    return child.getType() != null && "trigger".equalsIgnoreCase(child.getType().trim());
  }

  private static boolean isContainerDescriptor(CatalogElementDescriptorModel d) {
    return Boolean.TRUE.equals(d.getContainer());
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static String blankToNull(String s) {
    return isBlank(s) ? null : s.trim();
  }
}
