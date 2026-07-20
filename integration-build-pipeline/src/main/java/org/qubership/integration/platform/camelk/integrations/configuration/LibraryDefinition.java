package org.qubership.integration.platform.camelk.integrations.configuration;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LibraryDefinition {
    private String specificationId;
    private String location;
}
