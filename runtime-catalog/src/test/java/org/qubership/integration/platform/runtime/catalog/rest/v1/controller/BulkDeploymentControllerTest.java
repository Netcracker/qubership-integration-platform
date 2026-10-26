package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.cr.BulkDeploymentService;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentRequest;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentResponse;
import org.qubership.integration.platform.runtime.catalog.rest.v1.dto.deployment.bulk.BulkDeploymentStatus;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;

@ExtendWith(MockitoExtension.class)
class BulkDeploymentControllerTest {

    @Mock
    private BulkDeploymentService bulkDeploymentService;

    private BulkDeploymentController controller;

    @BeforeEach
    void setUp() {
        controller = new BulkDeploymentController(bulkDeploymentService);
    }

    @Test
    void bulkCreateReturnsEveryResponseTheBulkDeploymentReports() {
        BulkDeploymentRequest request = BulkDeploymentRequest.builder()
                .domains(List.of("orders"))
                .chainIds(List.of("chain-1", "chain-2"))
                .build();
        BulkDeploymentResponse created = BulkDeploymentResponse.builder()
                .chainId("chain-1")
                .status(BulkDeploymentStatus.CREATED)
                .build();
        BulkDeploymentResponse ignored = BulkDeploymentResponse.builder()
                .chainId("chain-2")
                .status(BulkDeploymentStatus.IGNORED)
                .build();
        doAnswer(invocation -> {
            Consumer<BulkDeploymentResponse> resultConsumer = invocation.getArgument(1);
            resultConsumer.accept(created);
            resultConsumer.accept(ignored);
            return null;
        }).when(bulkDeploymentService).deployChains(eq(request), any());

        ResponseEntity<List<BulkDeploymentResponse>> response = controller.bulkCreate(request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).containsExactly(created, ignored);
    }
}
