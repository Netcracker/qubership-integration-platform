package org.qubership.integration.platform.runtime.catalog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.ConfigParameter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class DiscoveryServiceTest {

    @Mock
    private KubeOperator operator;
    @Mock
    private SystemService systemService;
    @Mock
    private ConfigParameterService configParameterService;
    @Mock
    private ActionsLogService actionLogger;

    @Test
    void progressReachesCompleteOnlyAfterTheResultIsStored() {
        DiscoveryService service = new DiscoveryService(operator, systemService, null, null, null, null, null,
                new ObjectMapper(), null, null, configParameterService, actionLogger);

        service.runDiscovery();

        ArgumentCaptor<ConfigParameter> writes = ArgumentCaptor.forClass(ConfigParameter.class);
        verify(configParameterService, timeout(5_000).times(3)).update(writes.capture());
        List<String> names = writes.getAllValues().stream().map(ConfigParameter::getName).toList();
        assertThat(names).containsExactly("discoveryProgress", "discoveryResult", "discoveryProgress");
        assertThat(writes.getAllValues().get(2).getString()).isEqualTo("100");
    }
}
