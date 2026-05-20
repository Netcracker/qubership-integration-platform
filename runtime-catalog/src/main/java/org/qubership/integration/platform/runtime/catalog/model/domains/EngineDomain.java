package org.qubership.integration.platform.runtime.catalog.model.domains;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class EngineDomain {
    private String id;
    private String name;
    private int replicas;
    private String namespace;
    private String version;
    private DomainType type;
}
