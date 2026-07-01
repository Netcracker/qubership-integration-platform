package org.qubership.integration.platform.runtime.catalog.cr.k8s;

import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KubeCustomObject implements KubernetesObject {

    @SerializedName("apiVersion")
    private String apiVersion;

    @SerializedName("kind")
    private String kind;

    @SerializedName("subKind")
    private String subKind;

    @SerializedName("metadata")
    private V1ObjectMeta metadata;

    @SerializedName("spec")
    private Map<String, Object> spec;
}
