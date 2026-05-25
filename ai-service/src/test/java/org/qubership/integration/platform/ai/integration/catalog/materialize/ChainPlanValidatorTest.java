package org.qubership.integration.platform.ai.integration.catalog.materialize;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.catalog.descriptor.CatalogElementPlacementRules;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChainPlanValidatorTest {

  private CatalogElementPlacementRules placementRules;
  private ChainPlanValidator validator;

  @BeforeEach
  void setUp() {
    placementRules = mock(CatalogElementPlacementRules.class);
    validator = new ChainPlanValidator(placementRules);
  }

  @Test
  void nullPlanReturnsError() {
    Optional<String> r = validator.validate("ch-1", null);

    assertTrue(r.isPresent());
    assertEquals("plan is null", r.get());
    verify(placementRules, never()).validateCreatePlacement(anyString(), anyString(), anyString());
  }

  @Test
  void nullElementsOk() {
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(null);

    assertTrue(validator.validate("ch-1", plan).isEmpty());
    verify(placementRules, never()).validateCreatePlacement(anyString(), anyString(), anyString());
  }

  @Test
  void emptyElementsOk() {
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>());

    assertTrue(validator.validate("ch-1", plan).isEmpty());
    verify(placementRules, never()).validateCreatePlacement(anyString(), anyString(), anyString());
  }

  @Test
  void duplicateClientIdAcrossLevelsReturnsDuplicateMessage() {
    ElementPlan child = elem("dup", "script", "p1");
    ElementPlan root = elem("dup", "http-trigger", null);
    root.setChildren(List.of(child));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    Optional<String> r = validator.validate("ch-1", plan);

    assertTrue(r.isPresent());
    assertEquals("duplicate clientId in plan: dup", r.get());
  }

  @Test
  void missingClientIdIncludesPath() {
    ElementPlan root = new ElementPlan();
    root.setClientId("   ");
    root.setType("http-trigger");
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    Optional<String> r = validator.validate("ch-1", plan);

    assertTrue(r.isPresent());
    assertEquals("elements[0].clientId is required", r.get());
  }

  @Test
  void missingTypeIncludesPathAndClientId() {
    ElementPlan root = new ElementPlan();
    root.setClientId("c1");
    root.setType(null);
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    Optional<String> r = validator.validate("ch-1", plan);

    assertTrue(r.isPresent());
    assertEquals("elements[0].type is required (clientId=c1)", r.get());
  }

  @Test
  void rootPlacementRejectedPropagatesMessage() {
    when(placementRules.validateCreatePlacement("ch-1", "bad-root", ""))
        .thenReturn(Optional.of("root type not allowed here"));

    ElementPlan root = elem("r1", "bad-root", null);
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    Optional<String> r = validator.validate("ch-1", plan);

    assertTrue(r.isPresent());
    assertEquals("root type not allowed here", r.get());
    verify(placementRules).validateCreatePlacement("ch-1", "bad-root", "");
  }

  @Test
  void childWithNullParentElementIdSkipsPlacementForChild() {
    when(placementRules.validateCreatePlacement("ch-1", "http-trigger", ""))
        .thenReturn(Optional.empty());

    ElementPlan child = elem("child-1", "script", null);
    child.setParentElementId(null);
    ElementPlan root = elem("root-1", "http-trigger", null);
    root.setChildren(new ArrayList<>(List.of(child)));
    ChainImplementationPlan plan = new ChainImplementationPlan();
    plan.setElements(new ArrayList<>(List.of(root)));

    assertTrue(validator.validate("ch-1", plan).isEmpty());
    verify(placementRules, times(1)).validateCreatePlacement("ch-1", "http-trigger", "");
    verify(placementRules, never()).validateCreatePlacement(eq("ch-1"), eq("script"), anyString());
  }

  private static ElementPlan elem(String clientId, String type, String parentElementId) {
    ElementPlan e = new ElementPlan();
    e.setClientId(clientId);
    e.setType(type);
    e.setParentElementId(parentElementId);
    return e;
  }
}
