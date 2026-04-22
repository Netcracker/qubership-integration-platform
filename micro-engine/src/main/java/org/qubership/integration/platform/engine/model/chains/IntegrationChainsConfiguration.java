package org.qubership.integration.platform.engine.model.chains;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IntegrationChainsConfiguration {
    private List<ExtendedSourceDefinition> sources = Collections.emptyList();
    private List<LibraryDefinition> libraries = Collections.emptyList();
}
