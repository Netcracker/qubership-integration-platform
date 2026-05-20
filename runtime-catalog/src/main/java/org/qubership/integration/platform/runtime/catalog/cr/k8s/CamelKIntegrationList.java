package org.qubership.integration.platform.runtime.catalog.cr.k8s;

import com.google.gson.annotations.SerializedName;
import io.kubernetes.client.common.KubernetesListObject;
import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ListMeta;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.List;

@NoArgsConstructor
@AllArgsConstructor
public class CamelKIntegrationList implements KubernetesListObject {
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
    private V1ListMeta metadata;

    @Setter
    @SerializedName("items")
    private List<CamelKIntegration> items;

    @Override
    public V1ListMeta getMetadata() {
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

    @Override
    public List<? extends KubernetesObject> getItems() {
        return items;
    }
}
