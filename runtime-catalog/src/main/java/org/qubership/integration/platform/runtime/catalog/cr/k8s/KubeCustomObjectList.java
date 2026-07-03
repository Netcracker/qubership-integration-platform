package org.qubership.integration.platform.runtime.catalog.cr.k8s;

import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.openapi.models.V1ListMeta;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class KubeCustomObjectList implements KubernetesListObject {

    @SerializedName("apiVersion")
    private String apiVersion;

    @SerializedName("kind")
    private String kind;

    @SerializedName("metadata")
    private V1ListMeta metadata;

    @SerializedName("items")
    private List<KubeCustomObject> items;
}
