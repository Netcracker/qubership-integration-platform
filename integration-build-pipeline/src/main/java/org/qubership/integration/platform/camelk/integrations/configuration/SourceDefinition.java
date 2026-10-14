package org.qubership.integration.platform.camelk.integrations.configuration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SourceDefinition {
    private String id;
    private String chainId;
    private String name;
    private String language;
    private String location;
}
