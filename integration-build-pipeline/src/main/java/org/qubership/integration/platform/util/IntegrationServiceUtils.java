package org.qubership.integration.platform.util;

import org.qubership.integration.platform.chain.model.IntegrationService;
import org.qubership.integration.platform.chain.model.Protocol;
import org.qubership.integration.platform.chain.model.ServiceType;

import java.util.Collection;

public final class IntegrationServiceUtils {
    private IntegrationServiceUtils() {}

    public static boolean shouldCallControlPlane(IntegrationService service) {
        return service.getActiveEnvironment().isPresent()
            && ServiceType.EXTERNAL.equals(service.getType())
            && (
                Protocol.HTTP.equals(service.getProtocol())
                || Protocol.SOAP.equals(service.getProtocol())
                || Protocol.GRAPHQL.equals(service.getProtocol())
            );
    }

    public static Collection<IntegrationService> filterServicesRequiredGatewayRoutes(
        Collection<IntegrationService> services
    ) {
        return services
            .stream()
            .filter(IntegrationServiceUtils::shouldCallControlPlane)
            .toList();
    }
}
