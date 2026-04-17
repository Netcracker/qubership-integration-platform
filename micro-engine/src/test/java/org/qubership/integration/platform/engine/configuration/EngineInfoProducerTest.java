package org.qubership.integration.platform.engine.configuration;

import org.junit.jupiter.api.DisplayNameGeneration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.qubership.integration.platform.engine.model.deployment.engine.EngineInfo;
import org.qubership.integration.platform.engine.testutils.DisplayNameUtils;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayNameGeneration(DisplayNameUtils.ReplaceCamelCase.class)
class EngineInfoProducerTest {

    private final EngineInfoProducer engineInfoProducer = new EngineInfoProducer();

    @Test
    void shouldReturnDefaultDomainWhenMicroserviceNameMatchesConfiguredDefault() {
        ApplicationConfiguration applicationConfiguration = mock(ApplicationConfiguration.class);
        InetAddress inetAddress = mock(InetAddress.class);

        when(applicationConfiguration.getDefaultEngineMicroserviceName()).thenReturn("qip-engine");
        when(applicationConfiguration.getMicroserviceName()).thenReturn("qip-engine");
        when(applicationConfiguration.getEngineDefaultDomain()).thenReturn("default");
        when(applicationConfiguration.getDeploymentName()).thenReturn("qip-engine-v1");
        when(inetAddress.getHostAddress()).thenReturn("127.0.0.1");

        try (MockedStatic<InetAddress> inetAddressMockedStatic = mockStatic(InetAddress.class)) {
            inetAddressMockedStatic.when(InetAddress::getLocalHost).thenReturn(inetAddress);

            EngineInfo engineInfo = engineInfoProducer.getEngineInfo(applicationConfiguration);

            assertEquals("default", engineInfo.getDomain());
            assertEquals("qip-engine-v1", engineInfo.getEngineDeploymentName());
            assertEquals("127.0.0.1", engineInfo.getHost());
        }
    }

    @Test
    void shouldReturnMicroserviceNameAsDomainWhenMicroserviceNameDoesNotMatchConfiguredDefault() {
        ApplicationConfiguration applicationConfiguration = mock(ApplicationConfiguration.class);
        InetAddress inetAddress = mock(InetAddress.class);

        when(applicationConfiguration.getDefaultEngineMicroserviceName()).thenReturn("qip-engine");
        when(applicationConfiguration.getMicroserviceName()).thenReturn("custom-engine");
        when(applicationConfiguration.getDeploymentName()).thenReturn("custom-engine-v1");
        when(inetAddress.getHostAddress()).thenReturn("10.10.10.10");

        try (MockedStatic<InetAddress> inetAddressMockedStatic = mockStatic(InetAddress.class)) {
            inetAddressMockedStatic.when(InetAddress::getLocalHost).thenReturn(inetAddress);

            EngineInfo engineInfo = engineInfoProducer.getEngineInfo(applicationConfiguration);

            assertEquals("custom-engine", engineInfo.getDomain());
            assertEquals("custom-engine-v1", engineInfo.getEngineDeploymentName());
            assertEquals("10.10.10.10", engineInfo.getHost());
        }
    }

    @Test
    void shouldReturnEmptyHostWhenLocalHostResolutionFails() throws UnknownHostException {
        ApplicationConfiguration applicationConfiguration = mock(ApplicationConfiguration.class);

        when(applicationConfiguration.getDefaultEngineMicroserviceName()).thenReturn("qip-engine");
        when(applicationConfiguration.getMicroserviceName()).thenReturn("qip-engine");
        when(applicationConfiguration.getEngineDefaultDomain()).thenReturn("default");
        when(applicationConfiguration.getDeploymentName()).thenReturn("qip-engine-v1");

        try (MockedStatic<InetAddress> inetAddressMockedStatic = mockStatic(InetAddress.class)) {
            inetAddressMockedStatic.when(InetAddress::getLocalHost)
                    .thenThrow(new UnknownHostException("test"));

            EngineInfo engineInfo = engineInfoProducer.getEngineInfo(applicationConfiguration);

            assertEquals("default", engineInfo.getDomain());
            assertEquals("qip-engine-v1", engineInfo.getEngineDeploymentName());
            assertEquals("", engineInfo.getHost());
        }
    }
}
