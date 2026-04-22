package org.qubership.integration.platform.engine.metadata;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElementInfo {
    private String id;
    private String name;
    private String type;
    private String chainId;
    private String snapshotId;
    private String parentId;
    private String reuseId;
    private boolean hasIntermediateParents;
}
