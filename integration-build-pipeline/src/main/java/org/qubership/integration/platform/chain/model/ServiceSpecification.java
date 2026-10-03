package org.qubership.integration.platform.chain.model;

import java.util.Collection;

public interface ServiceSpecification extends Entity {
    Collection<SpecificationSource> getSources();
}
