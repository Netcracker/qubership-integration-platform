package org.qubership.integration.platform.runtime.catalog.cr;

import com.coreos.monitoring.models.V1ServiceMonitor;
import com.coreos.monitoring.models.V1ServiceMonitorList;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ConfigMap;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import io.kubernetes.client.openapi.models.V1Secret;
import io.kubernetes.client.openapi.models.V1Service;
import io.kubernetes.client.util.ModelMapper;
import io.kubernetes.client.util.Yaml;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.qubership.integration.platform.camelk.builders.IntegrationsConfigurationConfigMapBuilder;
import org.qubership.integration.platform.camelk.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegrationList;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObjectList;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.qubership.integration.platform.camelk.builders.chain.SourceConfigMapBuilder.SNAPSHOT_ID_LABEL;
import static org.qubership.integration.platform.camelk.k8s.CamelKConstants.CAMEL_K_INTEGRATION_LABEL;
import static org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil.getName;

@Slf4j
@Service
public class MicroDomainService {
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

    /** Identifies a document in the built YAML, and an entry in the observation map. */
    public record ResourceKey(String kind, String name) { }

    /**
     * The built YAML plus what Phase 1 observed for each object it read. The observation is the
     * live {@code V1ObjectMeta} rather than a bare version string: it already carries
     * {@code resourceVersion}, and it is also the metadata the write overlays generated labels onto.
     *
     * <p>A key that is absent means Phase 1 never read the object, which is the ordinary case under
     * {@code REWRITE}.
     */
    public record BuiltResources(String yaml, Map<ResourceKey, V1ObjectMeta> observations) { }

    private final KubeOperator kubeOperator;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy;
    private final IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private final boolean monitoringEnabled;
    private final GenericCustomResources genericCustomResources;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy;
    private final K8sNameValidator k8sNameValidator;

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String HTTP_ROUTES_PLURAL = "httproutes";
    private static final String NETWORKING_ISTIO_API_GROUP = "networking.istio.io";
    private static final String NETWORKING_ISTIO_API_VERSION = "v1";

    private final IntegrationServiceCatalog integrationServiceCatalog;

    @Value("${cip.cr.labels.domain}")
    String domainLabel;

    @Value("${cip.cr.labels.bg-version}")
    String bgVersionLabel;

    @Value("${spring.application.deployment_version}")
    String bgVersion;

    @Autowired
    public MicroDomainService(
            KubeOperator kubeOperator,
            @Qualifier("integrationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy,
            @Qualifier("integrationsConfigurationResourceNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy,
            IntegrationConfigurationSerdes integrationConfigurationSerdes,
            GenericCustomResources genericCustomResources,
            IntegrationServiceCatalog integrationServiceCatalog,
            @Value("${cip.cr.build.monitoring.enabled:false}") boolean monitoringEnabled,
            @Qualifier("engineRoutesNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy,
            K8sNameValidator k8sNameValidator
    ) {
        this.kubeOperator = kubeOperator;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.integrationsConfigurationConfigMapNamingStrategy = integrationsConfigurationConfigMapNamingStrategy;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.genericCustomResources = genericCustomResources;
        this.integrationServiceCatalog = integrationServiceCatalog;
        this.monitoringEnabled = monitoringEnabled;
        this.engineRoutesNamingStrategy = engineRoutesNamingStrategy;
        this.k8sNameValidator = k8sNameValidator;
    }

    @PostConstruct
    public void init() {
        ModelMapper.addModelMap("camel.apache.org", "v1", "Integration", "Integrations", CamelKIntegration.class, CamelKIntegrationList.class);
        ModelMapper.addModelMap("monitoring.coreos.com", "v1", "ServiceMonitor", "ServiceMonitors", V1ServiceMonitor.class, V1ServiceMonitorList.class);
        ModelMapper.addModelMap(GATEWAY_API_GROUP, GATEWAY_API_VERSION, "HTTPRoute", HTTP_ROUTES_PLURAL,
                KubeCustomObject.class, KubeCustomObjectList.class);
        ModelMapper.addModelMap(NETWORKING_ISTIO_API_GROUP, NETWORKING_ISTIO_API_VERSION, "ServiceEntry", "serviceentries",
                KubeCustomObject.class, KubeCustomObjectList.class);
        ModelMapper.addModelMap(NETWORKING_ISTIO_API_GROUP, NETWORKING_ISTIO_API_VERSION, "DestinationRule", "destinationrules",
                KubeCustomObject.class, KubeCustomObjectList.class);
        genericCustomResources.registerModelMaps();
    }

    /**
     * Every existing {@code ServiceEntry} in this namespace, unfiltered -- there's no per-domain
     * label to scope by, since a single {@code ServiceEntry} can be shared across every domain that
     * targets its host. Used to seed {@code EgressRouteResourceBuilder}'s build cache so it can
     * merge its own port into whatever another domain already contributed for the same host,
     * instead of overwriting it; see {@code MicroDomainResourceBuildContextFactory}.
     */
    public List<KubeCustomObject> getExistingServiceEntries() {
        return kubeOperator.getServiceEntries();
    }

    /** Same rationale as {@link #getExistingServiceEntries}, for {@code DestinationRule}. */
    public List<KubeCustomObject> getExistingDestinationRules() {
        return kubeOperator.getDestinationRules();
    }

    public void deploy(BuiltResources built) throws MicroDomainDeployError {
        try {
            List<Object> resources = new ArrayList<>(Yaml.loadAll(built.yaml()));
            // With mount hot reload, the Camel-K operator resets the Integration's status whenever a
            // ConfigMap labeled with the Integration's name changes. That status write moves the
            // Integration's resourceVersion past the one Phase 1 observed, so writing the ConfigMaps
            // first makes this deploy fail its own precondition with a 409. The sort is stable, so the
            // other documents keep their build order.
            resources.sort(Comparator.comparing(resource -> !(resource instanceof CamelKIntegration)));
            for (Object resource : resources) {
                applyObservation(resource, built.observations());
                kubeOperator.createOrUpdateResource(resource);
            }
        } catch (KubeApiConflictException conflict) {
            throw conflict;
        } catch (Exception exception) {
            log.error("Failed to deploy resources", exception);
            throw new MicroDomainDeployError("Failed to deploy resources: " + exception.getMessage(), exception);
        }
    }

    /**
     * Carries the metadata Phase 1 observed for {@code resource} into the document, so the write
     * uses it as an optimistic-concurrency precondition; see {@link #applyLiveMetadata}. A document
     * Phase 1 never read is left exactly as generated, and {@code KubeOperator} resolves it with a
     * write-time read.
     */
    private void applyObservation(Object resource, Map<ResourceKey, V1ObjectMeta> observations) {
        if (!(resource instanceof KubernetesObject object) || object.getMetadata() == null) {
            return;
        }
        V1ObjectMeta observation = observations.get(new ResourceKey(object.getKind(), object.getMetadata().getName()));
        if (observation != null) {
            applyLiveMetadata(object.getMetadata(), observation);
        }
    }

    /**
     * Replaces {@code generated} with the metadata Phase 1 observed, then folds the generated name,
     * labels, and annotations back on top -- generated values win on key collision. A PUT replaces
     * {@code metadata} wholesale, so anything the live object carried and this method did not copy
     * across is dropped from the cluster: {@code ownerReferences} (which garbage collection depends
     * on), {@code finalizers}, and the Camel-K operator's {@code camel.apache.org/*} annotations.
     * Carrying the observed {@code resourceVersion} across is what makes the write conditional.
     *
     * <p>Mutates {@code generated} in place because {@code KubernetesObject} exposes {@code
     * getMetadata} and no setter, so there is no way to hand the document a different instance.
     */
    private static void applyLiveMetadata(V1ObjectMeta generated, V1ObjectMeta live) {
        String name = generated.getName();
        Map<String, String> labels = overlay(live.getLabels(), generated.getLabels());
        Map<String, String> annotations = overlay(live.getAnnotations(), generated.getAnnotations());

        generated.setCreationTimestamp(live.getCreationTimestamp());
        generated.setDeletionGracePeriodSeconds(live.getDeletionGracePeriodSeconds());
        generated.setDeletionTimestamp(live.getDeletionTimestamp());
        generated.setFinalizers(live.getFinalizers());
        generated.setGenerateName(live.getGenerateName());
        generated.setGeneration(live.getGeneration());
        generated.setManagedFields(live.getManagedFields());
        generated.setNamespace(live.getNamespace());
        generated.setOwnerReferences(live.getOwnerReferences());
        generated.setResourceVersion(live.getResourceVersion());
        generated.setSelfLink(live.getSelfLink());
        generated.setUid(live.getUid());

        generated.setName(name);
        generated.setLabels(labels);
        generated.setAnnotations(annotations);
    }

    /** {@code base} with {@code overrides} folded on top; generated values win on key collision. */
    private static Map<String, String> overlay(Map<String, String> base, Map<String, String> overrides) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (overrides != null) {
            merged.putAll(overrides);
        }
        return merged.isEmpty() ? null : merged;
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
                        customResources.forEach(customObject ->
                            KubeUtil.getName(customObject).ifPresent(customObjectName -> {
                                GenericCustomResources.CustomResourceDefinition definition =
                                        genericCustomResources.definitionFor(customObject.getKind());
                                kubeOperator.deleteCustomObject(definition.group(), definition.version(), definition.plural(), customObjectName);
                            })
                        );
                    });
        });
    }

    public void deleteChainSnapshot(String name, String snapshotId) {
        getMainIntegrationResources(name).ifPresent(resources -> {
            CamelKIntegration integration = resources.integration();
            String cfgName = Optional.ofNullable(resources.getSourceByLabelMap(SNAPSHOT_ID_LABEL))
                    .map(m -> m.get(k8sNameValidator.validate(snapshotId)))
                    .flatMap(KubeUtil::getName)
                    .orElse("");
            List<String> mounts = integration.getSpec()
                    .getTraits()
                    .getMount()
                    .getResources()
                    .stream()
                    .filter(mount -> cfgName.isEmpty() || !mount.contains(cfgName))
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
                kubeOperator.createOrUpdateResource(configMap);
            });
            if (StringUtils.isNotBlank(cfgName)) {
                kubeOperator.deleteConfigMap(cfgName);
            }
            String snapshotLabel = k8sNameValidator.validate(snapshotId);
            deleteChainHttpRoutes(name, snapshotLabel::equals);
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
        BuildInfo buildInfo = BuildInfo.builder()
            .options(ResourceBuildOptions.builder().name(name).build())
            .build();
        return ResourceBuildContext.create(buildInfo, integrationServiceCatalog)
            .updateTo(Collections.emptyList());
    }

    void deleteHttpRoutes(String name) {
        deleteChainHttpRoutes(name, snapshotLabel -> true);
    }

    /**
     * Deletes the HTTPRoutes of every chain in domain {@code name} whose snapshot is not in {@code snapshotIds}, so a
     * REWRITE leaves routes only for the chains it deployed.
     */
    public void deleteHttpRoutesOfOtherSnapshots(String name, Collection<String> snapshotIds) {
        Set<String> snapshotLabels = snapshotIds.stream()
                .map(k8sNameValidator::validate)
                .collect(Collectors.toSet());
        deleteChainHttpRoutes(name, snapshotLabel -> !snapshotLabels.contains(snapshotLabel));
    }

    /**
     * Deletes the HTTPRoutes of domain {@code name} whose snapshot label passes
     * {@code snapshotLabelFilter}. The domain holds one HTTPRoute per chain and gateway tier.
     */
    private void deleteChainHttpRoutes(String name, Predicate<String> snapshotLabelFilter) {
        Map<String, String> labels = new HashMap<>();
        labels.put(domainLabel, k8sNameValidator.validate(name));
        labels.put(bgVersionLabel, bgVersion);
        labels.put(SNAPSHOT_ID_LABEL, null);
        kubeOperator.getCustomObjectsByLabels(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, labels).stream()
                .filter(httpRoute -> snapshotLabelFilter.test(httpRoute.getMetadata().getLabels().get(SNAPSHOT_ID_LABEL)))
                .forEach(httpRoute -> kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION,
                        HTTP_ROUTES_PLURAL, httpRoute.getMetadata().getName()));
    }

    void deleteEngineRoutes(String name) {
        ResourceBuildContext<List<Snapshot>> context = getContextForDomain(name);
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                engineRoutesNamingStrategy.getName(context));
    }
}
