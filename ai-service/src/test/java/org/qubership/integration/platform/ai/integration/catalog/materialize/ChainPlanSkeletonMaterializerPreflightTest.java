package org.qubership.integration.platform.ai.integration.catalog.materialize;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.container;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.containerRequiringInner;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.deprecatedContainer;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.graph;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.leaf;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.leafRestrictedTo;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.node;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.stubPermissive;
import static org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorTestSupport.trigger;

import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.ai.integration.catalog.client.CatalogRestClient;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogChildQuantity;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorException;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.CatalogElementDescriptorLoader;
import org.qubership.integration.platform.ai.integration.catalog.descriptor.DesiredGraphDescriptorPreflightException;
import org.qubership.integration.platform.ai.plan.model.ChainPlanGraph;

@ExtendWith(MockitoExtension.class)
class ChainPlanSkeletonMaterializerPreflightTest {

  private static final String CHAIN_ID = "chain-1";

  @Mock private CatalogRestClient catalogRestClient;
  @Mock private CatalogElementDescriptorLoader descriptorLoader;

  private ChainPlanSkeletonMaterializer materializer;

  @BeforeEach
  void setUp() {
    stubPermissive(descriptorLoader);
    materializer = new ChainPlanSkeletonMaterializer(catalogRestClient, descriptorLoader);
  }

  @Test
  void rejectsChildUnderNonContainer() {
    when(descriptorLoader.load("script")).thenReturn(leaf("script"));
    ChainPlanGraph desired =
        graph(node("parent-script", "script", null), node("child-script", "script", "parent-script"));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "child-script", "parent-script", "not a container");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsChildTypeOutsideAllowedList() {
    when(descriptorLoader.load("box"))
        .thenReturn(container("box", Map.of("role", CatalogChildQuantity.ANY)));
    when(descriptorLoader.load("script")).thenReturn(leaf("script"));
    ChainPlanGraph desired =
        graph(node("parent-box", "box", null), node("child-script", "script", "parent-box"));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "child-script", "script", "not allowed");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsParentRestrictionMismatch() {
    when(descriptorLoader.load("box")).thenReturn(container("box", Map.of()));
    when(descriptorLoader.load("role")).thenReturn(leafRestrictedTo("role", "try-2"));
    ChainPlanGraph desired =
        graph(node("parent-box", "box", null), node("child-role", "role", "parent-box"));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "child-role", "box", "not permitted");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsCardinalityBelowMinimum() {
    when(descriptorLoader.load("fan"))
        .thenReturn(container("fan", Map.of("branch", CatalogChildQuantity.TWO_OR_MANY)));
    when(descriptorLoader.load("branch")).thenReturn(leaf("branch"));
    ChainPlanGraph desired =
        graph(node("fan-1", "fan", null), node("branch-1", "branch", "fan-1"));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "fan-1", "branch", "minimum");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsCardinalityAboveMaximum() {
    when(descriptorLoader.load("box"))
        .thenReturn(container("box", Map.of("cap", CatalogChildQuantity.ONE)));
    when(descriptorLoader.load("cap")).thenReturn(leaf("cap"));
    ChainPlanGraph desired =
        graph(
            node("box-1", "box", null),
            node("cap-1", "cap", "box-1"),
            node("cap-2", "cap", "box-1"));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "box-1", "cap", "maximum");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsMissingMandatoryRole() {
    when(descriptorLoader.load("box"))
        .thenReturn(
            container(
                "box",
                Map.of(
                    "role", CatalogChildQuantity.ONE,
                    "cap", CatalogChildQuantity.ANY)));
    when(descriptorLoader.load("cap")).thenReturn(leaf("cap"));
    ChainPlanGraph desired = graph(node("box-1", "box", null), node("cap-1", "cap", "box-1"));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "box-1", "role", "missing mandatory");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsTriggerWithContainmentParent() {
    when(descriptorLoader.load("box")).thenReturn(container("box", Map.of()));
    // Live http-trigger omits allowedInContainers (DTO default true); still a nested trigger.
    when(descriptorLoader.load("http-trigger")).thenReturn(trigger("http-trigger"));
    ChainPlanGraph desired =
        graph(node("wrapper", "box", null), node("trigger", "http-trigger", "wrapper"));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "trigger", "wrapper", "chain root");
    verify(catalogRestClient, never()).createElement(any(), any());
    assertNoCatalogMutation();
  }

  @Test
  void rejectsEmptyMandatoryInnerContentContainer() {
    when(descriptorLoader.load("shell")).thenReturn(containerRequiringInner("shell"));
    ChainPlanGraph desired = graph(node("shell-1", "shell", null));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "shell-1", "shell", "inner content");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsNewlyIntroducedDeprecatedElement() {
    when(descriptorLoader.load("choice")).thenReturn(deprecatedContainer("choice"));
    ChainPlanGraph desired = graph(node("choice-1", "choice", null));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "choice-1", "choice", "deprecated");
    assertNoCatalogMutation();
  }

  @Test
  void rejectsUnknownDescriptor() {
    when(descriptorLoader.load("unknown-type"))
        .thenThrow(new CatalogElementDescriptorException("unknown-type", "not found."));
    ChainPlanGraph desired = graph(node("n1", "unknown-type", null));

    DesiredGraphDescriptorPreflightException thrown = reject(desired);

    assertMessage(thrown, "unknown-type", "not found");
    assertNoCatalogMutation();
  }

  private DesiredGraphDescriptorPreflightException reject(ChainPlanGraph desired) {
    return assertThrows(
        DesiredGraphDescriptorPreflightException.class,
        () -> materializer.materializeElements(desired, CHAIN_ID));
  }

  private static void assertMessage(
      DesiredGraphDescriptorPreflightException thrown, String... fragments) {
    String error = thrown.getMessage();
    for (String fragment : fragments) {
      assertTrue(error.contains(fragment), () -> "expected '" + fragment + "' in: " + error);
    }
  }

  private void assertNoCatalogMutation() {
    verify(catalogRestClient, never()).createElement(any(), any());
    verify(catalogRestClient, never()).updateElement(any(), any(), any());
    verify(catalogRestClient, never()).transferElements(any(), any());
    verify(catalogRestClient, never()).deleteElements(any(), any());
    verify(catalogRestClient, never()).createConnection(any(), any());
    verify(catalogRestClient, never()).deleteDependencies(any(), any());
    verify(catalogRestClient, never()).deleteChain(any());
  }
}
