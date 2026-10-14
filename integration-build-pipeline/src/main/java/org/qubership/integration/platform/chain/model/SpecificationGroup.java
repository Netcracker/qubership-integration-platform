package org.qubership.integration.platform.chain.model;

import java.util.Collection;

public interface SpecificationGroup extends Entity {
    Collection<ServiceSpecification> getSpecifications();
}
