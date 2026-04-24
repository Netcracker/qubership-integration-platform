package org.qubership.integration.platform.runtime.catalog.kubernetes;

import io.kubernetes.client.common.KubernetesObject;
import io.kubernetes.client.openapi.models.V1ObjectMeta;

import java.util.Optional;

public class KubeUtil {
    public static Optional<String> getName(KubernetesObject obj) {
        return Optional.ofNullable(obj.getMetadata()).map(V1ObjectMeta::getName);
    }
}
