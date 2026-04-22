package org.qubership.integration.platform.engine.metadata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.annotation.Nullable;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder(toBuilder = true)
public class RouteRegistrationInfo {
    private String snapshotId;

    private String path;
    @Nullable
    private String gatewayPrefix; // for senders and services
    @Nullable
    private String variableName; // to substitute with a resolved path
    private RouteType type;
    @Builder.Default
    private Long connectTimeout = -1L;
}
