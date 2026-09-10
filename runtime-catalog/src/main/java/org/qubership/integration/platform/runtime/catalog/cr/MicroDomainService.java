package org.qubership.integration.platform.runtime.catalog.cr;

import com.coreos.monitoring.models.V1ServiceMonitor;
import com.coreos.monitoring.models.V1ServiceMonitorList;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
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
import org.qubership.integration.platform.camelk.builders.chain.HttpRouteRuleNormalizer;
import org.qubership.integration.platform.camelk.integrations.configuration.IntegrationsConfiguration;
import org.qubership.integration.platform.camelk.integrations.configuration.SourceDefinition;
import org.qubership.integration.platform.camelk.model.BuildInfo;
import org.qubership.integration.platform.camelk.model.ResourceBuildContext;
import org.qubership.integration.platform.camelk.model.options.ResourceBuildOptions;
import org.qubership.integration.platform.camelk.model.routes.Route;
import org.qubership.integration.platform.camelk.model.routes.RouteType;
import org.qubership.integration.platform.camelk.naming.NamingStrategy;
import org.qubership.integration.platform.camelk.naming.validation.K8sNameValidator;
import org.qubership.integration.platform.camelk.services.EgressServiceRouteFormatter;
import org.qubership.integration.platform.camelk.services.RoutesGetterService;
import org.qubership.integration.platform.camelk.sources.IntegrationServiceCatalog;
import org.qubership.integration.platform.camelk.util.paths.GatewayPathMatch;
import org.qubership.integration.platform.chain.model.Snapshot;
import org.qubership.integration.platform.runtime.catalog.adapters.SnapshotAdapter;
import org.qubership.integration.platform.runtime.catalog.cr.integrations.configuration.IntegrationConfigurationSerdes;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegration;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.CamelKIntegrationList;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.GenericCustomResources;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObject;
import org.qubership.integration.platform.runtime.catalog.cr.k8s.KubeCustomObjectList;
import org.qubership.integration.platform.runtime.catalog.exception.exceptions.kubernetes.KubeApiConflictException;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeOperator;
import org.qubership.integration.platform.runtime.catalog.kubernetes.KubeUtil;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.entity.AbstractEntity;
import org.qubership.integration.platform.runtime.catalog.persistence.configs.repository.SnapshotRepository;
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
            Collection<KubeCustomObject> customResources,
            KubeCustomObject publicHttpRoute,
            KubeCustomObject privateHttpRoute,
            KubeCustomObject egressHttpRoute
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
     * <p>Three states, and they are not interchangeable. {@code Optional.of(meta)} means Phase 1
     * read the object; {@code Optional.empty()} means it looked and found nothing; a key that is
     * absent entirely means Phase 1 never looked, which is the ordinary case under {@code REWRITE}.
     */
    public record BuiltResources(String yaml, Map<ResourceKey, Optional<V1ObjectMeta>> observations) { }

    private final KubeOperator kubeOperator;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationResourceNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> integrationsConfigurationConfigMapNamingStrategy;
    private final IntegrationConfigurationSerdes integrationConfigurationSerdes;
    private final boolean monitoringEnabled;
    private final GenericCustomResources genericCustomResources;
    private final RoutesGetterService routesGetterService;
    private final SnapshotRepository snapshotRepository;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy;
    private final NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy;
    private final YAMLMapper yamlMapper;
    private final K8sNameValidator k8sNameValidator;

    private static final String GATEWAY_API_GROUP = "gateway.networking.k8s.io";
    private static final String GATEWAY_API_VERSION = "v1";
    private static final String HTTP_ROUTES_PLURAL = "httproutes";
    private static final String NETWORKING_ISTIO_API_GROUP = "networking.istio.io";
    private static final String NETWORKING_ISTIO_API_VERSION = "v1";

    private final IntegrationServiceCatalog integrationServiceCatalog;

    @Value("${qip.chains.external-routes.base-path}")
    String baseRoutePrefix;

    @Value("${qip.cr.labels.domain}")
    String domainLabel;

    @Value("${qip.cr.labels.bg-version}")
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
            @Value("${qip.cr.build.monitoring.enabled:false}") boolean monitoringEnabled,
            RoutesGetterService routesGetterService,
            SnapshotRepository snapshotRepository,
            @Qualifier("httpRoutePublicNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePublicNamingStrategy,
            @Qualifier("httpRoutePrivateNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRoutePrivateNamingStrategy,
            @Qualifier("httpRouteEgressNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> httpRouteEgressNamingStrategy,
            @Qualifier("engineRoutesNamingStrategy")
            NamingStrategy<ResourceBuildContext<List<Snapshot>>> engineRoutesNamingStrategy,
            @Qualifier("customResourceYamlMapper") YAMLMapper yamlMapper,
            K8sNameValidator k8sNameValidator
    ) {
        this.kubeOperator = kubeOperator;
        this.integrationResourceNamingStrategy = integrationResourceNamingStrategy;
        this.integrationsConfigurationConfigMapNamingStrategy = integrationsConfigurationConfigMapNamingStrategy;
        this.integrationConfigurationSerdes = integrationConfigurationSerdes;
        this.genericCustomResources = genericCustomResources;
        this.integrationServiceCatalog = integrationServiceCatalog;
        this.monitoringEnabled = monitoringEnabled;
        this.routesGetterService = routesGetterService;
        this.snapshotRepository = snapshotRepository;
        this.httpRoutePublicNamingStrategy = httpRoutePublicNamingStrategy;
        this.httpRoutePrivateNamingStrategy = httpRoutePrivateNamingStrategy;
        this.httpRouteEgressNamingStrategy = httpRouteEgressNamingStrategy;
        this.engineRoutesNamingStrategy = engineRoutesNamingStrategy;
        this.yamlMapper = yamlMapper;
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
            List<Object> resources = Yaml.loadAll(built.yaml());
            for (Object resource : resources) {
                boolean observedAbsent = applyObservation(resource, built.observations());
                kubeOperator.createOrUpdateResource(resource, observedAbsent);
            }
        } catch (KubeApiConflictException conflict) {
            throw conflict;
        } catch (Exception exception) {
            throw new MicroDomainDeployError("Failed to deploy resources", exception);
        }
    }

    /**
     * Resolves which of Phase 1's three observation states {@code resource} falls into, and rebuilds
     * its metadata for the states that carry one.
     *
     * <p>Returns {@code true} for the observed-absent state alone, so the caller can tell
     * {@code KubeOperator} to create the object outright. Collapsing that state into the others
     * would send the document through a write-time GET, and a racing writer that created the object
     * during the build would then be overwritten wholesale instead of surfacing as a conflict.
     *
     * <p>The two remaining states both return {@code false}. A key absent from {@code observations}
     * means Phase 1 never read this kind in this mode, so the document is left exactly as generated
     * and {@code KubeOperator} resolves it with a write-time read. An observation holding live
     * metadata means the write carries it as an optimistic-concurrency precondition; see
     * {@link #applyLiveMetadata}.
     */
    private boolean applyObservation(Object resource, Map<ResourceKey, Optional<V1ObjectMeta>> observations) {
        if (!(resource instanceof KubernetesObject object) || object.getMetadata() == null) {
            return false;
        }
        ResourceKey key = new ResourceKey(object.getKind(), object.getMetadata().getName());
        if (!observations.containsKey(key)) {
            return false;
        }
        Optional<V1ObjectMeta> observation = observations.get(key);
        if (observation.isEmpty()) {
            return true;
        }
        applyLiveMetadata(object.getMetadata(), observation.get());
        return false;
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
            Optional<Set<String>> remainingSnapshotIds = remainingSnapshotIds(resources, snapshotId);
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
            remainingSnapshotIds.ifPresentOrElse(
                    ids -> deleteChainSnapshotHttpRoutes(name, snapshotId, ids),
                    () -> log.warn("Micro-domain '{}' has no integrations-configuration ConfigMap, so the snapshots it still "
                            + "hosts are unknown. Kept every rule for removed snapshot '{}' rather than risk stripping "
                            + "one a live chain still serves. Redeploy the domain (REWRITE mode) to clear the leftovers.",
                            name, snapshotId));
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
        String egressRouteName = httpRouteEgressNamingStrategy.getName(getContextForDomain(name));
        KubeCustomObject publicHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, publicRouteName)
                .orElse(null);
        KubeCustomObject privateHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, privateRouteName)
                .orElse(null);
        KubeCustomObject egressHttpRoute = kubeOperator
                .getCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL, egressRouteName)
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
                privateHttpRoute,
                egressHttpRoute
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
        ResourceBuildContext<List<Snapshot>> context = getContextForDomain(name);
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRoutePublicNamingStrategy.getName(context));
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRoutePrivateNamingStrategy.getName(context));
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                httpRouteEgressNamingStrategy.getName(context));
    }

    void deleteEngineRoutes(String name) {
        ResourceBuildContext<List<Snapshot>> context = getContextForDomain(name);
        kubeOperator.deleteCustomObject(GATEWAY_API_GROUP, GATEWAY_API_VERSION, HTTP_ROUTES_PLURAL,
                engineRoutesNamingStrategy.getName(context));
    }

    /**
     * The snapshot IDs this micro-domain still hosts once {@code removedSnapshotId} is gone, read
     * from the integrations-configuration ConfigMap's source list, or {@link Optional#empty()} when
     * the domain has no such ConfigMap and they cannot be enumerated at all. That list is the only
     * in-cluster record holding raw snapshot IDs, and so the only one whose entries can go straight
     * to {@code SnapshotRepository}: the source ConfigMaps' {@code SNAPSHOT_ID_LABEL} holds a
     * {@code K8sNameValidator}-sanitized form, which strips a leading digit and therefore misses the
     * catalog row for most snapshot UUIDs.
     *
     * <p>{@link #deleteChainSnapshot} reads this before it rewrites that ConfigMap, so the removed
     * snapshot is still listed and is subtracted here. A redeployed chain's list already carries the
     * new snapshot ID by the time cleanup runs, because {@code IntegrationsConfiguration.merge}
     * dedupes sources by {@code chainId} and the later write wins. Cleanup depends on that dedupe
     * key: change it and a superseded snapshot stops seeing its replacement.
     *
     * <p>A present but blank ConfigMap yields an empty set, not an empty {@code Optional}:
     * {@code IntegrationConfigurationSerdes.getFromConfigMap} returns an empty
     * {@code IntegrationsConfiguration} rather than null, and a domain whose last chain is being
     * removed legitimately has no remaining snapshots. Those two cases must stay distinct -- the
     * empty set still strips, the empty {@code Optional} does not. A present configuration listing
     * no sources is an affirmative statement that no chains are deployed to the domain -- unlike an
     * absent ConfigMap, which carries no information at all -- so stripping the removed snapshot's
     * remaining rules here is correct rather than blind.
     */
    private Optional<Set<String>> remainingSnapshotIds(IntegrationResources resources, String removedSnapshotId) {
        V1ConfigMap configurationConfigMap = resources.integrationsConfiguration();
        if (configurationConfigMap == null) {
            return Optional.empty();
        }
        Set<String> ids = Optional.ofNullable(integrationConfigurationSerdes.getFromConfigMap(configurationConfigMap))
                .map(IntegrationsConfiguration::getSources)
                .map(sources -> sources.stream()
                        .map(SourceDefinition::getId)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toCollection(HashSet::new)))
                .orElseGet(HashSet::new);
        ids.remove(removedSnapshotId);
        return Optional.of(ids);
    }

    /**
     * Strips {@code snapshotId}'s gateway paths from this domain's shared HTTPRoute tiers, minus any
     * path {@code remainingSnapshotIds} still owns. The tiers are one CR per micro-domain, shared by
     * every snapshot that domain hosts, and two snapshots can legitimately claim the same path: a
     * chain redeployed under a new snapshot ID before the superseded one is removed, or two chains
     * reaching the same external system through the same egress prefix. Deleting a rule another
     * snapshot still serves takes a running chain offline silently, so the strip set is this
     * snapshot's paths minus every remaining snapshot's paths.
     * {@code ChainRouteRegistry.getUnsharedRoutes} runs the same shape of subtraction on the engine
     * side over a narrower set: it compares only other deployments of the same chain, while this
     * compares every snapshot the domain hosts, other chains included.
     * <p>When either resolution is incomplete the method strips nothing at all, across every tier.
     * An unresolved snapshot's routes are exactly what cannot be seen, so there is no way to
     * attribute it to one tier and spare the others. That leaves stale rules behind, and nothing
     * reconciles these CRs afterwards, so they persist until the domain is deleted or the owning
     * chain is redeployed. It is the safer half of the trade: the alternative removes a rule a
     * running chain still serves.
     */
    void deleteChainSnapshotHttpRoutes(String name, String snapshotId, Set<String> remainingSnapshotIds) {
        ResolvedRoutes own = snapshotRoutes(Set.of(snapshotId));
        ResolvedRoutes retained = snapshotRoutes(remainingSnapshotIds);
        if (!retained.isComplete()) {
            log.warn("Snapshot(s) {} listed for micro-domain '{}' have no catalog row, so the paths they own "
                    + "are unknown. Kept every rule for removed snapshot '{}' rather than risk stripping one a "
                    + "live chain still serves. Redeploy the domain (REWRITE mode) to clear the leftovers.",
                    retained.unresolvedIds(), name, snapshotId);
            return;
        }
        if (!own.isComplete()) {
            log.warn("Removed snapshot '{}' has no catalog row, so the paths it owns in micro-domain '{}' are "
                    + "unknown. Its HTTPRoute rules stay in place. Redeploy the domain (REWRITE mode) to clear them.",
                    snapshotId, name);
            return;
        }
        List<Route> ownRoutes = own.routes();
        List<Route> retainedRoutes = retained.routes();

        Set<GatewayPathMatch> publicPaths = unsharedPaths(
                tierOwnPaths(ownRoutes, RouteType::isExternalTriggerRoute),
                tierOwnPaths(retainedRoutes, RouteType::isExternalTriggerRoute));
        Set<GatewayPathMatch> privatePaths = unsharedPaths(
                tierOwnPaths(ownRoutes, RouteType::isPrivateTriggerRoute),
                tierOwnPaths(retainedRoutes, RouteType::isPrivateTriggerRoute));
        Set<GatewayPathMatch> egressPaths = unsharedPaths(
                egressOwnPaths(ownRoutes), egressOwnPaths(retainedRoutes));

        if (publicPaths.isEmpty() && privatePaths.isEmpty() && egressPaths.isEmpty()) {
            return;
        }
        if (!publicPaths.isEmpty()) {
            stripPathsFromTier(httpRoutePublicNamingStrategy.getName(getContextForDomain(name)), publicPaths, "public");
        }
        if (!privatePaths.isEmpty()) {
            stripPathsFromTier(httpRoutePrivateNamingStrategy.getName(getContextForDomain(name)), privatePaths, "private");
        }
        if (!egressPaths.isEmpty()) {
            stripPathsFromTier(httpRouteEgressNamingStrategy.getName(getContextForDomain(name)), egressPaths, "egress");
        }
    }

    /**
     * The routes a set of snapshot IDs resolved to, plus the IDs that had no catalog row. An
     * incomplete result means some other snapshot's paths are invisible, so the caller must not
     * strip anything: it cannot tell a path only the removed snapshot owns from one a live chain
     * still serves.
     */
    private record ResolvedRoutes(List<Route> routes, List<String> unresolvedIds) {
        boolean isComplete() {
            return unresolvedIds.isEmpty();
        }
    }

    /**
     * Resolves {@code snapshotIds} to the gateway routes they define, reporting any ID the catalog
     * database has no row for rather than logging and continuing. The caller decides what an
     * incomplete result means, because the answer differs between the removed snapshot and the
     * ones that remain.
     *
     * <p>Unresolved IDs are computed only when fewer rows came back than were asked for. Reading
     * {@code getId()} on every row unconditionally would be equivalent, but the test suite stubs
     * the repository with bare mocks whose ID is {@code null}, and they would all read as
     * unresolved.
     *
     * <p>Comparing counts to decide completeness is only valid because {@code findAllByIdIn} is a
     * derived query over the primary key: it returns at most one row per requested ID, so a
     * duplicate-free {@code snapshotIds} can never come back with more rows than it asked for.
     */
    private ResolvedRoutes snapshotRoutes(Collection<String> snapshotIds) {
        if (snapshotIds.isEmpty()) {
            return new ResolvedRoutes(List.of(), List.of());
        }
        var snapshots = snapshotRepository.findAllByIdIn(snapshotIds);
        if (snapshots.size() < snapshotIds.size()) {
            Set<String> resolvedIds = snapshots.stream()
                    .map(AbstractEntity::getId)
                    .collect(Collectors.toSet());
            List<String> unresolvedIds = snapshotIds.stream()
                    .filter(id -> !resolvedIds.contains(id))
                    .toList();
            return new ResolvedRoutes(List.of(), unresolvedIds);
        }
        List<Route> routes = snapshots.stream()
                .flatMap(snapshot -> routesGetterService
                        .getRoutes(new SnapshotAdapter(snapshot), integrationServiceCatalog).stream())
                .toList();
        return new ResolvedRoutes(routes, List.of());
    }

    /**
     * Returns {@code ownPaths} minus {@code retainedPaths}: the paths only the snapshot being
     * removed claims, and so the only ones safe to strip from a tier shared with other snapshots.
     */
    private Set<GatewayPathMatch> unsharedPaths(Set<GatewayPathMatch> ownPaths, Set<GatewayPathMatch> retainedPaths) {
        Set<GatewayPathMatch> unshared = new HashSet<>(ownPaths);
        unshared.removeAll(retainedPaths);
        return unshared;
    }

    /**
     * Builds the set of this snapshot's own route path matches (type + value, via
     * {@link GatewayPathMatch}) for a single gateway tier, so they can be compared exactly
     * against the paths recorded in the tier's HTTPRoute CR (mirroring
     * {@code HttpRouteResourceBuilder}'s own path bookkeeping). Egress routes
     * ({@code EXTERNAL_SENDER}/{@code EXTERNAL_SERVICE}, whose "paths" are absolute target
     * URLs, not gateway paths) are excluded by the tier predicate.
     */
    private Set<GatewayPathMatch> tierOwnPaths(List<Route> ownRoutes, Predicate<RouteType> tierPredicate) {
        return ownRoutes.stream()
                .filter(route -> tierPredicate.test(route.getType()))
                .map(route -> GatewayPathMatch.forPath(baseRoutePrefix + route.getPath()))
                .collect(Collectors.toSet());
    }

    /**
     * Builds the set of this snapshot's own egress route path matches. Unlike {@link #tierOwnPaths},
     * this reads {@code gatewayPrefix} (the resolved internal path, e.g. {@code /system/{id}}), not
     * {@code baseRoutePrefix + path} -- egress routes' {@code path} is the resolved external target
     * URL, not a gateway-facing path. {@code EXTERNAL_SERVICE} routes are additionally run through
     * {@link EgressServiceRouteFormatter} so the computed match reflects the hashed {@code gatewayPrefix}
     * the build pipeline's own {@code EgressRouteResourceBuilder} actually wrote to the cluster.
     */
    private Set<GatewayPathMatch> egressOwnPaths(List<Route> ownRoutes) {
        return ownRoutes.stream()
                .filter(route -> route.getType() == RouteType.EXTERNAL_SENDER
                        || route.getType() == RouteType.EXTERNAL_SERVICE)
                .map(EgressServiceRouteFormatter::formatServiceRoute)
                .map(route -> GatewayPathMatch.forPath(route.getGatewayPrefix()))
                .collect(Collectors.toSet());
    }

    @SuppressWarnings("unchecked")
    private void stripPathsFromTier(String routeName, Set<GatewayPathMatch> ownPaths, String tierName) {
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
                    GatewayPathMatch path = extractRulePath(rule);
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
     * Reads {@code matches[0].path.{type,value}} out of a raw (deserialized-from-YAML/JSON)
     * HTTPRoute rule, returning {@code null} for any shape that doesn't hold a readable path (no
     * {@code matches}, an empty {@code matches} list, a header-only match with no {@code path},
     * and similar) instead of throwing. The caller treats a {@code null} result as "not matched
     * by any of ownPaths" and preserves the rule.
     */
    private GatewayPathMatch extractRulePath(Map<String, Object> rule) {
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
            if (!(path.get("value") instanceof String value)) {
                return null;
            }
            // Gateway API defaults HTTPPathMatch.type to PathPrefix when omitted.
            String type = path.get("type") instanceof String t ? t : "PathPrefix";
            return GatewayPathMatch.of(type, value);
        } catch (RuntimeException e) {
            return null;
        }
    }
}
