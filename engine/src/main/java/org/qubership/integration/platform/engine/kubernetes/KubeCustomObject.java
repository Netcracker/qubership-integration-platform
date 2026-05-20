package org.qubership.integration.platform.engine.kubernetes;

import io.kubernetes.client.openapi.models.V1ObjectMeta;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.Setter;

import java.util.Map;

@Getter
@Setter
@Builder
@AllArgsConstructor
public class KubeCustomObject {

    private String apiVersion;
    private String kind;
    private V1ObjectMeta metadata;
    private Map<String, Object> spec;
}
