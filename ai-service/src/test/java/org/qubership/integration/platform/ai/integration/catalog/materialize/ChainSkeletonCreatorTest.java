package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogDescriptorResourceLoader;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogElementPlacementRules;
import org.qubership.integration.platform.ai.catalog.descriptor.model.CatalogElementDescriptorModel;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.model.CatalogElementResponseDto;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainSkeletonCreatorTest {

  private CatalogRestClient catalogRestClient;
  private CatalogElementPlacementRules placementRules;
  private CatalogDescriptorResourceLoader descriptorLoader;
  private ActiveChainPlanService activeChainPlanService;
  private ChainSkeletonCreator creator;

  @BeforeEach
  void setUp() {
    catalogRestClient = mock(CatalogRestClient.class);
    placementRules = mock(CatalogElementPlacementRules.class);
    descriptorLoader = mock(CatalogDescriptorResourceLoader.class);
    activeChainPlanService = mock(ActiveChainPlanService.class);
    creator =
        new ChainSkeletonCreator(
            catalogRestClient, placementRules, descriptorLoader, activeChainPlanService);
  }

  @Test
  void emptyPlanWithConversationPersistsOnceWithoutCreate() {
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>());

    ChainSkeletonCreator.MaterializeContext ctx =
        new ChainSkeletonCreator.MaterializeContext(new LinkedHashMap<>());

    creator.createElements("c1", plan, ctx, "conv-empty");

    verify(catalogRestClient, never()).createElement(any(), any());
    verify(activeChainPlanService, times(1)).updateActivePlan(eq("conv-empty"), eq(plan));
  }

  @Test
  void httpTriggerSingleCreateSetsElementIdAndPartial() {
    when(placementRules.validateCreatePlacement(eq("c1"), eq("http-trigger"), eq("")))
        .thenReturn(Optional.empty());
    when(catalogRestClient.createElement(eq("c1"), any()))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("elt-1", "http-trigger", Map.of())),
                List.of(),
                List.of()));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    ElementPlan root = new ElementPlan();
    root.setClientId("cli-root");
    root.setType("http-trigger");
    plan.setElements(new ArrayList<>(List.of(root)));

    LinkedHashMap<String, String> partial = new LinkedHashMap<>();
    ChainSkeletonCreator.MaterializeContext ctx =
        new ChainSkeletonCreator.MaterializeContext(partial);

    creator.createElements("c1", plan, ctx, null);

    assertEquals("elt-1", root.getElementId());
    assertNull(root.getParentElementId());
    assertEquals("elt-1", partial.get("cli-root"));
    assertTrue(ctx.skeletonFailures.isEmpty());
    verify(catalogRestClient, times(1)).createElement(eq("c1"), any());
    verify(activeChainPlanService, never()).updateActivePlan(any(), any());
  }

  @Test
  void containerCreateReturnsExtraShellElementsBindsShellIdsToChildren() {
    when(placementRules.validateCreatePlacement(eq("c1"), eq("container-x"), eq("")))
        .thenReturn(Optional.empty());
    List<CatalogRestClient.ElementSummaryDto> created =
        List.of(
            new CatalogRestClient.ElementSummaryDto("parent-cat", "container-x", Map.of()),
            new CatalogRestClient.ElementSummaryDto("sh-1", "script", Map.of()),
            new CatalogRestClient.ElementSummaryDto("sh-2", "script", Map.of()));
    when(catalogRestClient.createElement(eq("c1"), any()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(created, List.of(), List.of()));
    when(descriptorLoader.load("container-x")).thenReturn(Optional.empty());

    ElementPlan ch1 = elem("c-one", "script");
    ElementPlan ch2 = elem("c-two", "script");
    ElementPlan root = elem("p-root", "container-x");
    root.setChildren(new ArrayList<>(List.of(ch1, ch2)));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    ChainSkeletonCreator.MaterializeContext ctx =
        new ChainSkeletonCreator.MaterializeContext(new LinkedHashMap<>());

    creator.createElements("c1", plan, ctx, null);

    assertEquals("sh-1", ch1.getElementId());
    assertEquals("parent-cat", ch1.getParentElementId());
    assertEquals("sh-2", ch2.getElementId());
    assertEquals("parent-cat", ch2.getParentElementId());
    assertTrue(ctx.skeletonFailures.isEmpty());
    verify(catalogRestClient, never()).getElement(any(), any());
  }

  @Test
  void preassignedShellIdDrainedFromPoolSecondSiblingGetsRemainingShell() {
    when(placementRules.validateCreatePlacement(eq("c1"), eq("container-x"), eq("")))
        .thenReturn(Optional.empty());
    List<CatalogRestClient.ElementSummaryDto> created =
        List.of(
            new CatalogRestClient.ElementSummaryDto("parent-cat", "container-x", Map.of()),
            new CatalogRestClient.ElementSummaryDto("pre-shell", "script", Map.of()),
            new CatalogRestClient.ElementSummaryDto("other-shell", "script", Map.of()));
    when(catalogRestClient.createElement(eq("c1"), any()))
        .thenReturn(new CatalogRestClient.ChainDiffDto(created, List.of(), List.of()));
    when(descriptorLoader.load("container-x")).thenReturn(Optional.empty());

    ElementPlan pre = elem("pre", "script");
    pre.setElementId("pre-shell");
    ElementPlan second = elem("need", "script");
    ElementPlan root = elem("p-root", "container-x");
    root.setChildren(new ArrayList<>(List.of(pre, second)));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    ChainSkeletonCreator.MaterializeContext ctx =
        new ChainSkeletonCreator.MaterializeContext(new LinkedHashMap<>());

    creator.createElements("c1", plan, ctx, null);

    assertEquals("pre-shell", pre.getElementId());
    assertEquals("other-shell", second.getElementId());
    assertEquals("parent-cat", second.getParentElementId());
    assertTrue(ctx.skeletonFailures.isEmpty());
  }

  @Test
  void createElementThrowsRecordsFailureAndSkipsChildRecurse() {
    when(placementRules.validateCreatePlacement(eq("c1"), eq("parent-t"), eq("")))
        .thenReturn(Optional.empty());
    RuntimeException boom = new RuntimeException("boom");
    when(catalogRestClient.createElement(eq("c1"), any())).thenThrow(boom);

    ElementPlan child = elem("kid", "script");
    ElementPlan root = elem("p-root", "parent-t");
    root.setChildren(new ArrayList<>(List.of(child)));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    ChainSkeletonCreator.MaterializeContext ctx =
        new ChainSkeletonCreator.MaterializeContext(new LinkedHashMap<>());

    creator.createElements("c1", plan, ctx, null);

    assertNull(root.getElementId());
    assertNull(child.getElementId());
    assertEquals(2, ctx.skeletonFailures.size());
    assertEquals("p-root", ctx.skeletonFailures.get(0).clientId);
    assertNotNull(ctx.skeletonFailures.get(0).reason);
    assertTrue(ctx.skeletonFailures.get(0).reason.contains("boom"));
    assertEquals(
        "cannot create children (missing elementId on parent after skeleton stage)",
        ctx.skeletonFailures.get(1).reason);
    verify(catalogRestClient, times(1)).createElement(eq("c1"), any());
  }

  @Test
  void liveReadbackContainerDescriptorLoadsChildrenFromGetElement() {
    when(placementRules.validateCreatePlacement(eq("c1"), eq("container-x"), eq("")))
        .thenReturn(Optional.empty());
    when(catalogRestClient.createElement(eq("c1"), any()))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto("parent-cat", "container-x", Map.of())),
                List.of(),
                List.of()));

    CatalogElementDescriptorModel desc = new CatalogElementDescriptorModel();
    desc.setContainer(true);
    desc.getAllowedChildren().put("script", "one-or-many");
    when(descriptorLoader.load("container-x")).thenReturn(Optional.of(desc));

    CatalogElementResponseDto liveChild = new CatalogElementResponseDto();
    liveChild.id = "shell-live";
    liveChild.type = "script";
    CatalogElementResponseDto liveParent = new CatalogElementResponseDto();
    liveParent.children = new ArrayList<>(List.of(liveChild));
    when(catalogRestClient.getElement(eq("c1"), eq("parent-cat"))).thenReturn(liveParent);

    ElementPlan ch = elem("only", "script");
    ElementPlan root = elem("p-root", "container-x");
    root.setChildren(new ArrayList<>(List.of(ch)));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    ChainSkeletonCreator.MaterializeContext ctx =
        new ChainSkeletonCreator.MaterializeContext(new LinkedHashMap<>());

    creator.createElements("c1", plan, ctx, null);

    assertEquals("shell-live", ch.getElementId());
    assertEquals("parent-cat", ch.getParentElementId());
    assertTrue(ctx.skeletonFailures.isEmpty());
    verify(catalogRestClient, times(1)).getElement(eq("c1"), eq("parent-cat"));
  }

  @Test
  void conversationIdPersistsPlanAfterEachLevel() {
    when(placementRules.validateCreatePlacement(any(), any(), any())).thenReturn(Optional.empty());
    when(catalogRestClient.createElement(eq("c1"), any()))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(
                    new CatalogRestClient.ElementSummaryDto("root-id", "http-trigger", Map.of())),
                List.of(),
                List.of()))
        .thenReturn(
            new CatalogRestClient.ChainDiffDto(
                List.of(new CatalogRestClient.ElementSummaryDto("child-id", "script", Map.of())),
                List.of(),
                List.of()));
    when(descriptorLoader.load("http-trigger")).thenReturn(Optional.empty());

    ElementPlan child = elem("child", "script");
    ElementPlan root = elem("root", "http-trigger");
    root.setChildren(new ArrayList<>(List.of(child)));

    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    ChainSkeletonCreator.MaterializeContext ctx =
        new ChainSkeletonCreator.MaterializeContext(new LinkedHashMap<>());

    creator.createElements("c1", plan, ctx, "conv-1");

    verify(activeChainPlanService, times(2)).updateActivePlan(eq("conv-1"), eq(plan));
  }

  private static ElementPlan elem(String clientId, String type) {
    ElementPlan e = new ElementPlan();
    e.setClientId(clientId);
    e.setType(type);
    return e;
  }
}
