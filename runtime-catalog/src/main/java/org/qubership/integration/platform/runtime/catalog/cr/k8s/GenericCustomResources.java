package org.qubership.integration.platform.runtime.catalog.cr.k8s;

import io.kubernetes.client.util.ModelMapper;
import org.qubership.integration.platform.camelk.model.options.DBaaSOperatorType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Single source of truth for custom resources handled through the generic {@link KubeCustomObject}
 * model
 * Supports only one version per kind
 */
@Component
public final class GenericCustomResources {
    @Value("${spring.profiles.active}")
    private String activeProfile;

    @Value("${cip.cr.build.dbaas.enabled:true}")
    private boolean dbaasEnabled;

    @Value("${cip.cr.build.dbaas.operatorType:CORE}")
    private DBaaSOperatorType dbaasOperatorType;

    public record CustomResourceDefinition(
            String group,
            String version,
            String kind,
            String plural,
            boolean updateIfExists
    ) {
        public String apiVersion() {
            return group.isEmpty() ? version : group + "/" + version;
        }
    }

    private boolean isLocalDev() {
        return "localdev".equals(activeProfile);
    }

    // DBaaSResourcesBuilder generates InternalDatabase only in this mode. Outside it, a cluster
    // may lack the CRD, and listing a domain's resources would fail with 404.
    private boolean isInternalDatabaseGenerated() {
        return dbaasEnabled && DBaaSOperatorType.DBAAS.equals(dbaasOperatorType);
    }

    public Map<String, CustomResourceDefinition> getCustomResourceDefinitions() {
        return isLocalDev()
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(
                        Stream.concat(
                            Stream.of(
                                new CustomResourceDefinition("netcracker.com", "v1alpha", "FacadeService", "facadeservices", false),
                                new CustomResourceDefinition("core.netcracker.com", "v1", "Mesh", "meshes", true),
                                new CustomResourceDefinition("core.netcracker.com", "v1", "DBaaS", "dbaases", false)
                            ),
                            isInternalDatabaseGenerated()
                                ? Stream.of(new CustomResourceDefinition(
                                        "dbaas.netcracker.com", "v1", "InternalDatabase", "internaldatabases", false))
                                : Stream.empty()
                        ).collect(Collectors.toMap(CustomResourceDefinition::kind, Function.identity())));
    }

    public void registerModelMaps() {
        for (CustomResourceDefinition definition : getCustomResourceDefinitions().values()) {
            ModelMapper.addModelMap(
                    definition.group(),
                    definition.version(),
                    definition.kind(),
                    definition.plural(),
                    KubeCustomObject.class,
                    KubeCustomObjectList.class);
        }
    }

    public CustomResourceDefinition definitionFor(String kind) {
        CustomResourceDefinition definition = getCustomResourceDefinitions().get(kind);
        if (definition == null) {
            throw new IllegalArgumentException("No generic custom resource definition for kind " + kind);
        }
        return definition;
    }
}
