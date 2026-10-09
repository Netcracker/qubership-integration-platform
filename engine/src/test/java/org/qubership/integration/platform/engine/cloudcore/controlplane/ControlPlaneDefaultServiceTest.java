package org.qubership.integration.platform.engine.cloudcore.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.qubership.integration.platform.engine.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.engine.model.deployment.update.RouteType;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class ControlPlaneDefaultServiceTest {

    private RestTemplate restTemplate;
    private ControlPlaneDefaultService service;

    @BeforeEach
    void setUp() {
        restTemplate = mock(RestTemplate.class);
        service = new ControlPlaneDefaultService(restTemplate, new ObjectMapper(), mock(BlueGreenStateService.class));
    }

    // Undeploy passes the chain's egress routes too, and Core Mesh removes trigger routes only.
    @Test
    void removeEngineRoutesSkipsTheControlPlaneWhenNoTriggerRouteIsGiven() {
        DeploymentRouteUpdate sender = DeploymentRouteUpdate.builder()
                .path("https://api.example.com")
                .gatewayPrefix("/http-sender/elem/hash")
                .type(RouteType.EXTERNAL_SENDER)
                .build();

        service.removeEngineRoutes("chain-1", List.of(sender), "engine-service");

        verifyNoInteractions(restTemplate);
    }

    @Test
    void removeEngineRoutesReadsTheControlPlaneRoutesForATriggerRoute() {
        when(restTemplate.getForEntity(anyString(), eq(String.class))).thenReturn(ResponseEntity.ok("[]"));
        DeploymentRouteUpdate trigger = DeploymentRouteUpdate.builder()
                .path("/chain-a")
                .type(RouteType.EXTERNAL_TRIGGER)
                .build();

        service.removeEngineRoutes("chain-1", List.of(trigger), "engine-service");

        verify(restTemplate).getForEntity(any(String.class), eq(String.class));
    }
}
