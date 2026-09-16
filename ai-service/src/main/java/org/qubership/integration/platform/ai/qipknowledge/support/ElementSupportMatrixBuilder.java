package org.qubership.integration.platform.ai.qipknowledge.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.qubership.integration.platform.ai.compiler.contract.CompilerContract;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineIndex;
import org.qubership.integration.platform.ai.compiler.pipeline.CompilerPipelineNode;
import org.qubership.integration.platform.ai.plan.mapping.MappingExecutionSite;
import org.qubership.integration.platform.ai.plan.model.PlanProperty;
import org.qubership.integration.platform.ai.qipknowledge.patch.GraphPatchOwnershipPolicy;
import org.qubership.integration.platform.ai.schema.ChainElementCatalog;
import org.qubership.integration.platform.ai.schema.DeterministicElementSchemaService;

/** Builds support evidence from the schema, runtime catalog, compiler contract, and ownership. */
public final class ElementSupportMatrixBuilder {

  public static final int SCHEMA_VERSION = 1;
  private static final String RUNTIME_ELEMENTS =
      "runtime-catalog/src/main/resources/elements";

  public ElementSupportMatrix build(
      Path repoRoot, CompilerPipelineIndex pipelineIndex, CompilerContract contract) {
    ObjectMapper objectMapper = new ObjectMapper();
    ChainElementCatalog catalog = new ChainElementCatalog(objectMapper);
    DeterministicElementSchemaService schemaService =
        DeterministicElementSchemaService.createForUnitTests(objectMapper);
    return build(
        catalog,
        schemaService,
        runtimeDescriptorTypes(repoRoot),
        pipelineIndex,
        contract);
  }

  ElementSupportMatrix build(
      ChainElementCatalog catalog,
      DeterministicElementSchemaService schemaService,
      Set<String> runtimeDescriptorTypes,
      CompilerPipelineIndex pipelineIndex,
      CompilerContract contract) {
    Map<String, Map<String, Set<String>>> propertiesByTypeAndSkill = new TreeMap<>();
    Map<String, Set<String>> ownersByType = new TreeMap<>();
    collectOwnership(pipelineIndex, ownersByType, propertiesByTypeAndSkill);

    Set<String> elementTypes = new TreeSet<>(catalog.allTypes());
    elementTypes.addAll(ownersByType.keySet());
    elementTypes.addAll(propertiesByTypeAndSkill.keySet());
    if (contract != null) {
      elementTypes.addAll(contract.elements().keySet());
    }

    List<ElementSupportEntry> entries = new ArrayList<>();
    for (String elementType : elementTypes) {
      entries.add(
          buildEntry(
              elementType,
              catalog,
              schemaService,
              runtimeDescriptorTypes,
              ownersByType.getOrDefault(elementType, Set.of()),
              propertiesByTypeAndSkill.getOrDefault(elementType, Map.of()),
              contract));
    }

    Map<ElementSupportStatus, Integer> counts = new EnumMap<>(ElementSupportStatus.class);
    for (ElementSupportStatus status : ElementSupportStatus.values()) {
      counts.put(status, 0);
    }
    for (ElementSupportEntry entry : entries) {
      counts.compute(entry.status(), (ignored, count) -> count + 1);
    }
    return new ElementSupportMatrix(
        SCHEMA_VERSION,
        contract == null ? null : contract.contractVersion(),
        counts,
        entries);
  }

  private static ElementSupportEntry buildEntry(
      String elementType,
      ChainElementCatalog catalog,
      DeterministicElementSchemaService schemaService,
      Set<String> runtimeDescriptorTypes,
      Set<String> owners,
      Map<String, Set<String>> propertiesBySkill,
      CompilerContract contract) {
    boolean schemaPresent =
        catalog.isKnown(elementType) && schemaService.hasElementSchema(elementType);
    boolean runtimeDescriptorPresent = runtimeDescriptorTypes.contains(elementType);
    boolean compilerContractCovered =
        contract != null && contract.elements().containsKey(elementType);
    boolean deprecated = catalog.isDeprecated(elementType);

    Set<String> allowedProperties =
        schemaPresent ? schemaService.allowedPatchPropertyKeys(elementType) : Set.of();
    Map<String, List<String>> invalidBySkill = new LinkedHashMap<>();
    Set<String> ownedProperties = new LinkedHashSet<>();
    propertiesBySkill.entrySet().stream()
        .sorted(Map.Entry.comparingByKey())
        .forEach(
            entry -> {
              Set<String> invalid = new TreeSet<>();
              for (String property : entry.getValue()) {
                if (allowedProperties.contains(property)
                    || MappingExecutionSite.isCompilerMetadataKey(property)) {
                  ownedProperties.add(property);
                } else {
                  invalid.add(property);
                }
              }
              if (!invalid.isEmpty()) {
                invalidBySkill.put(entry.getKey(), List.copyOf(invalid));
              }
            });

    Set<String> unownedRequired = new TreeSet<>();
    if (schemaPresent) {
      unownedRequired.addAll(schemaService.requiredPatchPropertyKeys(elementType));
      unownedRequired.removeAll(defaultedPropertyKeys(schemaService, elementType));
      unownedRequired.removeAll(ownedProperties);
    }

    List<String> reasons = new ArrayList<>();
    if (!schemaPresent) {
      reasons.add("schema missing");
    }
    if (!runtimeDescriptorPresent) {
      reasons.add("runtime descriptor missing");
    }
    if (!compilerContractCovered) {
      reasons.add("compiler contract coverage missing");
    }
    if (owners.isEmpty()) {
      reasons.add("generator ownership missing");
    }
    if (!invalidBySkill.isEmpty()) {
      reasons.add("ownership claims properties outside schema");
    }
    if (!unownedRequired.isEmpty()) {
      reasons.add("required properties have no owner or schema default");
    }

    ElementSupportStatus status =
        status(
            deprecated,
            schemaPresent,
            runtimeDescriptorPresent,
            compilerContractCovered,
            owners,
            invalidBySkill,
            unownedRequired);
    return new ElementSupportEntry(
        elementType,
        status,
        schemaPresent,
        runtimeDescriptorPresent,
        compilerContractCovered,
        deprecated,
        List.copyOf(new TreeSet<>(owners)),
        invalidBySkill,
        List.copyOf(unownedRequired),
        reasons);
  }

  private static ElementSupportStatus status(
      boolean deprecated,
      boolean schemaPresent,
      boolean runtimeDescriptorPresent,
      boolean compilerContractCovered,
      Set<String> owners,
      Map<String, List<String>> invalidBySkill,
      Set<String> unownedRequired) {
    if (deprecated) {
      return ElementSupportStatus.DEPRECATED;
    }
    if (!schemaPresent || !runtimeDescriptorPresent) {
      return ElementSupportStatus.UNSUPPORTED;
    }
    if (!compilerContractCovered
        || owners.isEmpty()
        || !invalidBySkill.isEmpty()
        || !unownedRequired.isEmpty()) {
      return ElementSupportStatus.PARTIAL;
    }
    return ElementSupportStatus.SUPPORTED;
  }

  private static Set<String> defaultedPropertyKeys(
      DeterministicElementSchemaService schemaService, String elementType) {
    Set<String> keys = new LinkedHashSet<>();
    for (PlanProperty property :
        schemaService.withUnconditionalSchemaDefaults(elementType, List.of())) {
      if (property != null && property.key() != null) {
        keys.add(property.key());
      }
    }
    return keys;
  }

  private static void collectOwnership(
      CompilerPipelineIndex pipelineIndex,
      Map<String, Set<String>> ownersByType,
      Map<String, Map<String, Set<String>>> propertiesByTypeAndSkill) {
    if (pipelineIndex == null) {
      return;
    }
    pipelineIndex.nodes().stream()
        .sorted(java.util.Comparator.comparing(CompilerPipelineNode::skillId))
        .forEach(
            node -> {
              GraphPatchOwnershipPolicy ownership = node.ownership();
              Set<String> ownedTypes = new LinkedHashSet<>(ownership.nodeTypes());
              ownedTypes.addAll(ownership.properties().keySet());
              for (String elementType : ownedTypes) {
                ownersByType
                    .computeIfAbsent(elementType, ignored -> new TreeSet<>())
                    .add(node.skillId());
              }
              ownership.properties().forEach(
                  (elementType, properties) ->
                      propertiesByTypeAndSkill
                          .computeIfAbsent(elementType, ignored -> new TreeMap<>())
                          .computeIfAbsent(node.skillId(), ignored -> new TreeSet<>())
                          .addAll(properties));
            });
  }

  private static Set<String> runtimeDescriptorTypes(Path repoRoot) {
    if (repoRoot == null) {
      return Set.of();
    }
    Path elementsRoot = repoRoot.resolve(RUNTIME_ELEMENTS);
    if (!Files.isDirectory(elementsRoot)) {
      return Set.of();
    }
    Set<String> types = new TreeSet<>();
    try (Stream<Path> directories = Files.list(elementsRoot)) {
      directories
          .filter(Files::isDirectory)
          .filter(ElementSupportMatrixBuilder::hasDescription)
          .map(path -> path.getFileName().toString())
          .forEach(types::add);
    } catch (IOException e) {
      throw new IllegalStateException(
          "Failed to scan runtime element descriptors: " + elementsRoot, e);
    }
    return Set.copyOf(types);
  }

  private static boolean hasDescription(Path directory) {
    return Files.isRegularFile(directory.resolve("description.yaml"))
        || Files.isRegularFile(directory.resolve("description.yml"));
  }
}
