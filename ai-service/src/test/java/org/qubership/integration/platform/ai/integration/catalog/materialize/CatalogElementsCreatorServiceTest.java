package org.qubership.integration.platform.ai.integration.catalog.materialize;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.ai.chat.chainplan.ActiveChainPlanService;
import org.qubership.integration.platform.ai.chat.chainplan.ChainPlanBindingPreflightService;
import org.qubership.integration.platform.ai.chat.chainplan.PlanOpenItem;
import org.qubership.integration.platform.ai.chat.chainplan.PlanOpenItemKind;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan;
import org.qubership.integration.platform.ai.integration.catalog.model.ChainImplementationPlan.ElementPlan;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CatalogElementsCreatorServiceTest {

  private CatalogElementsCreatorService service;
  private ChainPlanLoader chainPlanLoader;
  private ChainPlanValidator chainPlanValidator;
  private ChainSkeletonCreator chainSkeletonCreator;
  private ChainPlanBindingPreflightService bindingPreflightService;

  @BeforeEach
  void setUp() {
    ObjectMapper objectMapper = new ObjectMapper();
    chainPlanLoader = mock(ChainPlanLoader.class);
    chainPlanValidator = mock(ChainPlanValidator.class);
    chainSkeletonCreator = mock(ChainSkeletonCreator.class);
    bindingPreflightService = mock(ChainPlanBindingPreflightService.class);
    when(bindingPreflightService.enrichOperationBindings(any()))
        .thenReturn(new ChainPlanBindingPreflightService.PreflightResult(List.of()));
    when(chainPlanValidator.validate(anyString(), any())).thenReturn(Optional.empty());
    when(chainPlanLoader.load(anyString(), any()))
        .thenAnswer(
            inv -> {
              ChainImplementationPlan plan = new ChainImplementationPlan();
              ElementPlan node = new ElementPlan();
              node.setClientId("svc");
              node.setType("service-call");
              plan.setElements(List.of(node));
              return new ChainPlanLoadResult.Loaded(plan, "conv-1", "active");
            });
    service =
        new CatalogElementsCreatorService(
            objectMapper,
            chainPlanLoader,
            chainPlanValidator,
            chainSkeletonCreator,
            mock(ChainConnectionsApplier.class),
            mock(ChainPropertiesApplier.class),
            mock(ChainPlanReconciler.class),
            mock(ActiveChainPlanService.class),
            mock(ChainPlanPropertyKeysValidator.class),
            bindingPreflightService);
  }

  @Test
  void createFromBatchJsonFailsBeforeSkeletonWhenBindingUnresolved() {
    when(bindingPreflightService.enrichOperationBindings(any()))
        .thenReturn(
            new ChainPlanBindingPreflightService.PreflightResult(
                List.of(
                    new PlanOpenItem(
                        "service-binding:svc",
                        PlanOpenItemKind.SERVICE_BINDING_UNRESOLVED,
                        "svc",
                        null,
                        "service-call",
                        "integrationOperationId not found",
                        List.of(),
                        false))));

    String result = service.createFromBatchJson("chain-1", "");

    assertTrue(result.contains("\"ok\":false"));
    assertTrue(result.contains("Unresolved operation binding"));
    assertTrue(result.contains("IMPORT_SPECIFICATION"));
    verify(chainSkeletonCreator, never()).createElements(any(), any(), any(), any());
  }
}
