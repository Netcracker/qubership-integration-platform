package org.qubership.integration.platform.runtime.catalog.cr;

import com.coreos.monitoring.models.V1ServiceMonitor;
import com.coreos.monitoring.models.V1ServiceMonitorList;
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
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegrationList;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.naming.NamingStrategy;
import org.qubership.integration.platform.runtime.catalog.cr.rest.v1.dto.ResourceBuildOptions;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.chain.Snapshot;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
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
            Collection<KubeCustomObject> customResources
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
            @Value("${qip.cr.build.monitoring.enabled:false}") boolean monitoringEnabled
    ) {
        this.kubeOperator = kubeOperator;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.integrationsConfigurationConfigMapNamingStrategy = integrationsConfigurationConfigMapNamingStrategy;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.monitoringEnabled = monitoringEnabled;
    }

    @PostConstruct
    public void init() {
        ModelMapper.addModelMap("camel.apache.org", "v1", "Integration", "Integrations", CamelKIntegration.class, CamelKIntegrationList.class);
        ModelMapper.addModelMap("monitoring.coreos.com", "v1", "ServiceMonitor", "ServiceMonitors", V1ServiceMonitor.class, V1ServiceMonitorList.class);
        GenericCustomResources.registerModelMaps();
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
                                        GenericCustomResources.definitionFor(customObject.getKind());
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
            GenericCustomResources.getCustomResourceDefinitions().forEach((key, def) -> {
                customResources.addAll(kubeOperator.getCustomObjectsByLabelAndDefinition(
                        CAMEL_K_INTEGRATION_LABEL, integrationName, def));
            });
        }
        return Optional.of(new IntegrationResources(
                integration.orElse(null),
                serviceMonitor.orElse(null),
                service.orElse(null),
                integrationsConfiguration.orElse(null),
                integrationSources,
                secret.orElse(null),
                customResources
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
}
