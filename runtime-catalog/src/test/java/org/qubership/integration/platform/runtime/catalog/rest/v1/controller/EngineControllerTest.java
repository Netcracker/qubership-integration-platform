package org.qubership.integration.platform.runtime.catalog.rest.v1.controller;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.configuration.DomainProperties;
import org.qubership.integration.platform.runtime.catalog.cr.MicroDomainService;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DomainTypeDisabledException;
import org.qubership.integration.platform.runtime.catalog.model.domains.DomainType;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentMapper;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.EngineMapper;
import org.qubership.integration.platform.runtime.catalog.service.DeploymentService;
import org.qubership.integration.platform.runtime.catalog.service.EngineService;
import org.qubership.integration.platform.runtime.catalog.service.RuntimeDeploymentService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EngineControllerTest {

    @Mock
    private EngineService engineService;
    @Mock
    private EngineMapper engineMapper;
    @Mock
    private DeploymentMapper deploymentMapper;
    @Mock
    private DeploymentService deploymentService;
    @Mock
    private RuntimeDeploymentService runtimeDeploymentService;
    @Mock
    private DomainProperties domainProperties;
    @Mock
    private MicroDomainService microDomainService;

    private EngineController controller;

    @BeforeEach
    void setUp() {
        controller = new EngineController(
                engineService,
                engineMapper,
                deploymentMapper,
                deploymentService,
                runtimeDeploymentService,
                domainProperties,
                microDomainService);
    }

    private void microDomainEnabled(boolean enabled) {
        when(microDomainService.verifyMicroDomainEnabled(any())).thenAnswer(invocation -> {
            Supplier<?> supplier = invocation.getArgument(0);
            if (enabled) {
                return supplier.get();
            }
            throw new DomainTypeDisabledException(DomainType.MICRO);
        });
    }

    @Test
    void deleteDomainDeletesTheNamedDomainWhenMicroDomainEnabled() {
        microDomainEnabled(true);

        ResponseEntity<Void> response = controller.deleteDomain("orders");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(microDomainService).delete("orders");
    }

    @Test
    void deleteDomainIsRejectedWhenMicroDomainDisabled() {
        microDomainEnabled(false);

        assertThatThrownBy(() -> controller.deleteDomain("orders"))
                .isInstanceOf(DomainTypeDisabledException.class);
        verify(microDomainService, never()).delete(any());
    }
}
