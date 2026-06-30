package org.qubership.integration.platform.runtime.catalog.cr.k8s;

import io.kubernetes.client.util.ModelMapper;

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
public final class GenericCustomResources {

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

    private static final Map<String, CustomResourceDefinition> DEFINITIONS_BY_KIND = Stream.of(
            new CustomResourceDefinition("netcracker.com", "v1alpha", "FacadeService", "facadeservices", false),
            new CustomResourceDefinition("core.netcracker.com", "v1", "Mesh", "meshes", true),
            new CustomResourceDefinition("core.netcracker.com", "v1", "DBaaS", "dbaases", false)
    ).collect(Collectors.toMap(CustomResourceDefinition::kind, Function.identity()));

    private GenericCustomResources() {
    }

    public static void registerModelMaps() {
        for (CustomResourceDefinition definition : DEFINITIONS_BY_KIND.values()) {
            ModelMapper.addModelMap(
                    definition.group(),
                    definition.version(),
                    definition.kind(),
                    definition.plural(),
                    KubeCustomObject.class,
                    KubeCustomObjectList.class);
        }
    }

    public static Map<String, CustomResourceDefinition> getCustomResourceDefinitions() {
        return Collections.unmodifiableMap(DEFINITIONS_BY_KIND);
    }

    public static CustomResourceDefinition definitionFor(String kind) {
        CustomResourceDefinition definition = DEFINITIONS_BY_KIND.get(kind);
        if (definition == null) {
            throw new IllegalArgumentException("No generic custom resource definition for kind " + kind);
        }
        return definition;
    }
}
