package org.qubership.integration.platform.ai.integration.catalog.materialize;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainLabel;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogChainSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateChainRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateDependencyRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateEnvironmentRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateSystemRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogDependencyDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemFilter;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogSystemSearchRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogTransferElementsRequest;

/**
 * In-memory catalog for CREATE/EDIT parity tests at the {@link CatalogRestClient} boundary.
 *
 * <p>Models generated children on container create, optional-child deletion, transfer semantics,
 * and dependency lifecycle without live CIP.
 */
final class InMemoryCatalogRestClient implements CatalogRestClient {

  enum GeneratedChildDelivery {
    INLINE,
    READ_BACK
  }

  enum TransferBehavior {
    UPDATE_PARENT,
    DEPENDENCY_INSTEAD
  }

  private final Map<String, CatalogElementDescriptorDto> library;
  private final Map<String, ChainState> chains = new LinkedHashMap<>();
  private GeneratedChildDelivery generatedChildDelivery = GeneratedChildDelivery.INLINE;
  private TransferBehavior transferBehavior = TransferBehavior.UPDATE_PARENT;
  private final AtomicLong dependencySequence = new AtomicLong(1);

  InMemoryCatalogRestClient(Map<String, CatalogElementDescriptorDto> library) {
    this.library = Map.copyOf(library);
  }

  void reset() {
    chains.clear();
    dependencySequence.set(1);
  }

  void resetAll() {
    reset();
    generatedChildDelivery = GeneratedChildDelivery.INLINE;
    transferBehavior = TransferBehavior.UPDATE_PARENT;
  }

  void setGeneratedChildDelivery(GeneratedChildDelivery generatedChildDelivery) {
    this.generatedChildDelivery = Objects.requireNonNull(generatedChildDelivery);
  }

  void setTransferBehavior(TransferBehavior transferBehavior) {
    this.transferBehavior = Objects.requireNonNull(transferBehavior);
  }

  void ensureChain(String chainId, String name) {
    chains.computeIfAbsent(chainId, id -> new ChainState(id, name, "Demo"));
  }

  @Override
  public ChainDto createChain(CatalogCreateChainRequest body) {
    String chainId = UUID.randomUUID().toString();
    chains.put(chainId, new ChainState(chainId, body.name(), body.description()));
    return new ChainDto(chainId, body.name(), body.description());
  }

  @Override
  public ChainDto getChain(String chainId) {
    ChainState chain = requireChain(chainId);
    return new ChainDto(chain.chainId, chain.name, chain.description);
  }

  @Override
  public void deleteChain(String chainId) {
    chains.remove(chainId);
  }

  @Override
  public List<FolderItemDto> searchFolderItems(CatalogChainSearchRequest request) {
    throw new UnsupportedOperationException("searchFolderItems");
  }

  @Override
  public SnapshotDto createSnapshot(String chainId) {
    throw new UnsupportedOperationException("createSnapshot");
  }

  @Override
  public List<SnapshotDto> listSnapshots(String chainId) {
    throw new UnsupportedOperationException("listSnapshots");
  }

  @Override
  public org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorDto
      getLibraryElement(String name) {
    CatalogElementDescriptorDto descriptor = library.get(name);
    if (descriptor == null) {
      return null;
    }
    return copy(descriptor);
  }

  @Override
  public ChainDiffDto createElement(String chainId, CatalogCreateElementRequest body) {
    ChainState chain = requireChain(chainId);
    StoredElement created = createStoredElement(chain, body.type(), body.parentElementId(), Map.of());
    List<ElementSummaryDto> createdRows = new ArrayList<>();
    createdRows.add(toSummary(created, generatedChildDelivery == GeneratedChildDelivery.INLINE));
    if (generatedChildDelivery == GeneratedChildDelivery.INLINE) {
      appendGeneratedSummaries(created, createdRows);
    }
    return new ChainDiffDto(createdRows, List.of(), List.of());
  }

  @Override
  public ChainDiffDto updateElement(String chainId, String elementId, Map<String, Object> body) {
    ChainState chain = requireChain(chainId);
    StoredElement element = requireElement(chain, elementId);
    if (body != null) {
      Object name = body.get("name");
      if (name instanceof String stringName && !stringName.isBlank()) {
        element.name = stringName;
      }
      Object properties = body.get("properties");
      if (properties instanceof Map<?, ?> propertyMap) {
        element.properties = new LinkedHashMap<>();
        propertyMap.forEach((key, value) -> element.properties.put(String.valueOf(key), value));
      }
    }
    return new ChainDiffDto(List.of(), List.of(toSummary(element, true)), List.of());
  }

  @Override
  public ChainDiffDto deleteElements(String chainId, List<String> elementsIds) {
    ChainState chain = requireChain(chainId);
    List<ElementSummaryDto> removed = new ArrayList<>();
    for (String elementId : elementsIds == null ? List.<String>of() : elementsIds) {
      StoredElement element = chain.elements.remove(elementId);
      if (element != null) {
        removeDescendants(chain, elementId);
        removeDependenciesTouching(chain, elementId);
        removed.add(toSummary(element, false));
      }
    }
    return new ChainDiffDto(List.of(), List.of(), List.of(), removed, List.of());
  }

  @Override
  public List<CatalogElementResponseDto> listElements(String chainId) {
    ChainState chain = requireChain(chainId);
    return chain.roots().stream().map(this::toResponseTree).toList();
  }

  @Override
  public CatalogElementResponseDto getElement(String chainId, String elementId) {
    StoredElement element = requireElement(requireChain(chainId), elementId);
    return toResponseTree(element);
  }

  @Override
  public ChainDiffDto transferElements(String chainId, CatalogTransferElementsRequest body) {
    ChainState chain = requireChain(chainId);
    List<String> elements = body.elements() == null ? List.of() : body.elements();
    if (transferBehavior == TransferBehavior.DEPENDENCY_INSTEAD) {
      List<DependencySummaryDto> created = new ArrayList<>();
      for (String elementId : elements) {
        StoredElement element = requireElement(chain, elementId);
        if (body.parentId() != null && !body.parentId().isBlank()) {
          String dependencyId = "dep-" + dependencySequence.getAndIncrement();
          chain.dependencies.put(
              dependencyId, new StoredDependency(dependencyId, body.parentId(), elementId));
          created.add(new DependencySummaryDto(dependencyId, body.parentId(), elementId));
        }
      }
      return new ChainDiffDto(List.of(), List.of(), created);
    }

    for (String elementId : elements) {
      StoredElement element = requireElement(chain, elementId);
      element.parentElementId = blankToNull(body.parentId());
    }
    return new ChainDiffDto(List.of(), List.of(), List.of());
  }

  @Override
  public ChainDiffDto createConnection(String chainId, CatalogCreateDependencyRequest body) {
    ChainState chain = requireChain(chainId);
    String dependencyId = "dep-" + dependencySequence.getAndIncrement();
    chain.dependencies.put(
        dependencyId, new StoredDependency(dependencyId, body.from(), body.to()));
    return new ChainDiffDto(
        List.of(),
        List.of(),
        List.of(new DependencySummaryDto(dependencyId, body.from(), body.to())));
  }

  @Override
  public List<CatalogDependencyDto> listDependencies(String chainId) {
    ChainState chain = requireChain(chainId);
    return chain.dependencies.values().stream().map(this::toDependencyDto).toList();
  }

  @Override
  public ChainDiffDto deleteDependencies(String chainId, List<String> dependenciesIds) {
    ChainState chain = requireChain(chainId);
    List<DependencySummaryDto> removed = new ArrayList<>();
    for (String dependencyId : dependenciesIds == null ? List.<String>of() : dependenciesIds) {
      StoredDependency dependency = chain.dependencies.remove(dependencyId);
      if (dependency != null) {
        removed.add(new DependencySummaryDto(dependency.id, dependency.from, dependency.to));
      }
    }
    return new ChainDiffDto(List.of(), List.of(), List.of(), List.of(), removed);
  }

  @Override
  public SystemDto createSystem(CatalogCreateSystemRequest body) {
    throw new UnsupportedOperationException("createSystem");
  }

  @Override
  public List<SystemDto> searchSystems(CatalogSystemSearchRequest body) {
    throw new UnsupportedOperationException("searchSystems");
  }

  @Override
  public List<SystemDto> filterSystems(List<CatalogSystemFilter> body) {
    throw new UnsupportedOperationException("filterSystems");
  }

  @Override
  public SystemDto getSystem(String systemId) {
    throw new UnsupportedOperationException("getSystem");
  }

  @Override
  public List<EnvironmentDto> getEnvironments(String systemId) {
    throw new UnsupportedOperationException("getEnvironments");
  }

  @Override
  public EnvironmentDto createEnvironment(String systemId, CatalogCreateEnvironmentRequest body) {
    throw new UnsupportedOperationException("createEnvironment");
  }

  @Override
  public List<SpecificationDto> getApiSpecifications(String systemId) {
    throw new UnsupportedOperationException("getApiSpecifications");
  }

  @Override
  public SpecificationDto getModel(String modelId) {
    throw new UnsupportedOperationException("getModel");
  }

  @Override
  public OperationDto getOperation(String operationId) {
    throw new UnsupportedOperationException("getOperation");
  }

  @Override
  public List<OperationDto> getOperations(
      String modelId, int offset, int count, String searchFilter) {
    throw new UnsupportedOperationException("getOperations");
  }

  String createSeededElement(String chainId, String type, String parentElementId, String name) {
    StoredElement created =
        createStoredElement(requireChain(chainId), type, parentElementId, Map.of());
    if (name != null && !name.isBlank()) {
      created.name = name;
    }
    return created.id;
  }

  private StoredElement createStoredElement(
      ChainState chain, String type, String parentElementId, Map<String, Object> properties) {
    StoredElement element = new StoredElement();
    element.id = type + "-" + UUID.randomUUID();
    element.type = type;
    element.parentElementId = blankToNull(parentElementId);
    element.name = type;
    element.properties = new LinkedHashMap<>(properties);
    chain.elements.put(element.id, element);

    CatalogElementDescriptorDto descriptor = library.get(type);
    if (descriptor != null && descriptor.container && descriptor.allowedChildren != null) {
      for (Map.Entry<String, CatalogChildQuantity> childRule :
          descriptor.allowedChildren.entrySet()) {
        int count = generatedCount(childRule.getValue());
        for (int index = 0; index < count; index++) {
          StoredElement child = new StoredElement();
          child.id = childRule.getKey() + "-" + UUID.randomUUID();
          child.type = childRule.getKey();
          child.parentElementId = element.id;
          child.name = childRule.getKey();
          child.properties = Map.of();
          chain.elements.put(child.id, child);
        }
      }
    }
    return element;
  }

  private static int generatedCount(CatalogChildQuantity quantity) {
    return switch (quantity) {
      case TWO_OR_MANY -> 2;
      case ONE, ONE_OR_ZERO, ONE_OR_MANY, ANY -> 1;
    };
  }

  private void appendGeneratedSummaries(StoredElement parent, List<ElementSummaryDto> createdRows) {
    for (StoredElement child : childrenOf(parent.id)) {
      createdRows.add(toSummary(child, true));
    }
  }

  private List<StoredElement> childrenOf(String parentId) {
    List<StoredElement> children = new ArrayList<>();
    for (StoredElement element : requireChainFromElement(parentId).elements.values()) {
      if (parentId.equals(element.parentElementId)) {
        children.add(element);
      }
    }
    children.sort((left, right) -> left.id.compareTo(right.id));
    return children;
  }

  private ChainState requireChainFromElement(String elementId) {
    for (ChainState chain : chains.values()) {
      if (chain.elements.containsKey(elementId)) {
        return chain;
      }
    }
    throw new IllegalArgumentException("Unknown element " + elementId);
  }

  private ElementSummaryDto toSummary(StoredElement element, boolean includeChildren) {
    List<ElementSummaryDto> children = List.of();
    if (includeChildren) {
      children =
          childrenOf(element.id).stream()
              .map(child -> toSummary(child, true))
              .toList();
    }
    return new ElementSummaryDto(
        element.id,
        element.type,
        Map.copyOf(element.properties),
        element.parentElementId,
        children);
  }

  private CatalogElementResponseDto toResponseTree(StoredElement element) {
    CatalogElementResponseDto dto = new CatalogElementResponseDto();
    dto.id = element.id;
    dto.type = element.type;
    dto.name = element.name;
    dto.parentElementId = element.parentElementId;
    dto.properties = Map.copyOf(element.properties);
    dto.children = childrenOf(element.id).stream().map(this::toResponseTree).toList();
    dto.mandatoryChecksPassed = true;
    return dto;
  }

  private CatalogDependencyDto toDependencyDto(StoredDependency dependency) {
    CatalogDependencyDto dto = new CatalogDependencyDto();
    dto.id = dependency.id;
    dto.from = dependency.from;
    dto.to = dependency.to;
    return dto;
  }

  private void removeDescendants(ChainState chain, String parentId) {
    List<String> queue = new ArrayList<>(List.of(parentId));
    while (!queue.isEmpty()) {
      String current = queue.remove(0);
      List<String> childIds =
          chain.elements.values().stream()
              .filter(element -> current.equals(element.parentElementId))
              .map(element -> element.id)
              .toList();
      for (String childId : childIds) {
        chain.elements.remove(childId);
        removeDependenciesTouching(chain, childId);
        queue.add(childId);
      }
    }
  }

  private void removeDependenciesTouching(ChainState chain, String elementId) {
    chain
        .dependencies
        .entrySet()
        .removeIf(entry -> elementId.equals(entry.getValue().from) || elementId.equals(entry.getValue().to));
  }

  private ChainState requireChain(String chainId) {
    ChainState chain = chains.get(chainId);
    if (chain == null) {
      throw new IllegalArgumentException("Unknown chain " + chainId);
    }
    return chain;
  }

  private StoredElement requireElement(ChainState chain, String elementId) {
    StoredElement element = chain.elements.get(elementId);
    if (element == null) {
      throw new IllegalArgumentException("Unknown element " + elementId + " in chain " + chain.chainId);
    }
    return element;
  }

  private static CatalogElementDescriptorDto copy(CatalogElementDescriptorDto source) {
    CatalogElementDescriptorDto copy = new CatalogElementDescriptorDto();
    copy.name = source.name;
    copy.container = source.container;
    copy.allowedChildren =
        source.allowedChildren == null ? Map.of() : Map.copyOf(source.allowedChildren);
    copy.parentRestriction =
        source.parentRestriction == null ? List.of() : List.copyOf(source.parentRestriction);
    copy.ordered = source.ordered;
    copy.priorityProperty = source.priorityProperty;
    copy.mandatoryInnerElement = source.mandatoryInnerElement;
    copy.deprecated = source.deprecated;
    copy.oldStyleContainer = source.oldStyleContainer;
    copy.allowedInContainers = source.allowedInContainers;
    return copy;
  }

  private static String blankToNull(String value) {
    if (value == null || value.isBlank()) {
      return null;
    }
    return value.trim();
  }

  private static final class ChainState {
    private final String chainId;
    private final String name;
    private final String description;
    private final Map<String, StoredElement> elements = new LinkedHashMap<>();
    private final Map<String, StoredDependency> dependencies = new LinkedHashMap<>();

    private ChainState(String chainId, String name, String description) {
      this.chainId = chainId;
      this.name = name;
      this.description = description;
    }

    private List<StoredElement> roots() {
      return elements.values().stream()
          .filter(element -> element.parentElementId == null)
          .sorted((left, right) -> left.id.compareTo(right.id))
          .toList();
    }
  }

  private static final class StoredElement {
    private String id;
    private String type;
    private String name;
    private String parentElementId;
    private Map<String, Object> properties = Map.of();
  }

  private static final class StoredDependency {
    private final String id;
    private final String from;
    private final String to;

    private StoredDependency(String id, String from, String to) {
      this.id = id;
      this.from = from;
      this.to = to;
    }
  }
}
