package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ChainPlanBindingPreflightService;
import org.qubership.integration.platform.ai.chat.chainplan.PlanOpenItem;
import org.qubership.integration.platform.ai.integration.catalog.materialize.plan.PlanTreeUtils;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;
import org.qubership.integration.platform.ai.integration.catalog.tool.CatalogToolResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Creates chain elements from a {@code ChainImplementationPlan}-shaped batch JSON (catalog HTTP
 * orchestration).
 *
 * <p>{@link
 * org.qubership.integration.platform.ai.integration.catalog.tool.CatalogElementTools#createElementsByJson}
 * delegates here. Parses or loads the plan, validates the tree, creates elements depth-first, fills
 * {@code elementId} / {@code parentElementId} on the plan, persists the mutated plan to {@link
 * org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService} when possible (via
 * loader and skeleton), applies plan connections and {@code expectedProperties}, and returns a
 * structured {@link CreateElementsByJsonReport} inside the catalog tool result envelope.
 */
@ApplicationScoped
public class CatalogElementsCreatorService {

  private static final String TOOL = "createElementsByJson";

  private static final Logger LOG = Logger.getLogger(CatalogElementsCreatorService.class);

  private final ObjectMapper objectMapper;
  private final ChainPlanLoader chainPlanLoader;
  private final ChainPlanValidator chainPlanValidator;
  private final ChainSkeletonCreator chainSkeletonCreator;
  private final ChainConnectionsApplier chainConnectionsApplier;
  private final ChainPropertiesApplier chainPropertiesApplier;
  private final ChainPlanReconciler chainPlanReconciler;
  private final ActiveChainPlanService activeChainPlanService;
  private final ChainPlanPropertyKeysValidator chainPlanPropertyKeysValidator;
  private final ChainPlanBindingPreflightService bindingPreflightService;

  @Inject
  public CatalogElementsCreatorService(
      ObjectMapper objectMapper,
      ChainPlanLoader chainPlanLoader,
      ChainPlanValidator chainPlanValidator,
      ChainSkeletonCreator chainSkeletonCreator,
      ChainConnectionsApplier chainConnectionsApplier,
      ChainPropertiesApplier chainPropertiesApplier,
      ChainPlanReconciler chainPlanReconciler,
      ActiveChainPlanService activeChainPlanService,
      ChainPlanPropertyKeysValidator chainPlanPropertyKeysValidator,
      ChainPlanBindingPreflightService bindingPreflightService) {
    this.objectMapper = objectMapper;
    this.chainPlanLoader = chainPlanLoader;
    this.chainPlanValidator = chainPlanValidator;
    this.chainSkeletonCreator = chainSkeletonCreator;
    this.chainConnectionsApplier = chainConnectionsApplier;
    this.chainPropertiesApplier = chainPropertiesApplier;
    this.chainPlanReconciler = chainPlanReconciler;
    this.activeChainPlanService = activeChainPlanService;
    this.chainPlanPropertyKeysValidator = chainPlanPropertyKeysValidator;
    this.bindingPreflightService = bindingPreflightService;
  }

  /**
   * Materializes elements for the given chain from batch JSON or the active captured plan.
   *
   * @param chainId target chain id
   * @param batchJson plan JSON; blank uses active plan for {@link
   *     org.qubership.integration.platform.ai.chat.ChatMdc#CONVERSATION_ID} when present
   * @return catalog tool result JSON with {@link CreateElementsByJsonReport} in {@code data}, or
   *     {@code ok:false} for fatal validation errors
   */
  public String createFromBatchJson(String chainId, String batchJson) {
    String cid = chainId != null ? chainId.trim() : "";
    if (cid.isEmpty()) {
      return CatalogToolResult.error(
          objectMapper,
          TOOL,
          CatalogToolResult.CODE_INVALID_ARGUMENT,
          "chainId is required",
          null);
    }

    ChainPlanLoadResult loadResult = chainPlanLoader.load(cid, batchJson);
    if (loadResult instanceof ChainPlanLoadResult.LegacyEmpty) {
      return CatalogToolResult.successMessage(
          objectMapper,
          TOOL,
          "No conversation context or active plan for empty batchJson.",
          Map.of());
    }
    if (loadResult instanceof ChainPlanLoadResult.Error(String errMsg)) {
      return CatalogToolResult.error(
          objectMapper, TOOL, CatalogToolResult.CODE_PLAN_VALIDATION_ERROR, errMsg);
    }
    if (!(loadResult
        instanceof ChainPlanLoadResult.Loaded(
            ChainImplementationPlan plan, String conversationId, String planSource))) {
      throw new IllegalStateException("Unexpected load result");
    }

    List<ElementPlan> rootsForLog = plan.getElements() != null ? plan.getElements() : List.of();

    if (conversationId != null && !conversationId.isBlank()) {
      List<PlanOpenItem> sanitizeUnknown =
          chainPlanPropertyKeysValidator.sanitizeAndCollectUnknownKeys(plan);
      activeChainPlanService.reconcileActiveSnapshotOpenItems(
          conversationId, plan, true, sanitizeUnknown, null);
    }

    LOG.infof(
        "plan materialize: start chainId=%s conversationId=%s source=%s planNodes=%d rootRows=%d",
        cid,
        conversationId != null ? conversationId : "none",
        planSource,
        PlanTreeUtils.count(plan.getElements()),
        rootsForLog.size());

    Optional<String> structureErr = chainPlanValidator.validate(cid, plan);
    if (structureErr.isPresent()) {
      LOG.warnf(
          "plan materialize: validation failed chainId=%s conversationId=%s reason=%s",
          cid, conversationId != null ? conversationId : "none", structureErr.get());
      return CatalogToolResult.error(
          objectMapper, TOOL, CatalogToolResult.CODE_PLAN_VALIDATION_ERROR, structureErr.get());
    }

    ChainPlanBindingPreflightService.PreflightResult bindingPreflight =
        bindingPreflightService.enrichOperationBindings(plan);
    if (!bindingPreflight.openItems().isEmpty()) {
      String reason =
          bindingPreflight.openItems().get(0).message() != null
              ? bindingPreflight.openItems().get(0).message()
              : "Unresolved operation binding in plan";
      LOG.warnf(
          "plan materialize: binding preflight failed chainId=%s conversationId=%s openItems=%d"
              + " reason=%s",
          cid,
          conversationId != null ? conversationId : "none",
          bindingPreflight.openItems().size(),
          reason);
      return CatalogToolResult.error(
          objectMapper,
          TOOL,
          CatalogToolResult.CODE_PLAN_VALIDATION_ERROR,
          "Unresolved operation binding must be fixed before materialization: " + reason);
    }

    CreateElementsByJsonReport report = new CreateElementsByJsonReport();
    report.chainId = cid;
    ChainSkeletonCreator.MaterializeContext ctx =
        new ChainSkeletonCreator.MaterializeContext(report.clientIds);

    chainSkeletonCreator.createElements(cid, plan, ctx, conversationId);

    report.stages.skeleton = ReportFactories.finalizeStage(ctx.skeletonFailures);

    chainConnectionsApplier.applyConnectionsStage(cid, plan, report);
    chainPropertiesApplier.applyPropertiesStage(cid, plan, report);
    chainPlanReconciler.reconcileAndLog(cid, plan, report);

    LinkedHashMap<String, String> full =
        PlanTreeUtils.collectClientIdToElementId(plan.getElements());
    report.clientIds.clear();
    report.clientIds.putAll(full);

    LOG.infof(
        "plan materialize: done chainId=%s conversationId=%s resolvedClientIds=%d skeletonOk=%s"
            + " connectionsOk=%s propertiesOk=%s",
        cid,
        conversationId != null ? conversationId : "none",
        full.size(),
        report.stages.skeleton.ok,
        report.stages.connections.ok,
        report.stages.properties.ok);

    if (conversationId != null && !conversationId.isBlank()) {
      activeChainPlanService.reconcileActiveSnapshotOpenItems(
          conversationId, plan, false, List.of(), report);
    }

    return CatalogToolResult.successMessage(objectMapper, TOOL, "Plan materialized.", report);
  }
}
