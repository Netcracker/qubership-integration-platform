package org.qubership.integration.platform.engine.metadata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DeploymentInfo {
    private String id;
    private String name;
    private Long timestamp;
    private ChainInfo chain;
    private SnapshotInfo snapshot;
}
