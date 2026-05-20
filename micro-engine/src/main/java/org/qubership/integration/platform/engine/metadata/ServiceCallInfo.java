package org.qubership.integration.platform.engine.metadata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ServiceCallInfo {
    String retryCount;
    String retryDelay;
    String protocol;
    String externalServiceName;
    String externalServiceAddress;
    String externalServiceEnvironmentName;
    String specificationId;
}
