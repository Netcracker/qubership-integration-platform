package org.qubership.integration.platform.engine.camel.listeners.actions.routes.removed;

import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.listeners.helpers.SdsSchedulerJobsRegistrationHelper;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.service.SdsService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RemoveSdsSchedulerJobsActionTest {

    @Mock
    private SdsService sdsService;

    @Mock
    private SdsSchedulerJobsRegistrationHelper sdsSchedulerJobsRegistrationHelper;

    @Mock
    private CamelEvent.RouteRemovedEvent event;

    @Mock
    private Route route;

    @Mock
    private DeploymentInfo deploymentInfo;

    private RemoveSdsSchedulerJobsAction action;

    @BeforeEach
    void setUp() {
        action = new RemoveSdsSchedulerJobsAction();
        action.sdsService = sdsService;
        action.sdsSchedulerJobsRegistrationHelper = sdsSchedulerJobsRegistrationHelper;

        when(event.getRoute()).thenReturn(route);
    }

    @Test
    void shouldRemoveSdsSchedulerJobsAndMarkDeploymentUnregistered() throws Exception {
        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            action.process(event);

            InOrder inOrder = inOrder(sdsService, sdsSchedulerJobsRegistrationHelper);
            inOrder.verify(sdsService).removeSchedulerJobs(deploymentInfo);
            inOrder.verify(sdsSchedulerJobsRegistrationHelper).markUnregistered(deploymentInfo);
        }
    }

    @Test
    void shouldPropagateExceptionWhenSdsSchedulerJobsRemovalFails() {
        RuntimeException exception = new RuntimeException("Failed to remove SDS scheduler jobs");

        doThrow(exception).when(sdsService).removeSchedulerJobs(deploymentInfo);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);

            RuntimeException result = assertThrows(RuntimeException.class, () -> action.process(event));

            assertSame(exception, result);
            verify(sdsSchedulerJobsRegistrationHelper, never()).markUnregistered(deploymentInfo);
        }
    }
}
