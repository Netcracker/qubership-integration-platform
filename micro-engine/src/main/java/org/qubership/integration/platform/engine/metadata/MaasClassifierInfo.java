package org.qubership.integration.platform.engine.metadata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class MaasClassifierInfo {
    String elementId;
    String protocol;
    String classifier;
    String namespace;
    String tenantId;
    String tenantEnabled;
}
