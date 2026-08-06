package org.qubership.integration.platform.runtime.catalog.cr;

import com.coreos.monitoring.models.V1ServiceMonitor;
import com.coreos.monitoring.models.V1ServiceMonitorList;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.ModelMapper;
import io.kubernetes.client.util.Yaml;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.runtime.catalog.cr.builders.IntegrationsConfigurationConfigMapBuilder;
import org.qubership.integration.platform.runtime.catalog.cr.builders.chain.HttpRouteRuleNormalizer;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegrationList;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObjectList;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil;
import org.qubership.integration.platform.runtime.catalog.model.deployment.RouteType;
import org.qubership.integration.platform.runtime.catalog.model.deployment.update.DeploymentRouteUpdate;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.DeploymentRoute;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.element.ChainElement;
import org.qubership.integration.platform.runtime.catalog.rest.v1.mapper.DeploymentRouteMapper;
import org.qubership.integration.platform.runtime.catalog.service.RoutesGetterService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.runtime.catalog.cr.builders.chain.SourceConfigMapBuilder.SNAPSHOT_ID_LABEL;
import static org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKConstants.CAMEL_K_INTEGRATION_LABEL;
import static org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil.getName;

@Slf4j
@Service
public class CustomResourceService {
    public record IntegrationResources(
            CamelKIntegration integration,
            V1ServiceMonitor serviceMonitor,
            V1Service service,
            V1ConfigMap integrationsConfiguration,
            Collection<V1ConfigMap> integrationSources,
            V1Secret secret,
            Collection<KubeCustomObject> customResources,
            KubeCustomObject publicHttpRoute,
            KubeCustomObject privateHttpRoute
    ) {
        public Map<String, V1ConfigMap> getSourceByLabelMap(String label) {
            return integrationSources.stream().collect(Collectors.toMap(
                    cm -> Optional.ofNullable(cm.getMetadata())
                            .map(V1ObjectMeta::getLabels)
                            .map(labels -> labels.get(label))
                            .orElse(""),
                    Function.identity(),
                    (a, b) -> a));
        }
    }

    private final KubeOperator kubeOperator;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy;
    private final IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private final boolean monitoringEnabled;
    private final GenericCustomResources genericCustomResources;
    private final RoutesGetterService routesGetterService;
    private final DeploymentRouteMapper deploymentRouteMapper;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesPublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesPrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesInternalNamingStrategy;
    private final YAMLMapper yamlMapper;

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String HTTP_ROUTES_PLURAL = "httproutes";

    @Value("${qip.chains.external-routes.base-path:/qip-routes}")
    String baseRoutePrefix;

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Autowired
    public CustomResourceService(
            KubeOperator kubeOperator,
            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,
            @Qualifier("integrationsConfigurationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy,
            IntegrationConfigurationSerdes integrationConfigurationSerdes,
            GenericCustomResources genericCustomResources,
            @Value("${qip.cr.build.monitoring.enabled:false}") boolean monitoringEnabled,
            RoutesGetterService routesGetterService,
            DeploymentRouteMapper deploymentRouteMapper,
            @Qualifier("httpRoutePublicNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy,
            @Qualifier("httpRoutePrivateNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy,
            @Qualifier("engineRoutesPublicNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesPublicNamingStrategy,
            @Qualifier("engineRoutesPrivateNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesPrivateNamingStrategy,
            @Qualifier("engineRoutesInternalNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesInternalNamingStrategy,
            @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper
    ) {
        this.kubeOperator = kubeOperator;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.integrationsConfigurationConfigMapNamingStrategy = integrationsConfigurationConfigMapNamingStrategy;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.genericCustomResources = genericCustomResources;
        this.monitoringEnabled = monitoringEnabled;
        this.routesGetterService = routesGetterService;
        this.deploymentRouteMapper = deploymentRouteMapper;
        this.httpRoutePublicNamingStrategy = httpRoutePublicNamingStrategy;
        this.httpRoutePrivateNamingStrategy = httpRoutePrivateNamingStrategy;
        this.engineRoutesPublicNamingStrategy = engineRoutesPublicNamingStrategy;
        this.engineRoutesPrivateNamingStrategy = engineRoutesPrivateNamingStrategy;
        this.engineRoutesInternalNamingStrategy = engineRoutesInternalNamingStrategy;
        this.yamlMapper = yamlMapper;
    }

    @PostConstruct
    public void init() {
        ModelMapper.addModelMap("camel.apache.org", "v1", "Integration", "Integrations", CamelKIntegration.class, CamelKIntegrationList.class);
        ModelMapper.addModelMap("monitoring.coreos.com", "v1", "ServiceMonitor", "ServiceMonitors", V1ServiceMonitor.class, V1ServiceMonitorList.class);
        ModelMapper.addModelMap(GATEWAY_API_GROUP, GATEWAY_API_VERSION, "HTTPRoute", HTTP_ROUTES_PLURAL,
                KubeCustomObject.class, KubeCustomObjectList.class);
        genericCustomResources.registerModelMaps();
    }

    public void deploy(String resourceText) throws CustomResourceDeployError {
        try {
            List<Object> resources = Yaml.loadAll(resourceText);
            for (Object resource : resources) {
                kubeOperator.createOrUpdateResource(resource);
            }
        } catch (Exception exception) {
            throw new CustomResourceDeployError("Failed to deploy resources", exception);
        }
    }

    public void delete(String name) {
        deleteHttpRoutes(name);
        deleteEngineRoutes(name);
        getAllIntegrationResources(name).ifPresent(resources -> {
            Optional.ofNullable(resources.integration)
                    .flatMap(KubeUtil::getName)
                    .ifPresent(kubeOperator::deleteCamelKIntegration);
            Optional.ofNullable(resources.serviceMonitor)
                    .flatMap(KubeUtil::getName)
                    .ifPresent(kubeOperator::deleteServiceMonitor);
            Optional.ofNullable(resources.service)
                    .flatMap(KubeUtil::getName)
                    .ifPresent(kubeOperator::deleteService);
            Optional.ofNullable(resources.integrationsConfiguration)
                    .flatMap(KubeUtil::getName)
                    .ifPresent(kubeOperator::deleteConfigMap);
            Optional.ofNullable(resources.integrationSources)
                    .ifPresent(configMaps ->
                            configMaps.stream()
                                    .map(KubeUtil::getName)
                                    .filter(Optional::isPresent)
                                    .map(Optional::get)
                                    .forEach(kubeOperator::deleteConfigMap));
            Optional.ofNullable(resources.secret)
                    .flatMap(KubeUtil::getName)
                    .ifPresent(kubeOperator::deleteSecret);
            Optional.ofNullable(resources.customResources)
                    .ifPresent(customResources -> {
                        log.info("Deleting {} generic custom resource(s) for domain '{}'", customResources.size(), name);
                        customResources.forEach(customObject -> {
                            KubeUtil.getName(customObject).ifPresent(customObjectName -> {
                                GenericCustomResources.CustomResourceDefinition definition =
                                        genericCustomResources.definitionFor(customObject.getKind());
                                kubeOperator.deleteCustomObject(definition.group(), definition.version(), definition.plural(), customObjectName);
                            });
                        });
                    });
        });
    }

    public void deleteChainSnapshot(String name, String snapshotId) {
        getMainIntegrationResources(name).ifPresent(resources -> {
            CamelKIntegration integration = resources.integration();
            String cfgName = Optional.ofNullable(resources.getSourceByLabelMap(SNAPSHOT_ID_LABEL))
                    .map(m -> m.get(snapshotId))
                    .flatMap(KubeUtil::getName)
                    .orElse("");
            List<String> mounts = integration.getSpec()
                    .getTraits()
                    .getMount()
                    .getResources()
                    .stream()
                    .filter(mount -> !mount.contains(cfgName))
                    .collect(Collectors.toList());
            integration.getSpec().getTraits().getMount().setResources(mounts);
            integration.setApiVersion("camel.apache.org/v1");
            integration.setKind("Integration");
            kubeOperator.createOrUpdateResource(integration);
            Optional.ofNullable(resources.integrationsConfiguration).ifPresent(configMap -> {
                IntegrationsConfiguration integrationsConfiguration =
                        integrationConfigurationSerdes.getFromConfigMap(configMap);
                integrationsConfiguration.setSources(integrationsConfiguration.getSources().stream()
                        .filter(source -> !snapshotId.equals(source.getId()))
                        .collect(Collectors.toList()));
                configMap.setData(Collections.singletonMap(
                        IntegrationsConfigurationConfigMapBuilder.CONTENT_KEY,
                        integrationConfigurationSerdes.toYaml(integrationsConfiguration)));
                configMap.setApiVersion("v1");
                configMap.setKind("ConfigMap");
                try {
                    kubeOperator.createOrUpdateResource(configMap);
                } catch (KubeApiException e) {
                    throw new RuntimeException(e);
                }
            });
            if (StringUtils.isNotBlank(cfgName)) {
                kubeOperator.deleteConfigMap(cfgName);
            }
            deleteChainSnapshotHttpRoutes(name, snapshotId);
        });
    }

    public Optional<IntegrationResources> getMainIntegrationResources(String name) {
        return getIntegrationResources(name, false);
    }

    public Optional<IntegrationResources> getAllIntegrationResources(String name) {
        return getIntegrationResources(name, true);
    }

    private Optional<IntegrationResources> getIntegrationResources(String name, boolean includeAdditionalResources) {
        String integrationName = getIntegrationResourceName(name);
        Optional<CamelKIntegration> integration = kubeOperator.getIntegrationsByLabels(
            Map.of(domainLabel, name, bgVersionLabel, bgVersion))
                .stream()
                .findFirst();
        if (integration.isEmpty()) {
            return Optional.empty();
        }
        Optional<V1Service> service = kubeOperator
                .getServicesByLabel(CAMEL_K_INTEGRATION_LABEL, integrationName)
                .stream()
                .findFirst();
        Optional<V1ServiceMonitor> serviceMonitor = monitoringEnabled
                ? kubeOperator
                    .getServiceMonitorsByLabel(CAMEL_K_INTEGRATION_LABEL, integrationName)
                    .stream()
                    .findFirst()
                : Optional.empty();
        List<V1ConfigMap> configMaps = kubeOperator.getConfigMapsByLabel(CAMEL_K_INTEGRATION_LABEL, integrationName);
        String cfgName = getIntegrationCfgConfigMapName(name);
        Optional<V1ConfigMap> integrationsConfiguration = configMaps.stream()
                .filter(cm -> cfgName.equals(getName(cm).orElse(null)))
                .findFirst();
        List<V1ConfigMap> integrationSources = configMaps.stream()
                .filter(cm -> !cfgName.equals(getName(cm).orElse(null)))
                .toList();

        Optional<V1Secret> secret = Optional.empty();
        List<KubeCustomObject> customResources = new ArrayList<>();
        if (includeAdditionalResources) {
            secret = kubeOperator
                .getSecretsByLabel(CAMEL_K_INTEGRATION_LABEL, integrationName)
                .stream()
                .findFirst();
            genericCustomResources.getCustomResourceDefinitions().forEach((key, def) ->
                customResources.addAll(kubeOperator.getCustomObjectsByLabelAndDefinition(
                        CAMEL_K_INTEGRATION_LABEL, integrationName, def)));
        }

        String publicRouteName = httpRoutePublicNamingStrategy.getName(getContextForDomain(name));
        String privateRouteName = httpRoutePrivateNamingStrategy.getName(getContextForDomain(name));
        KubeCustomObject publicHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, publicRouteName)
                .orElse(null);
        KubeCustomObject privateHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, privateRouteName)
                .orElse(null);

        return Optional.of(new IntegrationResources(
                integration.orElse(null),
                serviceMonitor.orElse(null),
                service.orElse(null),
                integrationsConfiguration.orElse(null),
                integrationSources,
                secret.orElse(null),
                customResources,
                publicHttpRoute,
                privateHttpRoute
        ));
    }

    private String getIntegrationCfgConfigMapName(String name) {
        return integrationsConfigurationConfigMapNamingStrategy.getName(getContextForDomain(name));
    }

    private String getIntegrationResourceName(String domainName) {
        return integrationResourceNamingStrategy.getName(getContextForDomain(domainName));
    }

    private ResourceBuildContext<List<Snapshot>> getContextForDomain(String name) {
        return ResourceBuildContext.create(BuildInfo.builder()
                .options(ResourceBuildOptions.builder().name(name).build())
                .build()).updateTo(Collections.emptyList());
    }

    void deleteHttpRoutes(String name) {
        ResourceBuildContext<List<Snapshot>> context = getContextForDomain(name);
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRoutePublicNamingStrategy.getName(context));
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRoutePrivateNamingStrategy.getName(context));
    }

    void deleteEngineRoutes(String name) {
        ResourceBuildContext<List<Snapshot>> context = getContextForDomain(name);
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                engineRoutesPublicNamingStrategy.getName(context));
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                engineRoutesPrivateNamingStrategy.getName(context));
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                engineRoutesInternalNamingStrategy.getName(context));
    }

    void deleteChainSnapshotHttpRoutes(String name, String snapshotId) {
        Specification<ChainElement> spec = (root, query, cb) ->
                cb.equal(root.get("snapshot").get("id"), snapshotId);
        List<DeploymentRoute> ownRoutes = routesGetterService.getRoutes(spec);
        List<DeploymentRouteUpdate> ownUpdates = deploymentRouteMapper.asUpdates(ownRoutes);

        Set<String> publicPaths = tierOwnPaths(ownUpdates, RouteType::isExternalTriggerRoute);
        Set<String> privatePaths = tierOwnPaths(ownUpdates, RouteType::isPrivateTriggerRoute);

        if (publicPaths.isEmpty() && privatePaths.isEmpty()) {
            return;
        }
        if (!publicPaths.isEmpty()) {
            stripPathsFromTier(httpRoutePublicNamingStrategy.getName(getContextForDomain(name)), publicPaths, "public");
        }
        if (!privatePaths.isEmpty()) {
            stripPathsFromTier(httpRoutePrivateNamingStrategy.getName(getContextForDomain(name)), privatePaths, "private");
        }
    }

    /**
     * Builds the set of this snapshot's own fully-prefixed route paths for a single gateway tier.
     * Egress routes ({@code EXTERNAL_SENDER}/{@code EXTERNAL_SERVICE}, whose "paths" are absolute
     * target URLs, not gateway paths) are excluded by the tier predicate, and each remaining path is
     * prefixed with {@link #baseRoutePrefix} so it can be compared exactly against the paths recorded
     * in the tier's HTTPRoute CR (mirroring {@code HttpRouteResourceBuilder}'s own path bookkeeping).
     */
    private Set<String> tierOwnPaths(List<DeploymentRouteUpdate> ownUpdates, Predicate<RouteType> tierPredicate) {
        return ownUpdates.stream()
                .filter(route -> tierPredicate.test(route.getType()))
                .map(route -> baseRoutePrefix + route.getPath())
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private void stripPathsFromTier(String routeName, Set<String> ownPaths, String tierName) {
        Optional<KubeCustomObject> existing = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, routeName);
        if (existing.isEmpty()) {
            return;
        }
        KubeCustomObject httpRoute = existing.get();
        Map<String, Object> spec = httpRoute.getSpec();
        Object rulesRaw = spec == null ? null : spec.get("rules");
        if (!(rulesRaw instanceof List<?> rules)) {
            log.warn("HTTPRoute '{}' ({} tier) has no 'rules' to strip snapshot paths from; leaving it unchanged",
                    routeName, tierName);
            return;
        }
        List<Map<String, Object>> remaining = rules.stream()
                .map(rule -> (Map<String, Object>) rule)
                .filter(rule -> {
                    String path = extractRulePath(rule);
                    if (path == null) {
                        log.warn("HTTPRoute '{}' ({} tier) has a rule with an unrecognized path match shape; "
                                + "keeping it rather than risk dropping it during snapshot cleanup", routeName, tierName);
                        return true;
                    }
                    return !ownPaths.contains(path);
                })
                .map(this::normalizeRawRule)
                .toList();
        if (remaining.isEmpty()) {
            kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, routeName);
            return;
        }
        httpRoute.getSpec().put("rules", remaining);
        httpRoute.setApiVersion(GATEWAY_API_GROUP + "/" + GATEWAY_API_VERSION);
        httpRoute.setKind("HTTPRoute");
        kubeOperator.createOrUpdateResource(httpRoute);
    }

    /**
     * Fixes up a surviving rule's whole-number values before it's re-applied. {@code rule} comes
     * from {@link KubeOperator#getCustomObject}, whose Gson deserialization decodes every JSON
     * number as {@code Double} (see {@link HttpRouteRuleNormalizer}), so a port or weight that
     * round-trips through this method unmodified would be re-emitted as e.g. {@code 8080.0} and
     * rejected by the Gateway API's int32-typed schema.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeRawRule(Map<String, Object> rule) {
        ObjectNode node = yamlMapper.convertValue(rule, ObjectNode.class);
        HttpRouteRuleNormalizer.normalizeIntegralDoubles(node);
        return yamlMapper.convertValue(node, Map.class);
    }

    /**
     * Reads {@code matches[0].path.value} out of a raw (deserialized-from-YAML/JSON) HTTPRoute rule,
     * returning {@code null} for any shape that doesn't hold a readable path (no {@code matches}, an
     * empty {@code matches} list, a header-only match with no {@code path}, and similar) instead of
     * throwing. The caller treats a {@code null} result as "not matched by any of ownPaths" and
     * preserves the rule.
     */
    private String extractRulePath(Map<String, Object> rule) {
        try {
            if (!(rule.get("matches") instanceof List<?> matches) || matches.isEmpty()) {
                return null;
            }
            if (!(matches.get(0) instanceof Map<?, ?> match)) {
                return null;
            }
            if (!(match.get("path") instanceof Map<?, ?> path)) {
                return null;
            }
            return path.get("value") instanceof String value ? value : null;
        } catch (RuntimeException e) {
            return null;
        }
    }
}
