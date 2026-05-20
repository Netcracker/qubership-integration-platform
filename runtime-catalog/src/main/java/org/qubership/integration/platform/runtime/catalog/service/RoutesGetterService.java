package org.qubership.integration.platform.runtime.catalog.service;

import lombok.extern.slf4j.Slf4j;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.DeploymentProcessingException;
import org.qubership.integration.platform.runtime.catalog.model.constant.CamelOptions;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.model.system.IntegrationSystemType;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.Environment;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.system.IntegrationSystem;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.chain.ElementRepository;
import org.qubership.integration.platform.runtime.catalog.util.ElementUtils;
import org.qubership.integration.platform.runtime.catalog.util.HashUtils;
import org.qubership.integration.platform.runtime.catalog.util.SimpleHttpUriUtils;
import org.qubership.integration.platform.runtime.catalog.util.TriggerUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

import static java.util.Objects.isNull;
import static org.qubership.integration.platform.runtime.catalog.model.constant.CamelNames.*;
import static org.qubership.integration.platform.runtime.catalog.util.TriggerUtils.getHttpConnectionTimeout;

@Slf4j
@Service
public class RoutesGetterService {
    @Value("${qip.control-plane.chain-routes-registration.egress-gateway:true}")
    private boolean registerOnEgress;

    @Value("${qip.control-plane.chain-routes-registration.ingress-gateways:true}")
    private boolean registerOnIncomingGateways;

    private final ElementRepository elementRepository;
    private final SystemService systemService;

    @Autowired
    public RoutesGetterService(
            ElementRepository elementRepository,
            SystemService systemService
    ) {
        this.elementRepository = elementRepository;
        this.systemService = systemService;
    }

    public List<DeploymentRoute> getRoutes(Specification<ChainElement> specification) {
        try {
            List<DeploymentRoute> allRoutes = new ArrayList<>();

            if (registerOnIncomingGateways) {
                // external and internal triggers
                List<DeploymentRoute> triggers = buildTriggersRoutes(specification);
                allRoutes.addAll(triggers);
            }
            if (registerOnEgress) {
                // external senders
                List<DeploymentRoute> senders = buildHttpSendersRoutes(specification);
                allRoutes.addAll(senders);
                // external services
                List<DeploymentRoute> serviceRoutes = buildServicesRoutes(specification);
                allRoutes.addAll(serviceRoutes);
            }

            log.debug("Routes for registration in control plane: {}", allRoutes);
            return allRoutes;
        } catch (Exception e) {
            log.error("Failed to build egress routes for deployment", e);
            throw new RuntimeException("Failed to build egress routes for deployment", e);
        }
    }

    private List<DeploymentRoute> buildHttpSendersRoutes(Specification<ChainElement> specification) {
        specification = specification.and((root, query, criteriaBuilder) ->
                root.get("type").in(List.of(HTTP_SENDER_COMPONENT, GRAPHQL_SENDER_COMPONENT)));
        return elementRepository.findAll(specification).stream()
                .filter(sender -> {
                    Object isExternalCall = sender.getProperty(CamelOptions.IS_EXTERNAL_CALL);
                    return isExternalCall == null || (boolean) isExternalCall;
                })
                .map(sender -> {
                    try {
                        String targetURL = SimpleHttpUriUtils.extractProtocolAndDomainWithPort(sender.getPropertyAsString(CamelOptions.URI));

                        String gatewayPrefix = String.format("/%s/%s/%s", sender.getType(), sender.getOriginalId(), getEncodedURL(getHttpConnectionTimeout(sender), targetURL));

                        DeploymentRoute.DeploymentRouteBuilder builder = DeploymentRoute.builder()
                                .path(targetURL)
                                .variableName(ElementUtils.buildRouteVariableName(sender))
                                .gatewayPrefix(gatewayPrefix)
                                .type(RouteType.EXTERNAL_SENDER);

                        if (sender.getType().equalsIgnoreCase(HTTP_SENDER_COMPONENT)) {
                            builder.connectTimeout(getHttpConnectionTimeout(sender));
                        }

                        return builder.build();
                    } catch (MalformedURLException e) {
                        throw new DeploymentProcessingException("Failed to post egress routes. Invalid URI in HTTP sender element");
                    }
                })
                .toList();
    }

    private List<DeploymentRoute> buildTriggersRoutes(Specification<ChainElement> specification) {
        specification = specification.and((root, query, criteriaBuilder) ->
                criteriaBuilder.equal(root.get("type"), HTTP_TRIGGER_COMPONENT));
        return elementRepository.findAll(specification).stream()
                .map(TriggerUtils::getHttpTriggerRoute)
                .map(route -> DeploymentRoute.builder()
                        .path("/" + route.getPath())
                        .type(RouteType.convertTriggerType(route.isExternal(), route.isPrivate()))
                        .connectTimeout(route.getConnectionTimeout())
                        .build())
                .collect(Collectors.toList());
    }

    private List<DeploymentRoute> buildServicesRoutes(Specification<ChainElement> specification) {
        specification = specification.and((root, query, criteriaBuilder) ->
            criteriaBuilder.equal(root.get("type"), SERVICE_CALL_COMPONENT));
        List<ChainElement> serviceCallElements = elementRepository.findAll(specification);
        Map<String, List<ChainElement>> systemsIds = serviceCallElements
                .stream()
                .collect(Collectors.groupingBy(
                        element -> (String) element.getProperty(CamelOptions.SYSTEM_ID),
                        Collectors.mapping(Function.identity(), Collectors.toList())
                ));

        List<IntegrationSystem> systems = systemService.findSystemsRequiredGatewayRoutes(systemsIds.keySet());
        List<DeploymentRoute> routes = new ArrayList<>();
        for (IntegrationSystem system : systems) {
            Environment environment = systemService.getActiveEnvironment(system);

            String path = systemService.getActiveEnvAddress(environment);
            Long connectionTimeout = systemService.getConnectTimeout(environment);

            RouteType routeType = getRouteTypeForSystemType(system.getIntegrationSystemType());

            List<ChainElement> elements = systemsIds.get(system.getId());
            for (ChainElement element : elements) {
                String gatewayPrefix = String.format("/system/%s", element.getOriginalId());

                routes.add(DeploymentRoute.builder()
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

    private RouteType getRouteTypeForSystemType(IntegrationSystemType systemType) {
        return isNull(systemType) ? null : switch (systemType) {
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
