package org.qubership.integration.platform.camelk.services;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.camelk.model.routes.Route;
import org.qubership.integration.platform.camelk.model.routes.RouteType;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.*;
import org.qubership.integration.platform.io.util.SimpleHttpUriUtils;
import org.qubership.integration.platform.library.constants.CamelOptions;
import org.qubership.integration.platform.util.ElementUtils;
import org.qubership.integration.platform.util.HashUtils;
import org.qubership.integration.platform.util.IntegrationServiceUtils;
import org.qubership.integration.platform.util.TriggerUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.library.constants.CamelNames.*;
import static org.qubership.integration.platform.library.constants.CamelOptions.CONNECT_TIMEOUT;
import static org.qubership.integration.platform.util.TriggerUtils.getHttpConnectionTimeout;

@Slf4j
@Service
public class RoutesGetterService {
    @Value("${qip.control-plane.chain-routes-registration.egress-gateway:true}")
    private boolean registerOnEgress;

    @Value("${qip.control-plane.chain-routes-registration.ingress-gateways:true}")
    private boolean registerOnIncomingGateways;

    public List<Route> getRoutes(
        Snapshot snapshot,
        IntegrationServiceCatalog integrationServiceCatalog
    ) {
        try {
            List<Route> allRoutes = new ArrayList<>();

            if (registerOnIncomingGateways) {
                // external and internal triggers
                List<Route> triggers = buildTriggersRoutes(snapshot);
                allRoutes.addAll(triggers);
            }
            if (registerOnEgress) {
                // external senders
                List<Route> senders = buildHttpSendersRoutes(snapshot);
                allRoutes.addAll(senders);
                // external services
                List<Route> serviceRoutes = buildServicesRoutes(snapshot, integrationServiceCatalog);
                allRoutes.addAll(serviceRoutes);
            }

            log.debug("Routes for registration in control plane: {}", allRoutes);
            return allRoutes;
        } catch (Exception e) {
            log.error("Failed to build egress routes for deployment", e);
            throw new RuntimeException("Failed to build egress routes for deployment", e);
        }
    }

    private List<Route> buildHttpSendersRoutes(Snapshot snapshot) {
        return snapshot.getElements().stream()
                .filter(element -> List.of(HTTP_SENDER_COMPONENT, GRAPHQL_SENDER_COMPONENT)
                        .contains(element.getType()))
                .filter(sender -> {
                    Object isExternalCall = sender.getProperties().get(CamelOptions.IS_EXTERNAL_CALL);
                    return isExternalCall == null || (boolean) isExternalCall;
                })
                .map(sender -> {
                    try {
                        String targetURL = SimpleHttpUriUtils.extractProtocolAndDomainWithPort(ElementUtils.getPropertyAsString(sender, CamelOptions.URI));

                        String gatewayPrefix = String.format("/%s/%s/%s", sender.getType(), sender.getOriginalId(), getEncodedURL(getHttpConnectionTimeout(sender), targetURL));

                        Route.RouteBuilder builder = Route.builder()
                                .id(UUID.randomUUID().toString())
                                .path(targetURL)
                                .variableName(ElementUtils.buildRouteVariableName(sender))
                                .gatewayPrefix(gatewayPrefix)
                                .type(RouteType.EXTERNAL_SENDER);

                        if (sender.getType().equalsIgnoreCase(HTTP_SENDER_COMPONENT)) {
                            builder.connectTimeout(getHttpConnectionTimeout(sender));
                        }

                        return builder.build();
                    } catch (MalformedURLException e) {
                        throw new RuntimeException("Invalid URI in HTTP sender element", e);
                    }
                })
                .toList();
    }

    private List<Route> buildTriggersRoutes(Snapshot snapshot) {
        return snapshot.getElements().stream()
                .filter(element -> HTTP_TRIGGER_COMPONENT.equals(element.getType()))
                .map(TriggerUtils::getHttpTriggerRoute)
                .map(route -> Route.builder()
                        .id(UUID.randomUUID().toString())
                        .path("/" + route.getPath())
                        .type(RouteType.convertTriggerType(route.isExternal(), route.isPrivate()))
                        .connectTimeout(route.getConnectionTimeout())
                        .build())
                .collect(Collectors.toList());
    }

    private List<Route> buildServicesRoutes(
        Snapshot snapshot,
        IntegrationServiceCatalog integrationServiceCatalog
    ) {
        List<Element> serviceCallElements = snapshot.getElements().stream().filter(
            element -> SERVICE_CALL_COMPONENT.equals(element.getType())
        ).toList();
        Map<String, List<Element>> systemsIds = serviceCallElements
                .stream()
                .collect(Collectors.groupingBy(
                        element -> (String) element.getProperties().get(CamelOptions.SYSTEM_ID),
                        Collectors.mapping(Function.identity(), Collectors.toList())
                ));

        Collection<IntegrationService> services = IntegrationServiceUtils.filterServicesRequiredGatewayRoutes(
            integrationServiceCatalog.findAllByIds(systemsIds.keySet()));
        List<Route> routes = new ArrayList<>();
        for (IntegrationService service : services) {
            String path = getActiveEnvAddress(service);
            Long connectionTimeout = getConnectionTimeout(service);

            RouteType routeType = getRouteTypeForSystemType(service.getType());

            List<Element> elements = systemsIds.get(service.getId());
            for (Element element : elements) {
                String gatewayPrefix = String.format("/system/%s", element.getOriginalId());

                routes.add(Route.builder()
                        .id(UUID.randomUUID().toString())
                        .type(routeType)
                        .path(path)
                        .gatewayPrefix(gatewayPrefix)
                        .variableName(ElementUtils.buildRouteVariableName(element))
                        .connectTimeout(connectionTimeout)
                        .build());
            }
        }

        return routes;
    }

    private String getActiveEnvAddress(IntegrationService integrationService) {
        return integrationService.getActiveEnvironment()
            .map(ServiceEnvironment::getAddress)
            .filter(StringUtils::isNotEmpty)
            .orElseThrow(() -> new RuntimeException("Address is not set"));
    }

    private Long getConnectionTimeout(IntegrationService integrationService) {
        return integrationService.getActiveEnvironment()
            .map(ServiceEnvironment::getProperties)
            .map(properties -> properties.get(CONNECT_TIMEOUT))
            .map(Object::toString)
            .map(Long::valueOf)
            .orElse(120000L);
    }

    private RouteType getRouteTypeForSystemType(ServiceType serviceType) {
        return isNull(serviceType) ? null : switch (serviceType) {
            case EXTERNAL -> RouteType.EXTERNAL_SERVICE;
            case INTERNAL -> RouteType.INTERNAL_SERVICE;
            case IMPLEMENTED -> RouteType.IMPLEMENTED_SERVICE;
        };
    }

    private String getEncodedURL(final Long connectTimeout, final String targetURL) {
        String senderURL = targetURL;
        if (!Objects.isNull(connectTimeout) && connectTimeout > -1L) {
            senderURL = senderURL + connectTimeout;
        }
        return HashUtils.sha1hex(senderURL);
    }
}
