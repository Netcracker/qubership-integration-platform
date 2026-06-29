package org.qubership.integration.platform.chain.model;

import java.util.Collection;
import java.util.Optional;

public interface IntegrationService extends Entity {
    ServiceType getType();

    Protocol getProtocol();

    Optional<ServiceEnvironment> getActiveEnvironment();

    Collection<ServiceEnvironment> getEnvironments();

    Collection<Label> getLabels();
}
