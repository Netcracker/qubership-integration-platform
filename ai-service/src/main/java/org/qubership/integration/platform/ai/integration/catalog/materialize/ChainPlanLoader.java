package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.ChatMdc;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanSnapshot;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.PlanTreeUtils;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ConnectionPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;
import org.slf4j.MDC;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Parses batch JSON and/or merges runtime context from {@link ActiveChainPlanService}. */
@ApplicationScoped
public class ChainPlanLoader {

  private static final Logger LOG = Logger.getLogger(ChainPlanLoader.class);

  private final ObjectMapper objectMapper;
  private final ActiveChainPlanService activeChainPlanService;

  @Inject
  public ChainPlanLoader(ObjectMapper objectMapper, ActiveChainPlanService activeChainPlanService) {
    this.objectMapper = objectMapper;
    this.activeChainPlanService = activeChainPlanService;
  }

  private static final String SOURCE_APPROVED_ACTIVE_SNAPSHOT = "approved_active_snapshot";

  /**
   * Loads and optionally enriches a plan for {@code batchJson}; {@code chainId} is used for merge
   * logs only.
   */
  public ChainPlanLoadResult load(String chainId, String batchJson) {
    String conversationId = conversationIdFromMdc();
    boolean conversationApproved =
        conversationId != null && activeChainPlanService.isApproved(conversationId);
    if (conversationApproved) {
      return loadApprovedFromActiveSnapshot(chainId, conversationId, batchJson);
    }

    boolean fromActiveOnly = isBlankOrEmptyPlanJson(batchJson);

    if (fromActiveOnly) {
      if (conversationId == null) {
        return new ChainPlanLoadResult.LegacyEmpty();
      }
      Optional<ActiveChainPlanSnapshot> snap = activeChainPlanService.getActive(conversationId);
      if (snap.isEmpty()) {
        return new ChainPlanLoadResult.LegacyEmpty();
      }
      ChainImplementationPlan plan = snap.get().plan();
      String source = planSource(fromActiveOnly);
      return new ChainPlanLoadResult.Loaded(plan, conversationId, source);
    }

    ChainImplementationPlan plan;
    try {
      plan = objectMapper.readValue(batchJson, ChainImplementationPlan.class);
    } catch (JsonProcessingException e) {
      LOG.warnf(e, "ChainPlanLoader: invalid batch JSON");
      String detail = e.getOriginalMessage() != null ? e.getOriginalMessage() : e.getMessage();
      return new ChainPlanLoadResult.Error("invalid JSON: " + detail);
    }
    if (conversationId != null) {
      mergeActiveSnapshotContext(chainId, conversationId, plan, conversationApproved);
    }
    String source = planSource(fromActiveOnly);
    return new ChainPlanLoadResult.Loaded(plan, conversationId, source);
  }

  private ChainPlanLoadResult loadApprovedFromActiveSnapshot(
      String chainId, String conversationId, String batchJson) {
    Optional<ActiveChainPlanSnapshot> snap = activeChainPlanService.getActive(conversationId);
    if (snap.isEmpty()) {
      LOG.warnf(
          "ChainPlanLoader: approved conversation has no active plan snapshot chainId=%s"
              + " conversationId=%s",
          chainId, conversationId);
      return new ChainPlanLoadResult.Error("no active plan snapshot for approved conversation");
    }
    if (!isBlankOrEmptyPlanJson(batchJson)) {
      int len = batchJson != null ? batchJson.length() : 0;
      LOG.infof(
          "ChainPlanLoader: ignoring LLM batchJson for approved plan chainId=%s conversationId=%s"
              + " batchJsonLength=%d",
          chainId, conversationId, len);
    }
    ChainImplementationPlan plan = snap.get().plan();
    return new ChainPlanLoadResult.Loaded(plan, conversationId, SOURCE_APPROVED_ACTIVE_SNAPSHOT);
  }

  private void mergeActiveSnapshotContext(
      String chainId,
      String conversationId,
      ChainImplementationPlan plan,
      boolean conversationApproved) {
    Optional<ActiveChainPlanSnapshot> activeSnapshot =
        activeChainPlanService.getActive(conversationId);
    int snapshotRuntimeRows =
        activeSnapshot.map(snap -> mergeRuntimeIdsFromPlan(snap.plan(), plan)).orElse(0);
    int restoredConnectionBlocks =
        activeSnapshot
            .filter(snap -> conversationApproved)
            .map(snap -> restoreMissingConnectionsFromPlan(snap.plan(), plan))
            .orElse(0);
    if (snapshotRuntimeRows > 0) {
      LOG.infof(
          "plan materialize: merged runtime ids from active snapshot chainId=%s conversationId=%s"
              + " snapshotClientIds=%d",
          chainId, conversationId, snapshotRuntimeRows);
    }
    if (restoredConnectionBlocks > 0) {
      LOG.infof(
          "plan materialize: restored missing connections from approved snapshot chainId=%s"
              + " conversationId=%s connectionBlocks=%d",
          chainId, conversationId, restoredConnectionBlocks);
    }
    activeChainPlanService.updateActivePlan(conversationId, plan);
  }

  private static String conversationIdFromMdc() {
    String raw = MDC.get(ChatMdc.CONVERSATION_ID);
    if (raw == null) {
      return null;
    }
    String t = raw.trim();
    return t.isEmpty() ? null : t;
  }

  private static String planSource(boolean fromActiveOnly) {
    return fromActiveOnly ? "active_snapshot" : "batch_json";
  }

  /** True when the agent sent no plan body or only the empty-object sentinel {@code {}}. */
  static boolean isBlankOrEmptyPlanJson(String batchJson) {
    if (batchJson == null || batchJson.isBlank()) {
      return true;
    }
    String t = batchJson.trim();
    return "{}".equals(t) || "null".equalsIgnoreCase(t);
  }

  private static int mergeRuntimeIdsFromPlan(
      ChainImplementationPlan source, ChainImplementationPlan target) {
    Map<String, String> catalogByClient = new HashMap<>();
    Map<String, String> parentByClient = new HashMap<>();
    collectRuntimeIdsFromRoots(source.getElements(), catalogByClient, parentByClient);
    applyRuntimeIdsToRoots(target.getElements(), catalogByClient, parentByClient);
    return catalogByClient.size();
  }

  private static void collectRuntimeIdsFromRoots(
      List<ElementPlan> roots,
      Map<String, String> catalogByClient,
      Map<String, String> parentByClient) {
    PlanTreeUtils.preOrder(
        roots,
        node -> {
          String clientId = CatalogStrings.blankToNull(node.getClientId());
          if (clientId != null) {
            String cat = CatalogStrings.blankToNull(node.getElementId());
            if (cat != null) {
              catalogByClient.put(clientId, cat);
            }
            String par = CatalogStrings.blankToNull(node.getParentElementId());
            if (par != null) {
              parentByClient.put(clientId, par);
            }
          }
        });
  }

  private static void applyRuntimeIdsToRoots(
      List<ElementPlan> roots,
      Map<String, String> catalogByClient,
      Map<String, String> parentByClient) {
    PlanTreeUtils.preOrder(
        roots,
        node -> {
          String clientId = CatalogStrings.blankToNull(node.getClientId());
          if (clientId != null) {
            if (catalogByClient.containsKey(clientId)) {
              node.setElementId(catalogByClient.get(clientId));
            }
            if (parentByClient.containsKey(clientId)) {
              node.setParentElementId(parentByClient.get(clientId));
            }
          }
        });
  }

  private static int restoreMissingConnectionsFromPlan(
      ChainImplementationPlan source, ChainImplementationPlan target) {
    if (source == null || target == null) {
      return 0;
    }
    int restored = 0;
    if (connectionsEmpty(target.getConnections()) && !connectionsEmpty(source.getConnections())) {
      target.setConnections(source.getConnections());
      restored++;
    }
    Map<String, ElementPlan> sourceByClientId = PlanTreeUtils.indexByClientId(source.getElements());
    restored += restoreMissingNodeConnections(target.getElements(), sourceByClientId);
    return restored;
  }

  private static int restoreMissingNodeConnections(
      List<ElementPlan> targetRoots, Map<String, ElementPlan> sourceByClientId) {
    if (targetRoots == null || targetRoots.isEmpty()) {
      return 0;
    }
    int restored = 0;
    for (ElementPlan target : targetRoots) {
      if (target == null) {
        continue;
      }
      String clientId = CatalogStrings.blankToNull(target.getClientId());
      ElementPlan source = clientId != null ? sourceByClientId.get(clientId) : null;
      if (source != null
          && connectionsEmpty(target.getConnections())
          && !connectionsEmpty(source.getConnections())) {
        target.setConnections(source.getConnections());
        restored++;
      }
      restored += restoreMissingNodeConnections(target.getChildren(), sourceByClientId);
    }
    return restored;
  }

  private static boolean connectionsEmpty(List<ConnectionPlan> list) {
    return list == null || list.isEmpty();
  }
}
