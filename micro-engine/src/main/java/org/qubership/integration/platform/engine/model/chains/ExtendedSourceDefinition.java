package org.qubership.integration.platform.engine.model.chains;

import lombok.Getter;
import lombok.Setter;
import org.apache.camel.k.SourceDefinition;

public class ExtendedSourceDefinition extends SourceDefinition {
    @Getter
    @Setter
    private String chainId;
}
