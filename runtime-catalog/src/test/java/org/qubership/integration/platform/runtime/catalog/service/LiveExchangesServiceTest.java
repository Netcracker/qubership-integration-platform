package org.qubership.integration.platform.runtime.catalog.service;

import jakarta.persistence.EntityNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.CatalogRuntimeException;
import org.qubership.integration.platform.runtime.catalog.rest.handler.exception.MicroserviceErrorResponseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class LiveExchangesServiceTest {

    private static final URI KILL_URI = URI.create("http://10.0.0.1:8080/v1/engine/live-exchanges/dep-1/ex-1");

    @Mock
    private RuntimeDeploymentService runtimeDeploymentService;
    @Mock
    private RestTemplate restTemplateMs;
    @Mock
    private ActionsLogService actionLogger;
    @Mock
    private DeploymentService deploymentService;

    private LiveExchangesService liveExchangesService;

    @BeforeEach
    void setUp() {
        when(runtimeDeploymentService.getEngineHosts())
                .thenReturn(Map.of("default", List.of("10.0.0.2", "10.0.0.1")));
        liveExchangesService = new LiveExchangesService(
                runtimeDeploymentService, restTemplateMs, actionLogger, deploymentService, null, null);
    }

    @Test
    void sendKillExchangeRequestLogsAnAcceptedKill() {
        liveExchangesService.sendKillExchangeRequest("10.0.0.1", "dep-1", "ex-1");

        verify(restTemplateMs).delete(KILL_URI);
        verify(actionLogger).logAction(any());
    }

    @Test
    void sendKillExchangeRequestRejectsAnAddressThatIsNotARegisteredEngine() {
        assertThatThrownBy(() -> liveExchangesService.sendKillExchangeRequest("10.0.0.99", "dep-1", "ex-1"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("No engine pod is registered at the given address");
        verifyNoInteractions(restTemplateMs, actionLogger);
    }

    @Test
    void sendKillExchangeRequestEncodesIdsAsSinglePathSegments() {
        liveExchangesService.sendKillExchangeRequest("10.0.0.1", "../dep", "ex/1");

        verify(restTemplateMs).delete(URI.create("http://10.0.0.1:8080/v1/engine/live-exchanges/..%2Fdep/ex%2F1"));
    }

    @Test
    void sendKillExchangeRequestAnswersNotFoundWhenTheEngineFindsNothing() {
        doThrow(new MicroserviceErrorResponseException(
                "No live exchange found for deployment id dep-1", HttpStatus.NOT_FOUND, HttpHeaders.EMPTY))
                .when(restTemplateMs).delete(KILL_URI);

        assertThatThrownBy(() -> liveExchangesService.sendKillExchangeRequest("10.0.0.1", "dep-1", "ex-1"))
                .isInstanceOf(EntityNotFoundException.class)
                .hasMessage("No live exchange found for deployment id dep-1");
        verifyNoInteractions(actionLogger);
    }

    @Test
    void sendKillExchangeRequestPassesOtherEngineErrorsThrough() {
        MicroserviceErrorResponseException error = new MicroserviceErrorResponseException(
                "boom", HttpStatus.INTERNAL_SERVER_ERROR, HttpHeaders.EMPTY);
        doThrow(error).when(restTemplateMs).delete(KILL_URI);

        assertThatThrownBy(() -> liveExchangesService.sendKillExchangeRequest("10.0.0.1", "dep-1", "ex-1"))
                .isSameAs(error);
        verifyNoInteractions(actionLogger);
    }

    @Test
    void sendKillExchangeRequestHidesTheEngineAddressWhenThePodIsUnreachable() {
        doThrow(new ResourceAccessException("I/O error on DELETE request for \"" + KILL_URI + "\": Read timed out"))
                .when(restTemplateMs).delete(KILL_URI);

        assertThatThrownBy(() -> liveExchangesService.sendKillExchangeRequest("10.0.0.1", "dep-1", "ex-1"))
                .isInstanceOf(CatalogRuntimeException.class)
                .hasMessage("Cannot reach the engine pod that runs the exchange");
        verifyNoInteractions(actionLogger);
    }
}
