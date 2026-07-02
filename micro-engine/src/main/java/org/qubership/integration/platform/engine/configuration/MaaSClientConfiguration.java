package org.qubership.integration.platform.engine.configuration;

import com.netcracker.cloud.maas.client.api.MaaSAPIClient;
import com.netcracker.cloud.maas.client.impl.MaaSAPIClientImpl;
import com.netcracker.cloud.quarkus.security.auth.M2MManager;
import io.quarkus.arc.properties.IfBuildProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;

@IfBuildProperty(name = "m2m.enabled", stringValue = "true")
public class MaaSClientConfiguration {
    @Produces
    @ApplicationScoped
    public MaaSAPIClient getMaaSAPIClient() {
        return new MaaSAPIClientImpl(() -> M2MManager.getInstance().getToken().getTokenValue(), true);
    }
}
