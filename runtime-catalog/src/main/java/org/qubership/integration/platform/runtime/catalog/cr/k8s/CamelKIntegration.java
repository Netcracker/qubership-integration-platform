package org.qubership.integration.platform.runtime.catalog.cr.k8s;

import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.*;

import java.util.List;

public class CamelKIntegration implements KubernetesObject {
    public static final String SERIALIZED_NAME_API_VERSION = "apiVersion";
    @Setter
    @SerializedName(SERIALIZED_NAME_API_VERSION)
    private String apiVersion;

    public static final String SERIALIZED_NAME_KIND = "kind";
    @Setter
    @SerializedName(SERIALIZED_NAME_KIND)
    private String kind;

    public static final String SERIALIZED_NAME_METADATA = "metadata";
    @Setter
    @SerializedName(SERIALIZED_NAME_METADATA)
    private V1ObjectMeta metadata;

    @Getter
    @Setter
    @SerializedName("spec")
    private IntegrationSpec spec;

    public CamelKIntegration() {
    }

    public CamelKIntegration(String apiVersion, String kind, V1ObjectMeta metadata, IntegrationSpec spec) {
        this.apiVersion = apiVersion;
        this.kind = kind;
        this.metadata = metadata;
        this.spec = spec;
    }

    @Override
    public V1ObjectMeta getMetadata() {
        return metadata;
    }

    @Override
    public String getApiVersion() {
        return apiVersion;
    }

    @Override
    public String getKind() {
        return kind;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class IntegrationSpec {
        @SerializedName("serviceAccountName")
        private String serviceAccountName;

        @SerializedName("traits")
        private Traits traits;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Traits {
            @SerializedName("container")
            private ContainerTrait container;

            @SerializedName("mount")
            private MountTrait mount;

            @SerializedName("environment")
            private EnvironmentTrait environment;

            @SerializedName("camel")
            private CamelTrait camel;

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class ContainerTrait {
                @SerializedName("image")
                private String image;

                @SerializedName("imagePullPolicy")
                private String imagePullPolicy;
            }

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class MountTrait {
                @SerializedName("resources")
                private List<String> resources;

                @SerializedName("hotReload")
                private Boolean hotReload;
            }

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class EnvironmentTrait {
                @SerializedName("vars")
                private List<String> vars;
            }

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class CamelTrait {
                @SerializedName("properties")
                private List<String> properties;
            }
        }
    }
}
