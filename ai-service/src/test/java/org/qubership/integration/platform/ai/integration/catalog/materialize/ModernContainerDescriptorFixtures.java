package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorDto;

/** Realistic catalog descriptors for modern container contract tests. */
final class ModernContainerDescriptorFixtures {

  private ModernContainerDescriptorFixtures() {}

  static final Map<String, CatalogElementDescriptorDto> LIBRARY = buildLibrary();

  private static Map<String, CatalogElementDescriptorDto> buildLibrary() {
    Map<String, CatalogElementDescriptorDto> library = new LinkedHashMap<>();
    library.put("http-trigger", trigger("http-trigger"));
    library.put("script", leaf("script"));
    library.put("service-call", leaf("service-call"));
    library.put(
        "condition",
        container(
            "condition",
            Map.of(
                "if", CatalogChildQuantity.ONE_OR_MANY,
                "else", CatalogChildQuantity.ONE_OR_ZERO),
            List.of("try-2", "catch-2")));
    library.put("if", container("if", Map.of(), List.of("condition"), false));
    library.put("else", leaf("else"));
    library.put(
        "try-catch-finally-2",
        container(
            "try-catch-finally-2",
            Map.of(
                "try-2", CatalogChildQuantity.ONE,
                "catch-2", CatalogChildQuantity.ONE_OR_MANY,
                "finally-2", CatalogChildQuantity.ONE_OR_ZERO)));
    library.put("try-2", shellContainer("try-2"));
    library.put("catch-2", shellContainer("catch-2"));
    library.put("finally-2", shellContainer("finally-2"));
    library.put(
        "split-async-2",
        container("split-async-2", Map.of("async-split-element-2", CatalogChildQuantity.TWO_OR_MANY)));
    library.put("async-split-element-2", leaf("async-split-element-2"));
    library.put(
        "split-2",
        container(
            "split-2",
            Map.of(
                "main-split-element-2", CatalogChildQuantity.ONE_OR_ZERO,
                "split-element-2", CatalogChildQuantity.ONE_OR_MANY)));
    library.put("main-split-element-2", leaf("main-split-element-2"));
    library.put("split-element-2", leaf("split-element-2"));
    library.put(
        "circuit-breaker-2",
        container(
            "circuit-breaker-2",
            Map.of(
                "circuit-breaker-configuration-2", CatalogChildQuantity.ONE,
                "on-fallback-2", CatalogChildQuantity.ONE)));
    library.put("circuit-breaker-configuration-2", leaf("circuit-breaker-configuration-2"));
    library.put("on-fallback-2", leaf("on-fallback-2"));
    library.put("loop-2", container("loop-2", Map.of()));
    return Map.copyOf(library);
  }

  private static CatalogElementDescriptorDto trigger(String type) {
    CatalogElementDescriptorDto dto = base(type, false);
    dto.allowedInContainers = true;
    return dto;
  }

  private static CatalogElementDescriptorDto leaf(String type) {
    return base(type, false);
  }

  private static CatalogElementDescriptorDto container(
      String type, Map<String, CatalogChildQuantity> allowedChildren) {
    return container(type, allowedChildren, List.of(), true);
  }

  private static CatalogElementDescriptorDto container(
      String type,
      Map<String, CatalogChildQuantity> allowedChildren,
      List<String> parentRestriction) {
    return container(type, allowedChildren, parentRestriction, true);
  }

  private static CatalogElementDescriptorDto container(
      String type,
      Map<String, CatalogChildQuantity> allowedChildren,
      List<String> parentRestriction,
      boolean mandatoryInnerElement) {
    CatalogElementDescriptorDto dto = base(type, true);
    dto.allowedChildren = allowedChildren;
    dto.parentRestriction = parentRestriction;
    dto.mandatoryInnerElement = mandatoryInnerElement;
    return dto;
  }

  private static CatalogElementDescriptorDto shellContainer(String type) {
    CatalogElementDescriptorDto dto = base(type, true);
    dto.mandatoryInnerElement = false;
    return dto;
  }

  private static CatalogElementDescriptorDto base(String type, boolean container) {
    CatalogElementDescriptorDto dto = new CatalogElementDescriptorDto();
    dto.name = type;
    dto.container = container;
    dto.allowedChildren = Map.of();
    dto.parentRestriction = List.of();
    dto.ordered = true;
    dto.priorityProperty = "priority";
    dto.mandatoryInnerElement = false;
    dto.deprecated = false;
    dto.oldStyleContainer = false;
    dto.allowedInContainers = true;
    return dto;
  }
}
