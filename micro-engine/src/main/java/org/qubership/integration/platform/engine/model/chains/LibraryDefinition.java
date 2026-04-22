package org.qubership.integration.platform.engine.model.chains;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LibraryDefinition {
    private String specificationId;
    private String location;
}
