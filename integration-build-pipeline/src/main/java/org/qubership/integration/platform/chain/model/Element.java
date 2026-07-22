package org.qubership.integration.platform.chain.model;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;

public interface Element extends Entity {
    String getType();

    Optional<Element> getParent();

    Optional<Element> getSwimlane();

    Optional<Snapshot> getSnapshot();

    Map<String, Object> getProperties();

    Collection<Element> getChildren();

    Collection<Connection> getInputConnections();

    Collection<Connection> getOutputConnections();

    Optional<String> getOriginalId();

    Chain getChain();

    Optional<ServiceEnvironment> getEnvironment();

    boolean isContainer();
}
