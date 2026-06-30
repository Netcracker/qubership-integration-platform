package org.qubership.integration.platform.runtime.catalog.cr.k8s;

import io.kubernetes.client.util.ModelMapper;

import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Single source of truth for custom resources handled through the generic {@link KubeCustomObject}
 * model
 */
public final class GenericCustomResources {

    public record Definition(
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

    private static final Map<String, Definition> DEFINITIONS_BY_KIND = Stream.of(
            new Definition("netcracker.com", "v1alpha", "FacadeService", "facadeservices", false),
            new Definition("core.netcracker.com", "v1", "Mesh", "meshes", true),
            new Definition("core.netcracker.com", "v1", "DBaaS", "dbaases", false)
    ).collect(Collectors.toMap(Definition::kind, Function.identity()));

    private GenericCustomResources() {
    }

    public static void registerModelMaps() {
        for (Definition definition : DEFINITIONS_BY_KIND.values()) {
            ModelMapper.addModelMap(
                    definition.group(),
                    definition.version(),
                    definition.kind(),
                    definition.plural(),
                    KubeCustomObject.class,
                    KubeCustomObjectList.class);
        }
    }

    public static String pluralFor(String kind) {
        return definitionFor(kind).plural();
    }

    public static boolean updateIfExistsFor(String kind) {
        return definitionFor(kind).updateIfExists();
    }

    private static Definition definitionFor(String kind) {
        Definition definition = DEFINITIONS_BY_KIND.get(kind);
        if (definition == null) {
            throw new IllegalArgumentException("No generic custom resource definition for kind " + kind);
        }
        return definition;
    }
}
