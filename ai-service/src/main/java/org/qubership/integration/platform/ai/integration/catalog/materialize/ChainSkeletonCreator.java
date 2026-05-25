package org.qubership.integration.platform.ai.integration.catalog.materialize;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.jboss.logging.Logger;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogDescriptorResourceLoader;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogElementPlacementRules;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogElementDescriptorModel;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogCreateElementRequest;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.CreateElementsByJsonReport;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogRestSupport;
import org.qubership.integration.platform.ai.integration.catalog.util.CatalogStrings;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@ApplicationScoped
public class ChainSkeletonCreator {

  private static final Logger LOG = Logger.getLogger(ChainSkeletonCreator.class);

  private static final String ROOT_PARENT_ARG = "";

  private final CatalogRestClient catalogRestClient;
  private final CatalogElementPlacementRules catalogElementPlacementRules;
  private final CatalogDescriptorResourceLoader catalogDescriptorResourceLoader;
  private final ActiveChainPlanService activeChainPlanService;

  @Inject
  public ChainSkeletonCreator(
      @RestClient CatalogRestClient catalogRestClient,
      CatalogElementPlacementRules catalogElementPlacementRules,
      CatalogDescriptorResourceLoader catalogDescriptorResourceLoader,
      ActiveChainPlanService activeChainPlanService) {
    this.catalogRestClient = catalogRestClient;
    this.catalogElementPlacementRules = catalogElementPlacementRules;
    this.catalogDescriptorResourceLoader = catalogDescriptorResourceLoader;
    this.activeChainPlanService = activeChainPlanService;
  }

  /**
   * @param ctx {@link MaterializeContext#partial} is typically {@link
   *     CreateElementsByJsonReport#clientIds} — cleared and rebuilt later by the orchestrator after
   *     later stages.
   */
  public void createElements(
      String chainId, ChainImplementationPlan plan, MaterializeContext ctx, String conversationId) {
    List<ElementPlan> roots = plan.getElements() != null ? plan.getElements() : List.of();
    if (roots.isEmpty()) {
      persistPlan(conversationId, plan);
      return;
    }
    createLevel(chainId, null, roots, ctx, plan, conversationId);
  }

  private void persistPlan(String conversationId, ChainImplementationPlan plan) {
    if (conversationId == null) {
      return;
    }
    activeChainPlanService.updateActivePlan(conversationId, plan);
  }

  /**
   * Creates missing elements for one sibling list under {@code parentElementId} (null at chain root
   * for API), binds auto-shell ids from each create response, persists, then recurses into nested
   * {@code children}.
   */
  private void createLevel(
      String chainId,
      String parentElementId,
      List<ElementPlan> rows,
      MaterializeContext ctx,
      ChainImplementationPlan rootPlan,
      String conversationId) {
    if (rows == null || rows.isEmpty()) {
      return;
    }

    logLevelStats(chainId, parentElementId, rows);

    for (ElementPlan row : rows) {
      createSingleRow(chainId, parentElementId, row, ctx);
    }

    persistPlan(conversationId, rootPlan);

    recurseChildren(chainId, rows, ctx, rootPlan, conversationId);
  }

  private void logLevelStats(String chainId, String parentElementId, List<ElementPlan> rows) {
    int skipWithElementId = 0;
    int needCreate = 0;
    for (ElementPlan r : rows) {
      if (r == null) {
        continue;
      }
      if (CatalogStrings.blankToNull(r.getElementId()) != null) {
        skipWithElementId++;
      } else {
        needCreate++;
      }
    }
    LOG.infof(
        "plan level: chainId=%s parentElementId=%s siblingRows=%d toCreate=%d"
            + " alreadyMaterialized=%d",
        chainId,
        parentElementId != null ? parentElementId : "root",
        rows.size(),
        needCreate,
        skipWithElementId);
  }

  private void createSingleRow(
      String chainId, String parentElementId, ElementPlan row, MaterializeContext ctx) {
    if (row == null) {
      ctx.skeletonFailures.add(ReportFactories.skeleton(null, null, null, "plan row is null"));
      return;
    }
    String clientId = CatalogStrings.blankToNull(row.getClientId());
    String type = CatalogStrings.blankToNull(row.getType());
    if (clientId == null || type == null) {
      ctx.skeletonFailures.add(
          ReportFactories.skeleton(clientId, type, null, "plan row missing clientId or type"));
      return;
    }

    if (row.getElementId() != null) {
      return;
    }
    String placementArg =
        parentElementId != null && !parentElementId.isBlank() ? parentElementId : ROOT_PARENT_ARG;
    Optional<String> placementErr =
        catalogElementPlacementRules.validateCreatePlacement(chainId, type, placementArg);
    if (placementErr.isPresent()) {
      ctx.skeletonFailures.add(
          ReportFactories.skeleton(clientId, type, parentElementId, placementErr.get()));
      return;
    }

    LOG.infof(
        "catalog createElement: chainId=%s clientId=%s type=%s parentElementId=%s",
        chainId, clientId, type, parentElementId != null ? parentElementId : "null");

    CatalogRestClient.ChainDiffDto diff;
    try {
      diff =
          catalogRestClient.createElement(
              chainId,
              new CatalogCreateElementRequest(
                  type, CatalogStrings.blankToNull(parentElementId), null));
    } catch (Exception e) {
      LOG.warnf(
          e,
          "CatalogElementsCreatorService: createElement failed clientId=%s type=%s",
          clientId,
          type);
      ctx.skeletonFailures.add(
          ReportFactories.skeleton(
              clientId,
              type,
              parentElementId,
              CatalogRestSupport.describeExceptionForToolResult(e)));
      return;
    }

    List<CatalogRestClient.ElementSummaryDto> created = diff.createdElements();
    if (created == null || created.isEmpty()) {
      ctx.skeletonFailures.add(
          ReportFactories.skeleton(clientId, type, null, "catalog returned no createdElements"));
      return;
    }

    int primaryIdx = indexOfPrimaryCreated(created, type);
    if (primaryIdx < 0) {
      ctx.skeletonFailures.add(
          ReportFactories.skeleton(
              clientId, type, null, "catalog create response has no element of type " + type));
      return;
    }
    CatalogRestClient.ElementSummaryDto primary = created.get(primaryIdx);
    String elementId = CatalogStrings.blankToNull(primary.id());
    if (elementId == null) {
      ctx.skeletonFailures.add(
          ReportFactories.skeleton(clientId, type, null, "catalog returned empty element id"));
      return;
    }
    row.setElementId(elementId);
    row.setParentElementId(parentElementId);
    ctx.partial.put(clientId, elementId);

    bindShellsForCreatedRow(chainId, clientId, row, created, primaryIdx, ctx);

    LOG.infof(
        "catalog createElement done: chainId=%s clientId=%s type=%s elementId=%s"
            + " responseElements=%d",
        chainId, clientId, type, elementId, created.size());
  }

  private void bindShellsForCreatedRow(
      String chainId,
      String clientId,
      ElementPlan row,
      List<CatalogRestClient.ElementSummaryDto> created,
      int primaryIdx,
      MaterializeContext ctx) {
    Map<String, ArrayDeque<String>> pool = buildShellPool(created, primaryIdx);
    int shellsBound = bindShellChildren(row, pool, ctx.partial);
    BindLiveResult liveResult = bindLiveReusableShellChildren(chainId, row, ctx.partial);
    if (shellsBound > 0) {
      LOG.infof(
          "plan bind auto-shells: chainId=%s parentClientId=%s parentElementId=%s bound=%d"
              + " poolTypes=%d",
          chainId, clientId, row.getElementId(), shellsBound, pool.size());
    }
    if (liveResult.boundTotal() > 0) {
      LOG.infof(
          "plan bind live auto-shells: chainId=%s parentClientId=%s parentElementId=%s bound=%d"
              + " pool=%s",
          chainId, clientId, row.getElementId(), liveResult.boundTotal(), liveResult.poolSummary());
    }
  }

  private void recurseChildren(
      String chainId,
      List<ElementPlan> rows,
      MaterializeContext ctx,
      ChainImplementationPlan rootPlan,
      String conversationId) {
    for (ElementPlan row : rows) {
      if (row == null) {
        continue;
      }
      if (row.getChildren() != null && !row.getChildren().isEmpty()) {
        if (row.getElementId() == null) {
          ctx.skeletonFailures.add(
              ReportFactories.skeleton(
                  CatalogStrings.blankToNull(row.getClientId()),
                  CatalogStrings.blankToNull(row.getType()),
                  null,
                  "cannot create children (missing elementId on parent after skeleton stage)"));
          continue;
        }
        createLevel(chainId, row.getElementId(), row.getChildren(), ctx, rootPlan, conversationId);
      }
    }
  }

  private record BindLiveResult(int boundTotal, String poolSummary) {
    static BindLiveResult empty() {
      return new BindLiveResult(0, "{}");
    }
  }

  private static int indexOfPrimaryCreated(
      List<CatalogRestClient.ElementSummaryDto> created, String expectedType) {
    String want = expectedType.trim();
    for (int i = 0; i < created.size(); i++) {
      CatalogRestClient.ElementSummaryDto el = created.get(i);
      if (el == null) {
        continue;
      }
      String t = el.type() != null ? el.type().trim() : "";
      if (want.equals(t)) {
        return i;
      }
    }
    return -1;
  }

  private static Map<String, ArrayDeque<String>> buildShellPool(
      List<CatalogRestClient.ElementSummaryDto> created, int skipIndex) {
    Map<String, ArrayDeque<String>> pool = new LinkedHashMap<>();
    for (int j = 0; j < created.size(); j++) {
      if (j == skipIndex) {
        continue;
      }
      CatalogRestClient.ElementSummaryDto el = created.get(j);
      addToPool(pool, el != null ? el.type() : null, el != null ? el.id() : null);
    }
    return pool;
  }

  private static int bindShellChildren(
      ElementPlan row,
      Map<String, ArrayDeque<String>> pool,
      LinkedHashMap<String, String> partial) {
    if (row.getChildren() == null || row.getElementId() == null) {
      return 0;
    }
    removePreassignedShellIdsFromPool(row.getChildren(), pool);
    int bound = 0;
    for (ElementPlan child : row.getChildren()) {
      if (bindShellChild(row, child, pool, partial)) {
        bound++;
      }
    }
    return bound;
  }

  /**
   * When plan rows already carry {@code elementId} (e.g. merged from an active snapshot), {@link
   * #bindShellChild} skips them and would leave matching shell ids in the pool so a later sibling
   * of the same type could bind to the wrong catalog id. Drain those ids from the pool first.
   */
  private static void removePreassignedShellIdsFromPool(
      List<ElementPlan> children, Map<String, ArrayDeque<String>> pool) {
    if (children == null || pool == null || pool.isEmpty()) {
      return;
    }
    for (ElementPlan child : children) {
      if (child == null) {
        continue;
      }
      String cid = CatalogStrings.blankToNull(child.getElementId());
      String ctype = CatalogStrings.blankToNull(child.getType());
      if (cid == null || ctype == null) {
        continue;
      }
      ArrayDeque<String> q = pool.get(ctype);
      if (q == null) {
        continue;
      }
      q.removeFirstOccurrence(cid);
    }
  }

  private static boolean bindShellChild(
      ElementPlan parent,
      ElementPlan child,
      Map<String, ArrayDeque<String>> pool,
      LinkedHashMap<String, String> partial) {
    if (child == null || child.getElementId() != null) {
      return false;
    }
    String ctype = CatalogStrings.blankToNull(child.getType());
    if (ctype == null) {
      return false;
    }
    ArrayDeque<String> q = pool.get(ctype);
    if (q == null || q.isEmpty()) {
      return false;
    }
    String shellId = q.pollFirst();
    child.setElementId(shellId);
    child.setParentElementId(parent.getElementId());
    String cc = CatalogStrings.blankToNull(child.getClientId());
    if (cc != null) {
      partial.put(cc, shellId);
    }
    return true;
  }

  private BindLiveResult bindLiveReusableShellChildren(
      String chainId, ElementPlan row, LinkedHashMap<String, String> partial) {
    if (row.getChildren() == null || row.getChildren().isEmpty() || row.getElementId() == null) {
      return BindLiveResult.empty();
    }
    String parentType = CatalogStrings.blankToNull(row.getType());
    if (parentType == null) {
      return BindLiveResult.empty();
    }
    Optional<CatalogElementDescriptorModel> descriptor =
        catalogDescriptorResourceLoader.load(parentType);
    if (descriptor.isEmpty() || !Boolean.TRUE.equals(descriptor.get().getContainer())) {
      return BindLiveResult.empty();
    }
    Map<String, String> allowedTypes = allAllowedChildTypes(descriptor.get().getAllowedChildren());
    if (allowedTypes.isEmpty()) {
      return BindLiveResult.empty();
    }

    CatalogElementResponseDto liveParent;
    try {
      liveParent = catalogRestClient.getElement(chainId, row.getElementId());
    } catch (Exception e) {
      LOG.warnf(
          e,
          "CatalogElementsCreatorService: failed to read parent children clientId=%s elementId=%s",
          CatalogStrings.blankToNull(row.getClientId()),
          row.getElementId());
      return BindLiveResult.empty();
    }
    if (liveParent == null) {
      LOG.warnf(
          "CatalogElementsCreatorService: getElement returned null clientId=%s elementId=%s",
          CatalogStrings.blankToNull(row.getClientId()), row.getElementId());
      return BindLiveResult.empty();
    }
    Map<String, ArrayDeque<String>> pool = buildLiveShellPool(liveParent.children, allowedTypes);
    String poolSummary = formatPoolSizes(pool);
    int bound = bindShellChildren(row, pool, partial);
    return new BindLiveResult(bound, poolSummary);
  }

  /**
   * Every declared allowed child type may be reused from live read-back (including {@code
   * one-or-many}).
   */
  private static Map<String, String> allAllowedChildTypes(Map<String, String> allowedChildren) {
    Map<String, String> out = new LinkedHashMap<>();
    if (allowedChildren == null || allowedChildren.isEmpty()) {
      return out;
    }
    for (Map.Entry<String, String> e : allowedChildren.entrySet()) {
      String type = CatalogStrings.blankToNull(e.getKey());
      if (type != null) {
        out.put(type, CatalogStrings.blankToNull(e.getValue()));
      }
    }
    return out;
  }

  private static Map<String, ArrayDeque<String>> buildLiveShellPool(
      List<CatalogElementResponseDto> children, Map<String, String> allowedTypes) {
    Map<String, ArrayDeque<String>> pool = new LinkedHashMap<>();
    if (children == null || children.isEmpty()) {
      return pool;
    }
    for (CatalogElementResponseDto child : children) {
      if (child == null) {
        continue;
      }
      String type = CatalogStrings.blankToNull(child.type);
      String id = CatalogStrings.blankToNull(child.id);
      if (type != null && id != null && allowedTypes.containsKey(type)) {
        addToPool(pool, type, id);
      }
    }
    return pool;
  }

  private static String formatPoolSizes(Map<String, ArrayDeque<String>> pool) {
    if (pool == null || pool.isEmpty()) {
      return "{}";
    }
    StringBuilder sb = new StringBuilder("{");
    boolean first = true;
    for (Map.Entry<String, ArrayDeque<String>> e : pool.entrySet()) {
      if (!first) {
        sb.append(", ");
      }
      first = false;
      int sz = e.getValue() != null ? e.getValue().size() : 0;
      sb.append(e.getKey()).append(":").append(sz);
    }
    sb.append("}");
    return sb.toString();
  }

  private static void addToPool(Map<String, ArrayDeque<String>> pool, String type, String id) {
    String t = CatalogStrings.blankToNull(type);
    String i = CatalogStrings.blankToNull(id);
    if (t != null && i != null) {
      pool.computeIfAbsent(t, k -> new ArrayDeque<>()).addLast(i);
    }
  }

  public static final class MaterializeContext {
    public final LinkedHashMap<String, String> partial;
    public final List<CreateElementsByJsonReport.StageFailure> skeletonFailures = new ArrayList<>();

    public MaterializeContext(LinkedHashMap<String, String> partial) {
      this.partial = partial;
    }
  }
}
