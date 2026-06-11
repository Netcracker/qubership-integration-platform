package org.qubership.integration.platform.engine.camel.listeners.actions.routes.added;

import org.apache.camel.Route;
import org.apache.camel.spi.CamelEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.camel.listeners.helpers.SdsSchedulerJobsRegistrationHelper;
import org.qubership.integration.platform.engine.metadata.DeploymentInfo;
import org.qubership.integration.platform.engine.metadata.ElementInfo;
import org.qubership.integration.platform.engine.metadata.util.MetadataUtil;
import org.qubership.integration.platform.engine.model.ChainElementType;
import org.qubership.integration.platform.engine.service.SdsService;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class RegisterSdsSchedulerJobsActionTest {

    private static final String SDS_TRIGGER_TYPE = "sds-trigger";
    private static final String NON_SDS_TRIGGER_TYPE = "http-trigger";

    @Mock
    private SdsService sdsService;

    @Mock
    private SdsSchedulerJobsRegistrationHelper sdsSchedulerJobsRegistrationHelper;

    @Mock
    private CamelEvent.RouteAddedEvent event;

    @Mock
    private Route route;

    @Mock
    private DeploymentInfo deploymentInfo;

    @Mock
    private ElementInfo elementInfo;

    @Mock
    private ElementInfo anotherElementInfo;

    private RegisterSdsSchedulerJobsAction action;

    @BeforeEach
    void setUp() {
        action = new RegisterSdsSchedulerJobsAction();
        action.sdsService = sdsService;
        action.sdsSchedulerJobsRegistrationHelper = sdsSchedulerJobsRegistrationHelper;

        when(event.getRoute()).thenReturn(route);
    }

    @Test
    void shouldRegisterSchedulerJobAndMarkElementRegisteredWhenElementIsSdsTrigger() throws Exception {
        when(elementInfo.getType()).thenReturn(SDS_TRIGGER_TYPE);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class);
             MockedStatic<ChainElementType> chainElementType = mockStatic(ChainElementType.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route)).thenReturn(Stream.of(elementInfo));

            chainElementType.when(() -> ChainElementType.fromString(SDS_TRIGGER_TYPE)).thenReturn(null);
            chainElementType.when(() -> ChainElementType.isSdsTriggerElement(null)).thenReturn(true);

            action.process(event);

            verify(sdsService).registerSchedulerJobs(deploymentInfo, elementInfo);
            verify(sdsSchedulerJobsRegistrationHelper).markRegistered(deploymentInfo, elementInfo);
        }
    }

    @Test
    void shouldSkipElementWhenElementIsNotSdsTrigger() throws Exception {
        when(elementInfo.getType()).thenReturn(NON_SDS_TRIGGER_TYPE);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class);
             MockedStatic<ChainElementType> chainElementType = mockStatic(ChainElementType.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route)).thenReturn(Stream.of(elementInfo));

            chainElementType.when(() -> ChainElementType.fromString(NON_SDS_TRIGGER_TYPE)).thenReturn(null);
            chainElementType.when(() -> ChainElementType.isSdsTriggerElement(null)).thenReturn(false);

            action.process(event);

            verifyNoInteractions(sdsService, sdsSchedulerJobsRegistrationHelper);
        }
    }

    @Test
    void shouldRegisterSchedulerJobsForAllSdsTriggerElements() throws Exception {
        when(elementInfo.getType()).thenReturn(SDS_TRIGGER_TYPE);
        when(anotherElementInfo.getType()).thenReturn(SDS_TRIGGER_TYPE);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class);
             MockedStatic<ChainElementType> chainElementType = mockStatic(ChainElementType.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route))
                .thenReturn(Stream.of(elementInfo, anotherElementInfo));

            chainElementType.when(() -> ChainElementType.fromString(SDS_TRIGGER_TYPE)).thenReturn(null);
            chainElementType.when(() -> ChainElementType.isSdsTriggerElement(null)).thenReturn(true);

            action.process(event);

            verify(sdsService).registerSchedulerJobs(deploymentInfo, elementInfo);
            verify(sdsService).registerSchedulerJobs(deploymentInfo, anotherElementInfo);
            verify(sdsSchedulerJobsRegistrationHelper).markRegistered(deploymentInfo, elementInfo);
            verify(sdsSchedulerJobsRegistrationHelper).markRegistered(deploymentInfo, anotherElementInfo);
        }
    }

    @Test
    void shouldRegisterOnlySdsTriggerElementsWhenRouteContainsMixedElements() throws Exception {
        when(elementInfo.getType()).thenReturn(SDS_TRIGGER_TYPE);
        when(anotherElementInfo.getType()).thenReturn(NON_SDS_TRIGGER_TYPE);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class);
             MockedStatic<ChainElementType> chainElementType = mockStatic(ChainElementType.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route))
                .thenReturn(Stream.of(elementInfo, anotherElementInfo));

            chainElementType.when(() -> ChainElementType.fromString(SDS_TRIGGER_TYPE)).thenReturn(null);
            chainElementType.when(() -> ChainElementType.fromString(NON_SDS_TRIGGER_TYPE)).thenReturn(null);
            chainElementType.when(() -> ChainElementType.isSdsTriggerElement(null))
                .thenReturn(true, false);

            action.process(event);

            verify(sdsService).registerSchedulerJobs(deploymentInfo, elementInfo);
            verify(sdsService, never()).registerSchedulerJobs(deploymentInfo, anotherElementInfo);
            verify(sdsSchedulerJobsRegistrationHelper).markRegistered(deploymentInfo, elementInfo);
            verify(sdsSchedulerJobsRegistrationHelper, never()).markRegistered(deploymentInfo, anotherElementInfo);
        }
    }

    @Test
    void shouldNotRegisterAnythingWhenRouteDoesNotContainElements() throws Exception {
        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route)).thenReturn(Stream.empty());

            action.process(event);

            verifyNoInteractions(sdsService, sdsSchedulerJobsRegistrationHelper);
        }
    }

    @Test
    void shouldPropagateExceptionWhenSchedulerJobRegistrationFails() {
        RuntimeException exception = new RuntimeException("Failed to register SDS scheduler job");

        when(elementInfo.getType()).thenReturn(SDS_TRIGGER_TYPE);
        org.mockito.Mockito.doThrow(exception)
            .when(sdsService)
            .registerSchedulerJobs(deploymentInfo, elementInfo);

        try (MockedStatic<MetadataUtil> metadataUtil = mockStatic(MetadataUtil.class);
             MockedStatic<ChainElementType> chainElementType = mockStatic(ChainElementType.class)) {
            metadataUtil.when(() -> MetadataUtil.getBean(route, DeploymentInfo.class)).thenReturn(deploymentInfo);
            metadataUtil.when(() -> MetadataUtil.getElementsInfo(route)).thenReturn(Stream.of(elementInfo));

            chainElementType.when(() -> ChainElementType.fromString(SDS_TRIGGER_TYPE)).thenReturn(null);
            chainElementType.when(() -> ChainElementType.isSdsTriggerElement(null)).thenReturn(true);

            RuntimeException result = assertThrows(RuntimeException.class, () -> action.process(event));

            assertSame(exception, result);
            verify(sdsSchedulerJobsRegistrationHelper, never()).markRegistered(deploymentInfo, elementInfo);
        }
    }
}
