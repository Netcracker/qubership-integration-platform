package org.qubership.integration.platform.engine.controlplane;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.qubership.integration.platform.engine.configuration.ApplicationConfiguration;
import org.qubership.integration.platform.engine.controlplane.impl.ControlPlaneServiceImpl;
import org.qubership.integration.platform.engine.controlplane.impl.NoopControlPlaneService;
import org.qubership.integration.platform.engine.controlplane.rest.ControlPlaneRestService;
import org.qubership.integration.platform.engine.service.BlueGreenStateService;

@ApplicationScoped
public class ControlPlaneServiceProducer {
    @Inject
    ControlPlaneServiceProperties properties;

    @RestClient
    @Inject
    ControlPlaneRestService controlPlaneRestService;

    @Inject
    ApplicationConfiguration applicationConfiguration;

    @Inject
    BlueGreenStateService blueGreenStateService;

    @ConfigProperty(name = "qip.camel.routes-prefix")
    String camelRoutesPrefix;

    @Produces
    public ControlPlaneService getControlPlaneService() {
        return properties.routes().registration().enabled()
                ? new ControlPlaneServiceImpl(
                        controlPlaneRestService,
                        applicationConfiguration,
                        blueGreenStateService,
                        properties,
                        camelRoutesPrefix
                )
                : new NoopControlPlaneService();
    }
}
