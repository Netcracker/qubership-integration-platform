package org.qubership.integration.platform.runtime.catalog.cr.k8s;

import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.*;
import lombok.*;

import java.util.List;
import java.util.Map;

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
        @SerializedName("replicas")
        private Integer replicas;

        @SerializedName("serviceAccountName")
        private String serviceAccountName;

        @SerializedName("traits")
        private Traits traits;

        @SerializedName("template")
        private PodSpecTemplate template;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class Traits {
            @SerializedName("container")
            private ContainerTrait container;

            @SerializedName("owner")
            private OwnerTrait owner;

            @SerializedName("mount")
            private MountTrait mount;

            @SerializedName("environment")
            private EnvironmentTrait environment;

            @SerializedName("camel")
            private CamelTrait camel;

            @SerializedName("jvm")
            private JvmTrait jvm;

            @SerializedName("health")
            private HealthTrait health;

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class ContainerTrait {
                @SerializedName("image")
                private String image;

                @SerializedName("imagePullPolicy")
                private String imagePullPolicy;

                @SerializedName("requestCPU")
                private String requestCPU;

                @SerializedName("requestMemory")
                private String requestMemory;

                @SerializedName("limitCPU")
                private String limitCPU;

                @SerializedName("limitMemory")
                private String limitMemory;

                @SerializedName("runAsUser")
                private Integer runAsUser;

                @SerializedName("runAsNonRoot")
                private Boolean runAsNonRoot;

                @SerializedName("seccompProfileType")
                private String seccompProfileType;

                @SerializedName("allowPrivilegeEscalation")
                private Boolean allowPrivilegeEscalation;

                @SerializedName("capabilitiesDrop")
                private List<String> capabilitiesDrop;

                @SerializedName("capabilitiesAdd")
                private List<String> capabilitiesAdd;
            }

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class OwnerTrait {
                @SerializedName("targetLabels")
                private List<String> targetLabels;
            }

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class MountTrait {
                @SerializedName("resources")
                private List<String> resources;

                @SerializedName("emptyDirs")
                private List<String> emptyDirs;

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

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class JvmTrait {
                @SerializedName("jar")
                private String jar;

                @SerializedName("options")
                private List<String> options;
            }

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class HealthTrait {
                @SerializedName("livenessProbeEnabled")
                private Boolean livenessProbeEnabled;

                @SerializedName("livenessScheme")
                private String livenessScheme;

                @SerializedName("livenessInitialDelay")
                private Integer livenessInitialDelay;

                @SerializedName("livenessTimeout")
                private Integer livenessTimeout;

                @SerializedName("livenessPeriod")
                private Integer livenessPeriod;

                @SerializedName("livenessSuccessThreshold")
                private Integer livenessSuccessThreshold;

                @SerializedName("livenessFailureThreshold")
                private Integer livenessFailureThreshold;

                @SerializedName("livenessProbe")
                private String livenessProbe;

                @SerializedName("livenessPort")
                private Integer livenessPort;

                @SerializedName("readinessProbeEnabled")
                private Boolean readinessProbeEnabled;

                @SerializedName("readinessScheme")
                private String readinessScheme;

                @SerializedName("readinessInitialDelay")
                private Integer readinessInitialDelay;

                @SerializedName("readinessTimeout")
                private Integer readinessTimeout;

                @SerializedName("readinessPeriod")
                private Integer readinessPeriod;

                @SerializedName("readinessSuccessThreshold")
                private Integer readinessSuccessThreshold;

                @SerializedName("readinessFailureThreshold")
                private Integer readinessFailureThreshold;

                @SerializedName("readinessProbe")
                private String readinessProbe;

                @SerializedName("readinessPort")
                private Integer readinessPort;

                @SerializedName("startupProbeEnabled")
                private Boolean startupProbeEnabled;

                @SerializedName("startupScheme")
                private String startupScheme;

                @SerializedName("startupInitialDelay")
                private Integer startupInitialDelay;

                @SerializedName("startupTimeout")
                private Integer startupTimeout;

                @SerializedName("startupPeriod")
                private Integer startupPeriod;

                @SerializedName("startupSuccessThreshold")
                private Integer startupSuccessThreshold;

                @SerializedName("startupFailureThreshold")
                private Integer startupFailureThreshold;

                @SerializedName("startupProbe")
                private String startupProbe;

                @SerializedName("startupPort")
                private Integer startupPort;
            }
        }

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class PodSpecTemplate {
            @SerializedName("spec")
            private PodSpec spec;

            @Data
            @NoArgsConstructor
            @AllArgsConstructor
            public static class PodSpec {
                @SerializedName("automountServiceAccountToken")
                private boolean automountServiceAccountToken;

                @SerializedName("volumes")
                private List<V1Volume> volumes;

                @SerializedName("initContainers")
                private List<V1Container> initContainers;

                @SerializedName("containers")
                private List<V1Container> containers;

                @SerializedName("ephemeralContainers")
                private List<V1EphemeralContainer> ephemeralContainers;

                @SerializedName("terminationGracePeriodSeconds")
                private Long terminationGracePeriodSeconds;

                @SerializedName("activeDeadlineSeconds")
                private Long activeDeadlineSeconds;

                @SerializedName("nodeSelector")
                private Map<String, String> nodeSelector;

                @SerializedName("topologySpreadConstraints")
                private List<V1TopologySpreadConstraint> topologySpreadConstraints;

                @SerializedName("securityContext")
                private V1PodSecurityContext securityContext;
            }
        }
    }
}
