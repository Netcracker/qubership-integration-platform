package org.qubership.integration.platform.ai.integration.catalog.cache;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Per-conversation cache of catalog systems and operations loaded during planning or implement. */
@ApplicationScoped
public class ConversationCatalogCache {

  private final CatalogOperationsReadCache operationsReadCache;
  private final ConcurrentHashMap<String, ConversationState> byConversation =
      new ConcurrentHashMap<>();

  @Inject
  public ConversationCatalogCache(CatalogOperationsReadCache operationsReadCache) {
    this.operationsReadCache = operationsReadCache;
  }

  public void rememberSystems(String conversationId, List<CatalogRestClient.SystemDto> systems) {
    if (blankConversation(conversationId) || systems == null) {
      return;
    }
    ConversationState state = state(conversationId);
    for (CatalogRestClient.SystemDto system : systems) {
      if (system != null && system.id() != null && !system.id().isBlank()) {
        state.systemsById.put(system.id(), system);
      }
    }
  }

  public void rememberActiveSystemId(String conversationId, String systemId) {
    String sid = CatalogStrings.blankToNull(systemId);
    if (blankConversation(conversationId) || sid == null) {
      return;
    }
    state(conversationId).activeSystemId = sid;
  }

  public void rememberSpecifications(
      String conversationId, List<CatalogRestClient.SpecificationDto> specifications) {
    if (blankConversation(conversationId) || specifications == null) {
      return;
    }
    ConversationState state = state(conversationId);
    for (CatalogRestClient.SpecificationDto spec : specifications) {
      rememberSpecification(state, spec);
    }
  }

  public void rememberOperation(
      String conversationId, CatalogRestClient.OperationDto operation) {
    String opId = operation != null ? CatalogStrings.blankToNull(operation.id()) : null;
    if (blankConversation(conversationId) || opId == null) {
      return;
    }
    state(conversationId).operationsById.put(opId, operation);
  }

  public Optional<CatalogRestClient.SpecificationDto> findSpecification(
      String conversationId, String modelId) {
    String mid = CatalogStrings.blankToNull(modelId);
    if (blankConversation(conversationId) || mid == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(state(conversationId).specificationsById.get(mid));
  }

  public boolean hasRememberedSpecifications(String conversationId) {
    if (blankConversation(conversationId)) {
      return false;
    }
    return !state(conversationId).specificationsById.isEmpty();
  }

  public boolean hasRememberedSystems(String conversationId) {
    if (blankConversation(conversationId)) {
      return false;
    }
    return !state(conversationId).systemsById.isEmpty();
  }

  public boolean isKnownSpecificationId(String conversationId, String specificationId) {
    String id = CatalogStrings.blankToNull(specificationId);
    if (blankConversation(conversationId) || id == null) {
      return false;
    }
    return state(conversationId).specificationsById.containsKey(id);
  }

  /** Id matches a remembered group id but is not a specification {@code id} from {@code data[]}. */
  public boolean isRememberedSpecificationGroupIdOnly(
      String conversationId, String specificationId) {
    String id = CatalogStrings.blankToNull(specificationId);
    if (blankConversation(conversationId) || id == null) {
      return false;
    }
    if (state(conversationId).specificationsById.containsKey(id)) {
      return false;
    }
    return state(conversationId).specificationsById.values().stream()
        .anyMatch(spec -> spec != null && id.equals(spec.specificationGroupId()));
  }

  public void rememberSpecificationsForSystem(
      String conversationId, String systemId, List<CatalogRestClient.SpecificationDto> specs) {
    rememberSpecifications(conversationId, specs);
    String sid = CatalogStrings.blankToNull(systemId);
    if (blankConversation(conversationId) || sid == null || specs == null) {
      return;
    }
    ConversationState state = state(conversationId);
    for (CatalogRestClient.SpecificationDto spec : specs) {
      if (spec != null && spec.id() != null && !spec.id().isBlank()) {
        state.specificationSystemIdByModelId.putIfAbsent(spec.id(), sid);
      }
    }
  }

  public Optional<String> findSpecificationOwnerSystemId(String conversationId, String modelId) {
    String mid = CatalogStrings.blankToNull(modelId);
    if (blankConversation(conversationId) || mid == null) {
      return Optional.empty();
    }
    Optional<CatalogRestClient.SpecificationDto> spec = findSpecification(conversationId, mid);
    if (spec.isPresent()) {
      String fromDto = CatalogStrings.blankToNull(spec.get().systemId());
      if (fromDto != null) {
        return Optional.of(fromDto);
      }
    }
    return Optional.ofNullable(state(conversationId).specificationSystemIdByModelId.get(mid));
  }

  public Optional<CatalogRestClient.SystemDto> findSystem(String conversationId, String systemId) {
    String sid = CatalogStrings.blankToNull(systemId);
    if (blankConversation(conversationId) || sid == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(state(conversationId).systemsById.get(sid));
  }

  public String activeSystemId(String conversationId) {
    if (blankConversation(conversationId)) {
      return null;
    }
    return state(conversationId).activeSystemId;
  }

  public List<CatalogRestClient.OperationDto> getOrLoadOperations(
      String conversationId, String modelId, String systemId) {
    String mid = CatalogStrings.blankToNull(modelId);
    if (blankConversation(conversationId) || mid == null) {
      return List.of();
    }
    ConversationState state = state(conversationId);
    String sid = CatalogStrings.blankToNull(systemId);
    if (sid != null) {
      state.activeSystemId = sid;
    }
    return state.operationsByModelId.computeIfAbsent(
        mid,
        ignored ->
            indexOperations(state, operationsReadCache.loadByModelId(mid), mid));
  }

  /**
   * Reloads operations for {@code modelId} from catalog without using conversation cache, and
   * replaces indexed operation ids for that model.
   */
  public List<CatalogRestClient.OperationDto> refreshOperations(
      String conversationId, String modelId, String systemId) {
    String mid = CatalogStrings.blankToNull(modelId);
    if (blankConversation(conversationId) || mid == null) {
      return List.of();
    }
    ConversationState state = state(conversationId);
    String sid = CatalogStrings.blankToNull(systemId);
    if (sid != null) {
      state.activeSystemId = sid;
    }
    List<CatalogRestClient.OperationDto> previous =
        state.operationsByModelId.getOrDefault(mid, List.of());
    Set<String> previousOpIds = new HashSet<>();
    for (CatalogRestClient.OperationDto op : previous) {
      if (op != null && op.id() != null && !op.id().isBlank()) {
        previousOpIds.add(op.id());
      }
    }
    for (String opId : previousOpIds) {
      state.operationsById.remove(opId);
    }
    List<CatalogRestClient.OperationDto> refreshed =
        indexOperations(state, operationsReadCache.loadByModelIdUncached(mid), mid);
    state.operationsByModelId.put(mid, refreshed);
    return refreshed;
  }

  public Optional<CatalogRestClient.OperationDto> findOperation(
      String conversationId, String operationId) {
    String opId = CatalogStrings.blankToNull(operationId);
    if (blankConversation(conversationId) || opId == null) {
      return Optional.empty();
    }
    return Optional.ofNullable(state(conversationId).operationsById.get(opId));
  }

  public List<CatalogRestClient.OperationDto> filterOperations(
      List<CatalogRestClient.OperationDto> operations, String searchFilter) {
    String filter = CatalogStrings.blankToNull(searchFilter);
    if (filter == null) {
      return operations;
    }
    String needle = filter.toLowerCase(Locale.ROOT);
    List<CatalogRestClient.OperationDto> matched = new ArrayList<>();
    for (CatalogRestClient.OperationDto op : operations) {
      if (op == null) {
        continue;
      }
      if (containsIgnoreCase(op.id(), needle)
          || containsIgnoreCase(op.name(), needle)
          || containsIgnoreCase(op.method(), needle)
          || containsIgnoreCase(op.path(), needle)) {
        matched.add(op);
      }
    }
    return matched;
  }

  private static void rememberSpecification(
      ConversationState state, CatalogRestClient.SpecificationDto spec) {
    if (spec == null || spec.id() == null || spec.id().isBlank()) {
      return;
    }
    state.specificationsById.put(spec.id(), spec);
    String ownerSystemId = CatalogStrings.blankToNull(spec.systemId());
    if (ownerSystemId != null) {
      state.specificationSystemIdByModelId.putIfAbsent(spec.id(), ownerSystemId);
    }
  }

  private static List<CatalogRestClient.OperationDto> indexOperations(
      ConversationState state, List<CatalogRestClient.OperationDto> loaded, String modelId) {
    List<CatalogRestClient.OperationDto> list = loaded != null ? loaded : List.of();
    List<CatalogRestClient.OperationDto> indexed = new ArrayList<>(list.size());
    for (CatalogRestClient.OperationDto op : list) {
      if (op == null || op.id() == null || op.id().isBlank()) {
        continue;
      }
      CatalogRestClient.OperationDto withModel = withModelId(op, modelId);
      state.operationsById.put(withModel.id(), withModel);
      indexed.add(withModel);
    }
    return List.copyOf(indexed);
  }

  private static CatalogRestClient.OperationDto withModelId(
      CatalogRestClient.OperationDto op, String modelId) {
    if (CatalogStrings.blankToNull(op.modelId()) != null || CatalogStrings.blankToNull(modelId) == null) {
      return op;
    }
    return new CatalogRestClient.OperationDto(
        op.id(), op.name(), op.method(), op.path(), modelId);
  }

  /** Drops all catalog lookup state for a conversation (test isolation and session cleanup). */
  public void clearConversation(String conversationId) {
    if (!blankConversation(conversationId)) {
      byConversation.remove(conversationId);
    }
  }

  private ConversationState state(String conversationId) {
    return byConversation.computeIfAbsent(conversationId, ignored -> new ConversationState());
  }

  private static boolean blankConversation(String conversationId) {
    return conversationId == null || conversationId.isBlank();
  }

  private static boolean containsIgnoreCase(String value, String needle) {
    return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
  }

  private static final class ConversationState {
    final Map<String, CatalogRestClient.SystemDto> systemsById = new ConcurrentHashMap<>();
    final Map<String, CatalogRestClient.SpecificationDto> specificationsById =
        new ConcurrentHashMap<>();
    final Map<String, List<CatalogRestClient.OperationDto>> operationsByModelId =
        new ConcurrentHashMap<>();
    final Map<String, CatalogRestClient.OperationDto> operationsById = new ConcurrentHashMap<>();
    final Map<String, String> specificationSystemIdByModelId = new ConcurrentHashMap<>();
    volatile String activeSystemId;
  }
}
