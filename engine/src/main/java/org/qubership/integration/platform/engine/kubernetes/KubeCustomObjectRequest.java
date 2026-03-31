package org.qubership.integration.platform.engine.kubernetes;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
@AllArgsConstructor
public class KubeCustomObjectRequest {

    private final String group;
    private final String version;
    private final String resourceNamePlural;
    private final KubeCustomObject body;
}
